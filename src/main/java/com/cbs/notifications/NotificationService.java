package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.sql.Array;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customer alerts through a transactional outbox: a posted movement records its alert in the
 * same control-database transaction that completes the posting, so an alert exists if and only
 * if the posting completed. Delivery happens later, at least once, through a configured sender.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class NotificationService {
    static final int MAX_ATTEMPTS=5;
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final List<NotificationSender> senders;private final TransactionTemplate tx;
    public NotificationService(JdbcTemplate jdbc,TenantRepository tenants,List<NotificationSender> senders,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.senders=senders;this.tx=new TransactionTemplate(manager);}

    // ---- Preferences (approved changes only) ----
    public void validate(Tenant bank,Preference p){
        if(bank.version()!=p.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=TenantModels.Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=? AND delivery='APPLIED')",Boolean.class,bank.id(),p.customerId()))throw error(404,"CUSTOMER_NOT_FOUND");
        if(p.channel()==Channel.NONE){if(p.destination()!=null&&!p.destination().isBlank())throw error(400,"NOTIFICATION_DESTINATION_INVALID");}
        else{
            if(p.destination()==null||!(p.channel()==Channel.SMS?PHONE:EMAIL).matcher(p.destination()).matches())throw error(400,"NOTIFICATION_DESTINATION_INVALID");
            if(p.events()==null||p.events().isEmpty())throw error(400,"NOTIFICATION_EVENTS_REQUIRED");
        }
    }
    public Map<String,Object> values(Preference p){
        var m=new LinkedHashMap<String,Object>();m.put("customerId",p.customerId());m.put("channel",p.channel());m.put("destination",p.destination()==null?"":p.destination());
        m.put("language",p.language());m.put("events",new TreeSet<>(p.events()==null?Set.of():p.events()).toString());m.put("consentReference",p.consentReference());return m;
    }
    public Map<String,Object> before(UUID bankId,UUID customerId){var s=stored(bankId,customerId);if(s==null)return Map.of();var m=new LinkedHashMap<String,Object>();m.put("channel",s.channel());m.put("destination",s.destination()==null?"":s.destination());m.put("language",s.language());m.put("events",new TreeSet<>(s.events()).toString());return m;}
    public void apply(Actor actor,UUID bankId,Preference p,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,p);
        var events=p.channel()==Channel.NONE?new String[0]:p.events().stream().map(Enum::name).sorted().toArray(String[]::new);String destination=p.channel()==Channel.NONE?null:p.destination();
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO platform.notification_preference(tenant_id,customer_id,channel,destination,language,events,consent_reference,approval_id) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,customer_id) DO UPDATE SET channel=excluded.channel,destination=excluded.destination,language=excluded.language,events=excluded.events,consent_reference=excluded.consent_reference,approval_id=excluded.approval_id,updated_at=clock_timestamp()");
            s.setObject(1,bankId);s.setObject(2,p.customerId());s.setString(3,p.channel().name());s.setString(4,destination);s.setString(5,p.language());s.setArray(6,c.createArrayOf("varchar",events));s.setString(7,p.consentReference());s.setObject(8,approvalId);return s;});
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"NOTIFICATION_PREFERENCE_CHANGED",bank.version()+1,bank.status(),bank.status(),p.reason(),null,null,Map.of("customerId",p.customerId(),"channel",p.channel().name(),"destination",String.valueOf(mask(p.channel(),destination)),"events",String.join(",",events)));
    }
    public Stored preference(Actor actor,UUID bankId,UUID customerId){AccessService.admin(actor);tenants.get(bankId,false);return stored(bankId,customerId);}
    private Stored stored(UUID bankId,UUID customerId){
        var rows=jdbc.query("SELECT * FROM platform.notification_preference WHERE tenant_id=? AND customer_id=?",(r,n)->new Stored(customerId,Channel.valueOf(r.getString("channel")),r.getString("destination"),r.getString("language"),events(r.getArray("events")),r.getString("consent_reference"),r.getObject("approval_id",UUID.class),r.getTimestamp("updated_at").toInstant()),bankId,customerId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static Set<Event> events(Array array)throws java.sql.SQLException{var set=EnumSet.noneOf(Event.class);for(Object o:(Object[])array.getArray())set.add(Event.valueOf(o.toString()));return set;}

    // ---- Outbox ----
    /** Called inside the transaction that completes a posting. Idempotent per movement and account. */
    public void record(Movement m){
        // Every holder with a preference is alerted: primary customer, joint holders and guardians.
        var rows=jdbc.query("WITH holders AS (SELECT customer_id FROM platform.account_registry WHERE id=? UNION SELECT customer_id FROM platform.account_party WHERE account_id=? AND role IN ('JOINT_HOLDER','GUARDIAN')) SELECT p.customer_id,a.account_number,p.channel,p.destination,p.language,p.events,t.display_name FROM holders h JOIN platform.notification_preference p ON p.customer_id=h.customer_id JOIN platform.account_registry a ON a.id=? AND p.tenant_id=a.tenant_id JOIN platform.tenant t ON t.id=a.tenant_id WHERE a.tenant_id=? AND p.channel<>'NONE' ORDER BY p.customer_id",
            (r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),Channel.valueOf(r.getString(3)),r.getString(4),r.getString(5),events(r.getArray(6)),r.getString(7)},m.accountId(),m.accountId(),m.accountId(),m.bankId());
        for(var row:rows)recordFor(m,row);
    }
    private void recordFor(Movement m,Object[] row){
        @SuppressWarnings("unchecked") var wanted=(Set<Event>)row[5];if(!wanted.contains(m.event()))return;
        String language=(String)row[4];var values=Map.of("bank",(String)row[6],"currency",m.currency(),"amount",m.amount(),"last4",NotificationTemplates.last4((String)row[1]),"date",m.businessDate().toString(),"reference",m.reference()==null?"":m.reference());
        Channel channel=(Channel)row[2];UUID id=UUID.nameUUIDFromBytes((m.event()+":"+m.sourceId()+":"+m.accountId()+":"+row[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO platform.notification(id,tenant_id,customer_id,account_id,event,source_id,channel,destination,language,subject,body) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            id,m.bankId(),row[0],m.accountId(),m.event().name(),m.sourceId(),channel.name(),row[3],language,channel==Channel.EMAIL?NotificationTemplates.subject(language,values):null,NotificationTemplates.body(language,m.event(),values));
    }
    /** Delivers pending alerts in small batches; each claim and result is its own transaction. */
    public int dispatch(){
        if(senders.isEmpty())return 0;int sent=0;
        for(int i=0;i<50;i++){
            Boolean done=tx.execute(t->{
                var rows=jdbc.query("SELECT * FROM platform.notification WHERE status='PENDING' ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 1 FOR UPDATE SKIP LOCKED",(r,n)->map(r,true));
                if(rows.isEmpty())return null;var n=rows.getFirst();
                var sender=senders.stream().filter(s->s.supports(n.channel())).findFirst();
                Result result;try{result=sender.map(s->s.send(n)).orElse(Result.failed("NOTIFICATION_CHANNEL_UNAVAILABLE"));}catch(RuntimeException e){result=Result.failed("NOTIFICATION_PROVIDER_ERROR");}
                String provider=sender.map(NotificationSender::name).orElse(null);
                if(result.delivered())jdbc.update("UPDATE platform.notification SET status='SENT',attempts=attempts+1,last_attempt_at=clock_timestamp(),sent_at=clock_timestamp(),failure_code=NULL,provider=?,provider_reference=? WHERE id=?",provider,result.providerReference(),n.id());
                else jdbc.update("UPDATE platform.notification SET status=CASE WHEN attempts+1>=? THEN 'FAILED' ELSE 'PENDING' END,attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,provider=? WHERE id=?",MAX_ATTEMPTS,result.failureCode(),provider,n.id());
                return result.delivered();
            });
            if(done==null)break;if(done)sent++;
        }
        return sent;
    }
    public NotificationModels.Page list(Actor actor,UUID bankId,String status,UUID customerId,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);tenants.get(bankId,false);
        if(status!=null&&!status.isBlank())try{NotificationModels.Status.valueOf(status);}catch(IllegalArgumentException e){throw error(400,"NOTIFICATION_STATUS_INVALID");}
        String filter=" WHERE tenant_id=? AND (?::varchar IS NULL OR status=?) AND (?::uuid IS NULL OR customer_id=?)";String s=status==null||status.isBlank()?null:status;
        var items=jdbc.query("SELECT * FROM platform.notification"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->map(r,false),bankId,s,s,customerId,customerId,limit,offset);
        long total=jdbc.queryForObject("SELECT count(*) FROM platform.notification"+filter,Long.class,bankId,s,s,customerId,customerId);
        return new NotificationModels.Page(items,total,limit,offset);
    }
    public boolean providerConfigured(){return !senders.isEmpty();}
    public void retry(Actor actor,UUID bankId,UUID id){
        AccessService.admin(actor);var bank=tenants.get(bankId,false);
        if(jdbc.update("UPDATE platform.notification SET status='PENDING',attempts=0,failure_code=NULL WHERE tenant_id=? AND id=? AND status='FAILED'",bankId,id)!=1)throw error(409,"NOTIFICATION_NOT_RETRYABLE");
        tenants.audit(actor,bankId,"NOTIFICATION_RETRY_REQUESTED",bank.version(),bank.status(),bank.status(),"Failed alert queued again",null,null,Map.of("notificationId",id));
    }
    private static Notification map(java.sql.ResultSet r,boolean full)throws java.sql.SQLException{
        var channel=Channel.valueOf(r.getString("channel"));var sent=r.getTimestamp("sent_at");
        return new Notification(r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getObject("account_id",UUID.class),Event.valueOf(r.getString("event")),r.getObject("source_id",UUID.class),channel,
            full?r.getString("destination"):mask(channel,r.getString("destination")),r.getString("language"),r.getString("subject"),r.getString("body"),NotificationModels.Status.valueOf(r.getString("status")),r.getInt("attempts"),r.getString("failure_code"),r.getString("provider"),r.getString("provider_reference"),r.getTimestamp("created_at").toInstant(),sent==null?null:sent.toInstant());
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Customer notification could not be completed");}
}
