package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import com.cbs.contracts.ContractModels;
import com.cbs.contracts.LoanArrears;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scheduled customer notices (Round 10): term deposits about to mature, loan instalments about to fall
 * due, and loans crossing an arrears threshold. Each notice has a deterministic source, so a scan can
 * run any number of times and queues each notice once per holder. The scan only adds outbox rows; it
 * never changes the bank's version or any financial record.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class NoticeService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static final int CONTRACT_LIMIT=5000;
    private final JdbcTemplate jdbc;private final NotificationService notifications;private final TransactionTemplate tx;
    private final int maturityDays,dueDays;private final List<Integer> thresholds;

    public NoticeService(JdbcTemplate jdbc,NotificationService notifications,PlatformTransactionManager manager,
            @Value("${cbs.notifications.notices.maturity-days:7}") int maturityDays,@Value("${cbs.notifications.notices.loan-due-days:3}") int dueDays,
            @Value("${cbs.notifications.notices.arrears-days:1,30,60,90}") List<Integer> thresholds){
        if(maturityDays<0||maturityDays>60||dueDays<0||dueDays>30||thresholds.isEmpty()||thresholds.stream().anyMatch(d->d<1||d>365))throw new IllegalStateException("Invalid notice settings");
        this.jdbc=jdbc;this.notifications=notifications;this.tx=new TransactionTemplate(manager);this.maturityDays=maturityDays;this.dueDays=dueDays;
        this.thresholds=thresholds.stream().sorted().distinct().toList();
    }

    static UUID source(String... parts){return UUID.nameUUIDFromBytes(String.join(":",parts).getBytes(StandardCharsets.UTF_8));}

    /** Scans every active bank; returns the number of notices considered (not necessarily queued). */
    public int scan(){
        int count=0;
        for(UUID bank:jdbc.queryForList("SELECT t.id FROM platform.tenant t JOIN platform.bank_settings s ON s.tenant_id=t.id WHERE t.status='ACTIVE' ORDER BY t.id",UUID.class)){
            try{count+=scan(bank);}catch(RuntimeException e){LoggerFactory.getLogger(NoticeService.class).warn("Customer notices for a bank will be retried: {}",e.getClass().getSimpleName());}
        }
        return count;
    }

    public int scan(UUID bank){
        LocalDate date=jdbc.queryForObject("SELECT business_date FROM platform.bank_settings WHERE tenant_id=?",LocalDate.class,bank);
        var notices=new ArrayList<Notice>();
        var rows=jdbc.queryForList("SELECT id,account_id,kind,coalesce(current_terms,payload->'terms')::text AS terms FROM platform.contract WHERE tenant_id=? AND status='ACTIVE' ORDER BY id LIMIT ?",bank,CONTRACT_LIMIT);
        for(var row:rows){
            UUID id=(UUID)row.get("id"),account=(UUID)row.get("account_id");var terms=JSON.readValue((String)row.get("terms"),ContractModels.Open.class);
            if("TERM_DEPOSIT".equals(row.get("kind"))){
                var maturity=terms.maturityDate();
                if(!maturity.isBefore(date)&&!maturity.isAfter(date.plusDays(maturityDays)))
                    notices.add(new Notice(bank,account,Event.TD_MATURITY,source("TD_MATURITY",id.toString(),maturity.toString()),Map.of("date",maturity.toString(),"reference",terms.agreementReference())));
                continue;
            }
            for(int n=1;n<=terms.instalments();n++){
                var due=terms.startDate().plusMonths(n);
                if(due.isAfter(date.plusDays(dueDays)))break;
                if(!due.isBefore(date))notices.add(new Notice(bank,account,Event.LOAN_DUE,source("LOAN_DUE",id.toString(),due.toString()),Map.of("date",due.toString(),"reference",terms.agreementReference())));
            }
            var arrears=arrears(bank,id,date);
            if(arrears!=null){
                long days=arrears.daysPastDue();Integer crossed=null;for(int t:thresholds)if(days>=t)crossed=t;
                // One notice per threshold per episode: a loan that is cured and falls behind again is notified again.
                var oldest=arrears.instalments().stream().filter(d->d.daysPastDue()>0).map(LoanArrears.Due::dueDate).min(Comparator.naturalOrder());
                if(crossed!=null&&oldest.isPresent())notices.add(new Notice(bank,account,Event.LOAN_ARREARS,source("LOAN_ARREARS",id.toString(),oldest.get().toString(),crossed.toString()),Map.of("days",Long.toString(days),"reference",terms.agreementReference())));
            }
        }
        for(var notice:notices)tx.executeWithoutResult(t->notifications.record(notice));
        return notices.size();
    }

    private LoanArrears.Report arrears(UUID bank,UUID contract,LocalDate date){
        var events=jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at,id LIMIT 10001",(r,n)->JSON.readValue(r.getString(1),ContractModels.Posting.class),bank,contract);
        if(events.isEmpty()||events.size()>10000)return null;
        try{var report=LoanArrears.calculate(events,date);return report.reconciled()?report:null;}catch(RuntimeException e){return null;}
    }
}
