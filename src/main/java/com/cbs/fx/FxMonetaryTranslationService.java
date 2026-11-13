package com.cbs.fx;

import static com.cbs.fx.FxMonetaryTranslationModels.*;
import com.cbs.access.AccessService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.BankDatabaseProvisioner;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class FxMonetaryTranslationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ReferenceDataService reference;
    private final BankDatabaseProvisioner databases;
    private final TransactionTemplate tx;

    public FxMonetaryTranslationService(JdbcTemplate jdbc, TenantRepository tenants,
                                       ReferenceDataService reference, BankDatabaseProvisioner databases,
                                       PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.reference = reference;
        this.databases = databases;
        this.tx = new TransactionTemplate(manager);
    }

    public MonetaryTranslationReport translate(Actor actor, UUID bankId, LocalDate asOf) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            var settings = reference.read(bank);
            LocalDate date = asOf != null ? asOf : settings.businessDate();
            String functional = bank.legalEntities().getFirst().functionalCurrency();

            // Find all active foreign currency posting ledgers
            var foreignLedgers = jdbc.query("""
                SELECT l.id, l.currency, c.code, c.name, c.category
                FROM platform.ledger_registry l
                JOIN platform.chart_account c ON c.id = l.chart_account_id
                WHERE l.tenant_id = ? AND l.currency <> ? AND l.active AND l.delivery = 'APPLIED' AND c.active AND c.kind = 'POSTING'
                ORDER BY c.code
                """, (rs, rowNum) -> Map.of(
                    "id", rs.getObject("id", UUID.class),
                    "currency", rs.getString("currency"),
                    "code", rs.getString("code"),
                    "name", rs.getString("name"),
                    "category", rs.getString("category")
            ), bankId, functional);

            List<MonetaryItem> items = new ArrayList<>();
            BigDecimal totalAssets = BigDecimal.ZERO;
            BigDecimal totalLiabilities = BigDecimal.ZERO;

            try (Connection conn = databases.openOwned(bank.id(), bank.databaseBinding().databaseReference())) {
                for (var ledger : foreignLedgers) {
                    UUID ledgerId = (UUID) ledger.get("id");
                    String currency = (String) ledger.get("currency");
                    String code = (String) ledger.get("code");
                    String name = (String) ledger.get("name");
                    String category = (String) ledger.get("category");

                    BigDecimal debit = BigDecimal.ZERO;
                    BigDecimal credit = BigDecimal.ZERO;

                    try (PreparedStatement ps = conn.prepareStatement("SELECT debit, credit FROM bank.ledger_balance WHERE ledger_id = ?")) {
                        ps.setObject(1, ledgerId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                debit = rs.getBigDecimal("debit");
                                credit = rs.getBigDecimal("credit");
                                if (debit == null) debit = BigDecimal.ZERO;
                                if (credit == null) credit = BigDecimal.ZERO;
                            }
                        }
                    }

                    boolean isAsset = category.equals("ASSET") || category.equals("EXPENSE");
                    BigDecimal foreignBal = isAsset ? debit.subtract(credit) : credit.subtract(debit);

                    // Fetch closing rate for foreign currency to functional currency
                    BigDecimal closingRate = getClosingRate(bankId, currency, functional, date);
                    int functionalScale = Currency.getInstance(functional).getDefaultFractionDigits();
                    BigDecimal translated = foreignBal.multiply(closingRate).setScale(functionalScale, RoundingMode.HALF_EVEN);

                    if (isAsset) {
                        totalAssets = totalAssets.add(translated);
                    } else {
                        totalLiabilities = totalLiabilities.add(translated);
                    }

                    items.add(new MonetaryItem(
                            ledgerId, code, name, category, currency,
                            foreignBal.stripTrailingZeros().toPlainString(),
                            closingRate.stripTrailingZeros().toPlainString(),
                            translated.stripTrailingZeros().toPlainString(),
                            functional
                    ));
                }
            } catch (Exception e) {
                throw new TenantException(409, "DATABASE_ERROR", "Failed to inspect ledger balances: " + e.getMessage());
            }

            BigDecimal netExposure = totalAssets.subtract(totalLiabilities);
            MonetaryTranslationReport report = new MonetaryTranslationReport(
                    bankId, date, functional, items.size(),
                    totalAssets.stripTrailingZeros().toPlainString(),
                    totalLiabilities.stripTrailingZeros().toPlainString(),
                    netExposure.stripTrailingZeros().toPlainString(),
                    items, Instant.now()
            );

            UUID runId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO platform.monetary_translation_run
                (id, tenant_id, business_date, functional_currency, total_monetary_items, net_unrealized_gain_loss, payload, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, runId, bankId, date, functional, items.size(), netExposure,
                    JSON.writeValueAsString(report), actor.subject());

            return report;
        });
    }

    private BigDecimal getClosingRate(UUID bankId, String foreignCurrency, String functionalCurrency, LocalDate date) {
        // Try closing rate table first
        var closingRates = jdbc.queryForList("""
            SELECT payload->>'rate' FROM platform.fx_closing_rate
            WHERE tenant_id = ? AND business_date = ?
            ORDER BY created_at DESC LIMIT 1
            """, String.class, bankId, date);
        if (!closingRates.isEmpty()) {
            return new BigDecimal(closingRates.getFirst());
        }

        // Try fx_rate table
        String pairKey = foreignCurrency.compareTo(functionalCurrency) < 0 ?
                foreignCurrency + "/" + functionalCurrency : functionalCurrency + "/" + foreignCurrency;
        var rates = jdbc.queryForList("""
            SELECT payload->>'midRate' FROM platform.fx_rate
            WHERE tenant_id = ? AND pair_key = ? AND effective_from <= ? AND effective_to >= ?
            AND NOT EXISTS (SELECT 1 FROM platform.fx_rate_withdrawal w WHERE w.rate_id = id)
            ORDER BY created_at DESC LIMIT 1
            """, String.class, bankId, pairKey, date, date);
        if (!rates.isEmpty()) {
            return new BigDecimal(rates.getFirst());
        }

        // Default to parity if no market rate defined
        return BigDecimal.ONE;
    }
}
