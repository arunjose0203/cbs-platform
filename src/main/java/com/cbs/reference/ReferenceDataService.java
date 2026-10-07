package com.cbs.reference;

import static com.cbs.reference.ReferenceModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ReferenceDataService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> LANGUAGE_CODES=Set.of(Locale.getISOLanguages());
    private final JdbcTemplate jdbc;
    private final TenantRepository repository;
    private final TenantService tenants;
    private final AccessService access;
    public ReferenceDataService(JdbcTemplate jdbc,TenantRepository repository,TenantService tenants,AccessService access) {
        this.jdbc=jdbc;this.repository=repository;this.tenants=tenants;this.access=access;
    }
    public Catalogue catalogue(Actor actor) {
        AccessService.admin(actor);
        return new Catalogue(Currency.getAvailableCurrencies().stream().filter(c->c.getDefaultFractionDigits()>=0)
                .sorted(Comparator.comparing(Currency::getCurrencyCode)).map(c->new CurrencyInfo(c.getCurrencyCode(),c.getDisplayName(Locale.ENGLISH),c.getDefaultFractionDigits())).toList(),
                LANGUAGE_CODES.stream().sorted().map(code->new LanguageInfo(code,Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH))).toList(),
                ZoneId.getAvailableZoneIds().stream().sorted().toList(),
                List.of("HALF_EVEN","HALF_UP","HALF_DOWN","UP","DOWN","CEILING","FLOOR"));
    }
    @Transactional
    public Settings get(Actor actor,UUID bankId) {
        if(!actor.platformAdmin()) tenants.bankContext(actor,bankId,false);
        // A shared tenant lock keeps the config and its version consistent with approved writes.
        jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",rs->{},bankId);
        return read(repository.get(bankId,false));
    }
    public Settings read(Tenant bank) {
        return jdbc.queryForObject("SELECT * FROM platform.bank_settings WHERE tenant_id=?",(row,n)->new Settings(bank.id(),bank.version(),
                JSON.readValue(row.getString("currencies"),new TypeReference<List<CurrencyRule>>(){}),
                JSON.readValue(row.getString("languages"),new TypeReference<List<String>>(){}),bank.defaultLanguage(),row.getString("business_timezone"),
                row.getDate("business_date").toLocalDate(),JSON.readValue(row.getString("weekend_days"),new TypeReference<List<Integer>>(){}),
                JSON.readValue(row.getString("holidays"),new TypeReference<List<Holiday>>(){})),bank.id());
    }
    public void validate(Tenant bank,Change input) {
        com.cbs.accounting.DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=input.expectedVersion()) throw error(409,"STALE_VERSION");
        if(bank.status()==Status.PROVISIONING) throw error(409,"PROVISIONING_IN_PROGRESS");
        Set<String> enabled=new HashSet<>(),codes=new HashSet<>();
        for(CurrencyRule rule:input.currencies()) {
            try {
                if(Currency.getInstance(rule.code()).getDefaultFractionDigits()<0 || !codes.add(rule.code())) throw new IllegalArgumentException();
                if(!catalogueRoundingModes().contains(rule.roundingMode())) throw new IllegalArgumentException();
            } catch(IllegalArgumentException exception) { throw error(400,"INVALID_CURRENCY_SETTINGS"); }
            if(rule.enabled())enabled.add(rule.code());
        }
        if(enabled.isEmpty() || bank.legalEntities().stream().anyMatch(entity->!enabled.contains(entity.functionalCurrency())))
            throw error(400,"FUNCTIONAL_CURRENCY_REQUIRED");
        var ledgerCurrencies=jdbc.queryForList("SELECT DISTINCT currency FROM platform.ledger_registry WHERE tenant_id=?",String.class,bank.id());
        if(!enabled.containsAll(ledgerCurrencies))throw error(409,"CURRENCY_USED_BY_LEDGER");
        var accountCurrencies=jdbc.queryForList("SELECT DISTINCT currency FROM platform.account_registry WHERE tenant_id=? AND (state<>'CLOSED' OR delivery<>'APPLIED')",String.class,bank.id());
        if(!enabled.containsAll(accountCurrencies))throw error(409,"CURRENCY_USED_BY_ACCOUNT");
        var productCurrencies=jdbc.queryForList("SELECT DISTINCT currency FROM platform.deposit_product WHERE tenant_id=? AND active",String.class,bank.id());
        if(!enabled.containsAll(productCurrencies))throw error(409,"CURRENCY_USED_BY_PRODUCT");
        Set<String> languages=new HashSet<>();
        for(String language:input.languages()) if(!languages.add(language(language)))throw error(400,"INVALID_LANGUAGE_SETTINGS");
        if(!languages.contains(language(input.defaultLanguage())))throw error(400,"DEFAULT_LANGUAGE_REQUIRED");
        if(!ZoneId.getAvailableZoneIds().contains(input.businessTimezone()))throw error(400,"INVALID_CALENDAR");
        if(new HashSet<>(input.weekendDays()).size()!=input.weekendDays().size() || input.weekendDays().size()>6 || input.weekendDays().stream().anyMatch(day->day<1||day>7))throw error(400,"INVALID_CALENDAR");
        Set<LocalDate> dates=new HashSet<>();
        for(Holiday holiday:input.holidays())if(!dates.add(holiday.date()) || !validDate(holiday.date()))throw error(400,"INVALID_CALENDAR");
        if(!validDate(input.businessDate()) || input.businessDate().isBefore(read(bank).businessDate()))throw error(400,"BUSINESS_DATE_BACKWARDS");
        if(!input.businessDate().equals(read(bank).businessDate())&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry WHERE tenant_id=?) OR EXISTS(SELECT 1 FROM platform.business_day_close WHERE tenant_id=?)",Boolean.class,bank.id(),bank.id()))throw error(409,"BUSINESS_DATE_REQUIRES_CLOSE");
        if(!working(input.businessDate(),input.weekendDays(),input.holidays()))throw error(400,"BUSINESS_DATE_NOT_WORKING");
    }
    @Transactional
    public void apply(Actor actor,UUID bankId,Change input,String reason) {
        AccessService.admin(actor);
        Tenant bank=repository.get(bankId,true);
        validate(bank,input);
        Settings before=read(bank);
        jdbc.update("""
                UPDATE platform.bank_settings SET currencies=?::jsonb,languages=?::jsonb,business_timezone=?,business_date=?,
                weekend_days=?::jsonb,holidays=?::jsonb,updated_at=clock_timestamp() WHERE tenant_id=?
                """,JSON.writeValueAsString(input.currencies()),JSON.writeValueAsString(input.languages()),input.businessTimezone(),
                java.sql.Date.valueOf(input.businessDate()),JSON.writeValueAsString(input.weekendDays()),JSON.writeValueAsString(input.holidays()),bankId);
        repository.change(bank,bank.status(),bank.displayName(),input.defaultLanguage());
        repository.audit(actor,bankId,"BANK_SETTINGS_UPDATED",bank.version()+1,bank.status(),bank.status(),reason,null,null,
                Map.of("before",values(before),"after",values(input)));
    }
    public Map<String,Object> values(Settings input) {
        return values(input.currencies(),input.languages(),input.defaultLanguage(),input.businessTimezone(),input.businessDate(),input.weekendDays(),input.holidays());
    }
    public Map<String,Object> values(Change input) {
        return values(input.currencies(),input.languages(),input.defaultLanguage(),input.businessTimezone(),input.businessDate(),input.weekendDays(),input.holidays());
    }
    private Map<String,Object> values(List<CurrencyRule> currencies,List<String> languages,String language,String timezone,LocalDate date,List<Integer> weekends,List<Holiday> holidays) {
        return Map.of("currencies",currencies,"languages",languages,"defaultLanguage",language,"businessTimezone",timezone,
                "businessDate",date.toString(),"weekendDays",weekends,"holidays",holidays);
    }
    @Transactional
    public DayInfo day(Actor actor,UUID bankId,LocalDate date) {
        if(!validDate(date))throw error(400,"INVALID_CALENDAR");
        Settings settings=get(actor,bankId);
        return new DayInfo(date,working(date,settings.weekendDays(),settings.holidays()),settings.holidays().stream().filter(h->h.date().equals(date)).map(Holiday::name).findFirst().orElse(null),
                nextWorkingDay(date,settings.weekendDays(),settings.holidays()));
    }
    public static LocalDate nextWorkingDay(LocalDate date,List<Integer> weekends,List<Holiday> holidays) {
        for(int days=1;days<=3660;days++) { LocalDate next=date.plusDays(days);if(working(next,weekends,holidays))return next; }
        throw error(400,"NO_WORKING_DAY");
    }
    public static boolean working(LocalDate date,List<Integer> weekends,List<Holiday> holidays) {
        return !weekends.contains(date.getDayOfWeek().getValue()) && holidays.stream().noneMatch(h->h.date().equals(date));
    }
    @Transactional
    public AmountPreview round(Actor actor,UUID bankId,String code,String amount) {
        Settings settings=get(actor,bankId);
        CurrencyRule rule=settings.currencies().stream().filter(c->c.code().equals(code)&&c.enabled()).findFirst().orElseThrow(()->error(400,"CURRENCY_NOT_ENABLED"));
        try {
            if(amount==null || !amount.matches("-?[0-9]{1,24}(\\.[0-9]{1,12})?"))throw new IllegalArgumentException();
            int digits=Currency.getInstance(code).getDefaultFractionDigits();
            return new AmountPreview(code,digits,rule.roundingMode(),new BigDecimal(amount).setScale(digits,RoundingMode.valueOf(rule.roundingMode())).toPlainString());
        }catch(IllegalArgumentException exception){throw error(400,"INVALID_AMOUNT");}
    }
    public Preference preference(Actor actor) {
        var staff=access.requireAssignable(actor.issuer(),actor.subject());
        String language=jdbc.queryForObject("SELECT preferred_language FROM platform.staff_identity WHERE id=?",String.class,staff.id());
        return new Preference(language==null?"en":language);
    }
    public Preference preference(Actor actor,Preference input) {
        var staff=access.requireAssignable(actor.issuer(),actor.subject());
        String language=language(input.language());
        if(!List.of("en","fr","sw").contains(language))throw error(400,"UI_LANGUAGE_NOT_SUPPORTED");
        jdbc.update("UPDATE platform.staff_identity SET preferred_language=? WHERE id=?",language,staff.id());
        return new Preference(language);
    }
    private static String language(String tag) {
        try {
            Locale locale=new Locale.Builder().setLanguageTag(tag).build();
            if(!LANGUAGE_CODES.contains(locale.getLanguage())||!locale.toLanguageTag().equals(tag))throw new IllegalArgumentException();
            return tag;
        }catch(RuntimeException exception){throw error(400,"INVALID_LANGUAGE_SETTINGS");}
    }
    private static boolean validDate(LocalDate date) { return date.getYear()>=1900 && date.getYear()<=2200; }
    private static List<String> catalogueRoundingModes() { return List.of("HALF_EVEN","HALF_UP","HALF_DOWN","UP","DOWN","CEILING","FLOOR"); }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Reference settings could not be applied");}
}
