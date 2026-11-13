package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 *
 * <p>Round 10 adds approved templates, scheduled notices, one-time-code verification of every new
 * destination, opt-out suppression and provider delivery receipts.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class NotificationService {
    static final int MAX_ATTEMPTS=5, CODE_ATTEMPTS=5;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final String CODE_SENT="[verification code sent]", CODE_HIDDEN="[verification code]";
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final List<NotificationSender> senders;private final TransactionTemplate tx;
    public NotificationService(JdbcTemplate jdbc,TenantRepository tenants,List<NotificationSender> senders,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.senders=senders;this.tx=new TransactionTemplate(manager);}

    // ---- Preferences (approved changes only) ----
    public void validate(Tenant bank,Preference p){
        if(bank.version()!=p.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=TenantModels.Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=? AND delivery='APPLIED')",Boolean.class,bank.id(),p.customerId()))throw error(404,"CUSTOMER_NOT_FOUND");
        if(p.channel()==Channel.NONE){if(p.destination()!=null&&!p.destination().isBlank())throw error(400,"NOTIFICATION_DESTINATION_INVALID");}
        else{
            if(!validDestination(p.channel(),p.destination()))throw error(400,"NOTIFICATION_DESTINATION_INVALID");
            if(p.events()==null||p.events().isEmpty())throw error(400,"NOTIFICATION_EVENTS_REQUIRED");
            if(p.events().contains(Event.VERIFICATION))throw error(400,"NOTIFICATION_EVENTS_INVALID");
        }
    }
    public Map<String,Object> values(Preference p){
        var m=new LinkedHashMap<String,Object>();m.put("customerId",p.customerId());m.put("channel",p.channel());m.put("destination",p.destination()==null?"":p.destination());
        m.put("language",p.language());m.put("events",new TreeSet<>(p.events()==null?Set.of():p.events()).toString());m.put("consentReference",p.consentReference());return m;
    }
    public Map<String,Object> before(UUID bankId,UUID customerId){var s=stored(bankId,customerId);if(s==null)return Map.of();var m=new LinkedHashMap<String,Object>();m.put("channel",s.channel());m.put("destination",s.destination()==null?"":s.destination());m.put("language",s.language());m.put("events",new TreeSet<>(s.events()).toString());m.put("verified",s.verified());return m;}
    public void apply(Actor actor,UUID bankId,Preference p,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,p);
        var events=p.channel()==Channel.NONE?new String[0]:p.events().stream().map(Enum::name).sorted().toArray(String[]::new);String destination=p.channel()==Channel.NONE?null:p.destination();
        // A destination stays verified only while it is unchanged; a new one is verified with a one-time code.
        var old=stored(bankId,p.customerId());boolean keep=old!=null&&old.channel()==p.channel()&&Objects.equals(old.destination(),destination)&&old.verifiedAt()!=null;
        var verifiedAt=keep?java.sql.Timestamp.from(old.verifiedAt()):null;
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO platform.notification_preference(tenant_id,customer_id,channel,destination,language,events,consent_reference,approval_id,verified_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,customer_id) DO UPDATE SET channel=excluded.channel,destination=excluded.destination,language=excluded.language,events=excluded.events,consent_reference=excluded.consent_reference,approval_id=excluded.approval_id,verified_at=excluded.verified_at,code_hash=NULL,code_expires_at=NULL,code_issued_at=NULL,code_attempts=0,updated_at=clock_timestamp()");
            s.setObject(1,bankId);s.setObject(2,p.customerId());s.setString(3,p.channel().name());s.setString(4,destination);s.setString(5,p.language());s.setArray(6,c.createArrayOf("varchar",events));s.setString(7,p.consentReference());s.setObject(8,approvalId);s.setTimestamp(9,verifiedAt);return s;});
        boolean code=p.channel()!=Channel.NONE&&!keep;if(code)issueCode(bank,p.customerId(),p.channel(),destination,p.language());
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"NOTIFICATION_PREFERENCE_CHANGED",bank.version()+1,bank.status(),bank.status(),p.reason(),null,null,Map.of("customerId",p.customerId(),"channel",p.channel().name(),"destination",String.valueOf(mask(p.channel(),destination)),"events",String.join(",",events),"verification",code?"CODE_SENT":"NOT_REQUIRED"));
    }
    public Stored preference(Actor actor,UUID bankId,UUID customerId){AccessService.admin(actor);tenants.get(bankId,false);return stored(bankId,customerId);}
    private Stored stored(UUID bankId,UUID customerId){
        var rows=jdbc.query("SELECT * FROM platform.notification_preference WHERE tenant_id=? AND customer_id=?",(r,n)->{var v=r.getTimestamp("verified_at");return new Stored(customerId,Channel.valueOf(r.getString("channel")),r.getString("destination"),r.getString("language"),events(r.getArray("events")),r.getString("consent_reference"),r.getObject("approval_id",UUID.class),r.getTimestamp("updated_at").toInstant(),v==null?null:v.toInstant());},bankId,customerId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static Set<Event> events(Array array)throws java.sql.SQLException{var set=EnumSet.noneOf(Event.class);for(Object o:(Object[])array.getArray())set.add(Event.valueOf(o.toString()));return set;}

    // ---- Destination verification ----
    static String hash(UUID bank,UUID customer,String code){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((bank+":"+customer+":"+code).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** Stores the hash of a new six-digit code and queues it to the destination; the code itself is never stored after sending. */
    private void issueCode(Tenant bank,UUID customer,Channel channel,String destination,String language){
        String code=String.format("%06d",RANDOM.nextInt(1_000_000));
        jdbc.update("UPDATE platform.notification_preference SET code_hash=?,code_expires_at=clock_timestamp()+interval '24 hours',code_issued_at=clock_timestamp(),code_attempts=0 WHERE tenant_id=? AND customer_id=?",hash(bank.id(),customer,code),bank.id(),customer);
        var text=render(bank.id(),Event.VERIFICATION,language,channel,Map.of("bank",bank.displayName(),"code",code));
        jdbc.update("INSERT INTO platform.notification(id,tenant_id,customer_id,account_id,event,source_id,channel,destination,language,subject,body) VALUES (?,?,?,NULL,'VERIFICATION',?,?,?,?,?,?)",
            UUID.randomUUID(),bank.id(),customer,UUID.randomUUID(),channel.name(),destination,language,text[0],text[1]);
    }
    public void verify(Actor actor,UUID bankId,UUID customer,String code){
        AccessService.admin(actor);
        String outcome=tx.execute(t->{
            var bank=tenants.get(bankId,false);
            var rows=jdbc.queryForList("SELECT channel,verified_at,code_hash,code_expires_at<clock_timestamp() AS expired,code_attempts FROM platform.notification_preference WHERE tenant_id=? AND customer_id=? FOR UPDATE",bankId,customer);
            if(rows.isEmpty())throw error(404,"NOTIFICATION_PREFERENCE_NOT_FOUND");var row=rows.getFirst();
            if("NONE".equals(row.get("channel"))||row.get("verified_at")!=null)return "NOTIFICATION_ALREADY_VERIFIED";
            if(row.get("code_hash")==null||Boolean.TRUE.equals(row.get("expired")))return "NOTIFICATION_CODE_EXPIRED";
            if(((Number)row.get("code_attempts")).intValue()>=CODE_ATTEMPTS)return "NOTIFICATION_CODE_LOCKED";
            boolean match=MessageDigest.isEqual(((String)row.get("code_hash")).getBytes(StandardCharsets.US_ASCII),hash(bankId,customer,code).getBytes(StandardCharsets.US_ASCII));
            if(!match){
                jdbc.update("UPDATE platform.notification_preference SET code_attempts=code_attempts+1 WHERE tenant_id=? AND customer_id=?",bankId,customer);
                tenants.audit(actor,bankId,"NOTIFICATION_CODE_REJECTED",bank.version(),bank.status(),bank.status(),"Wrong verification code",null,null,Map.of("customerId",customer));
                return "NOTIFICATION_CODE_INVALID";
            }
            jdbc.update("UPDATE platform.notification_preference SET verified_at=clock_timestamp(),code_hash=NULL,code_expires_at=NULL,code_attempts=0 WHERE tenant_id=? AND customer_id=?",bankId,customer);
            tenants.audit(actor,bankId,"NOTIFICATION_DESTINATION_VERIFIED",bank.version(),bank.status(),bank.status(),"Customer confirmed the verification code",null,null,Map.of("customerId",customer));
            return null;
        });
        if(outcome!=null)throw error(outcome.equals("NOTIFICATION_CODE_INVALID")?400:409,outcome);
    }
    public void resend(Actor actor,UUID bankId,UUID customer){
        AccessService.admin(actor);
        tx.executeWithoutResult(t->{
            var bank=tenants.get(bankId,false);
            var rows=jdbc.queryForList("SELECT channel,destination,language,verified_at,code_issued_at>clock_timestamp()-interval '60 seconds' AS recent FROM platform.notification_preference WHERE tenant_id=? AND customer_id=? FOR UPDATE",bankId,customer);
            if(rows.isEmpty())throw error(404,"NOTIFICATION_PREFERENCE_NOT_FOUND");var row=rows.getFirst();
            if("NONE".equals(row.get("channel"))||row.get("verified_at")!=null)throw error(409,"NOTIFICATION_ALREADY_VERIFIED");
            if(Boolean.TRUE.equals(row.get("recent")))throw error(409,"NOTIFICATION_CODE_RECENT");
            issueCode(bank,customer,Channel.valueOf((String)row.get("channel")),(String)row.get("destination"),(String)row.get("language"));
            tenants.audit(actor,bankId,"NOTIFICATION_CODE_RESENT",bank.version(),bank.status(),bank.status(),"Verification code sent again",null,null,Map.of("customerId",customer));
        });
    }

    // ---- Outbox ----
    /** Called inside the transaction that completes a posting. Idempotent per movement and account. */
    public void record(Movement m){
        record(new Notice(m.bankId(),m.accountId(),m.event(),m.sourceId(),Map.of("currency",m.currency(),"amount",m.amount(),"date",m.businessDate().toString(),"reference",m.reference()==null?"":m.reference())));
    }
    /** Queues an alert or notice for every holder of the account who opted in to the event on a verified destination. */
    public void record(Notice notice){
        // Primary customer, joint holders and guardians.
        var rows=jdbc.query("WITH holders AS (SELECT customer_id FROM platform.account_registry WHERE id=? UNION SELECT customer_id FROM platform.account_party WHERE account_id=? AND role IN ('JOINT_HOLDER','GUARDIAN')) SELECT p.customer_id,a.account_number,p.channel,p.destination,p.language,p.events,t.display_name FROM holders h JOIN platform.notification_preference p ON p.customer_id=h.customer_id JOIN platform.account_registry a ON a.id=? AND p.tenant_id=a.tenant_id JOIN platform.tenant t ON t.id=a.tenant_id WHERE a.tenant_id=? AND p.channel<>'NONE' AND p.verified_at IS NOT NULL ORDER BY p.customer_id",
            (r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),Channel.valueOf(r.getString(3)),r.getString(4),r.getString(5),events(r.getArray(6)),r.getString(7)},notice.accountId(),notice.accountId(),notice.accountId(),notice.bankId());
        for(var row:rows){
            @SuppressWarnings("unchecked") var wanted=(Set<Event>)row[5];if(!wanted.contains(notice.event()))continue;
            String language=(String)row[4];Channel channel=(Channel)row[2];
            var values=new HashMap<>(notice.values());values.put("bank",(String)row[6]);values.put("last4",NotificationTemplates.last4((String)row[1]));
            var text=render(notice.bankId(),notice.event(),language,channel,values);
            UUID id=UUID.nameUUIDFromBytes((notice.event()+":"+notice.sourceId()+":"+notice.accountId()+":"+row[0]).getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO platform.notification(id,tenant_id,customer_id,account_id,event,source_id,channel,destination,language,subject,body) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                id,notice.bankId(),row[0],notice.accountId(),notice.event().name(),notice.sourceId(),channel.name(),row[3],language,text[0],text[1]);
        }
    }
    /** Subject (email only) and body, from the bank's approved template or the built-in wording. */
    private String[] render(UUID bank,Event event,String language,Channel channel,Map<String,String> values){
        var rows=jdbc.queryForList("SELECT subject,body FROM platform.notification_template WHERE tenant_id=? AND event=? AND language=? AND channel=?",bank,event.name(),language,channel.name());
        String subject=rows.isEmpty()?NotificationTemplates.defaultSubject(language,event):(String)rows.getFirst().get("subject");
        String body=rows.isEmpty()?NotificationTemplates.defaultBody(language,event):(String)rows.getFirst().get("body");
        return new String[]{channel==Channel.EMAIL?NotificationTemplates.fill(subject,values):null,NotificationTemplates.fill(body,values)};
    }
    /** Delivers pending alerts in small batches; each claim and result is its own transaction. */
    public int dispatch(){
        if(senders.isEmpty())return 0;int sent=0;
        for(int i=0;i<50;i++){
            Boolean done=tx.execute(t->{
                var rows=jdbc.query("SELECT * FROM platform.notification WHERE status='PENDING' ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 1 FOR UPDATE SKIP LOCKED",(r,n)->map(r,true));
                if(rows.isEmpty())return null;var n=rows.getFirst();
                UUID bank=jdbc.queryForObject("SELECT tenant_id FROM platform.notification WHERE id=?",UUID.class,n.id());
                // A destination that opted out is never contacted again, whatever is queued.
                if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.notification_opt_out WHERE tenant_id=? AND channel=? AND destination=?)",Boolean.class,bank,n.channel().name(),n.destination())){
                    jdbc.update("UPDATE platform.notification SET status='SUPPRESSED',failure_code='NOTIFICATION_OPTED_OUT',last_attempt_at=clock_timestamp() WHERE id=?",n.id());return false;
                }
                var sender=senders.stream().filter(s->s.supports(n.channel())&&s.serves(bank)).findFirst();
                Result result;try{result=sender.map(s->s.send(n)).orElse(Result.failed("NOTIFICATION_CHANNEL_UNAVAILABLE"));}catch(RuntimeException e){result=Result.failed("NOTIFICATION_PROVIDER_ERROR");}
                String provider=sender.map(NotificationSender::name).orElse(null);
                if(result.delivered())jdbc.update("UPDATE platform.notification SET status='SENT',attempts=attempts+1,last_attempt_at=clock_timestamp(),sent_at=clock_timestamp(),failure_code=NULL,provider=?,provider_reference=?,body=CASE WHEN event='VERIFICATION' THEN ? ELSE body END WHERE id=?",provider,result.providerReference(),CODE_SENT,n.id());
                else jdbc.update("UPDATE platform.notification SET status=CASE WHEN attempts+1>=? THEN 'FAILED' ELSE 'PENDING' END,attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,provider=? WHERE id=?",MAX_ATTEMPTS,result.failureCode(),provider,n.id());
                return result.delivered();
            });
            if(done==null)break;if(done)sent++;
        }
        return sent;
    }
    public NotificationModels.Page list(Actor actor,UUID bankId,String status,UUID customerId,int limit,int offset){return list(actor,bankId,status,customerId,null,limit,offset);}
    public NotificationModels.Page list(Actor actor,UUID bankId,String status,UUID customerId,Event event,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);tenants.get(bankId,false);
        if(status!=null&&!status.isBlank())try{NotificationModels.Status.valueOf(status);}catch(IllegalArgumentException e){throw error(400,"NOTIFICATION_STATUS_INVALID");}
        String filter=" WHERE tenant_id=? AND (?::varchar IS NULL OR status=?) AND (?::uuid IS NULL OR customer_id=?) AND (?::varchar IS NULL OR event=?)";String s=status==null||status.isBlank()?null:status;String ev=event==null?null:event.name();
        var items=jdbc.query("SELECT * FROM platform.notification"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->map(r,false),bankId,s,s,customerId,customerId,ev,ev,limit,offset);
        long total=jdbc.queryForObject("SELECT count(*) FROM platform.notification"+filter,Long.class,bankId,s,s,customerId,customerId,ev,ev);
        return new NotificationModels.Page(items,total,limit,offset);
    }
    public boolean providerConfigured(){return !senders.isEmpty();}
    public void retry(Actor actor,UUID bankId,UUID id){
        AccessService.admin(actor);var bank=tenants.get(bankId,false);
        if(jdbc.update("UPDATE platform.notification SET status='PENDING',attempts=0,failure_code=NULL WHERE tenant_id=? AND id=? AND status='FAILED' AND event<>'VERIFICATION'",bankId,id)!=1)throw error(409,"NOTIFICATION_NOT_RETRYABLE");
        tenants.audit(actor,bankId,"NOTIFICATION_RETRY_REQUESTED",bank.version(),bank.status(),bank.status(),"Failed alert queued again",null,null,Map.of("notificationId",id));
    }
    private static Notification map(java.sql.ResultSet r,boolean full)throws java.sql.SQLException{
        var channel=Channel.valueOf(r.getString("channel"));var sent=r.getTimestamp("sent_at");var receipt=r.getTimestamp("receipt_at");var event=Event.valueOf(r.getString("event"));
        // Lists never show a verification code, even before it is sent.
        String body=!full&&event==Event.VERIFICATION?CODE_HIDDEN:r.getString("body");
        return new Notification(r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getObject("account_id",UUID.class),event,r.getObject("source_id",UUID.class),channel,
            full?r.getString("destination"):mask(channel,r.getString("destination")),r.getString("language"),r.getString("subject"),body,NotificationModels.Status.valueOf(r.getString("status")),r.getInt("attempts"),r.getString("failure_code"),r.getString("provider"),r.getString("provider_reference"),r.getTimestamp("created_at").toInstant(),sent==null?null:sent.toInstant(),
            r.getString("receipt_status"),receipt==null?null:receipt.toInstant());
    }

    // ---- Approved templates (maker-checker NOTIFICATION_TEMPLATE) ----
    public void validate(Tenant bank,Template t){
        if(bank.version()!=t.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=TenantModels.Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
        if(t.channel()==Channel.NONE)throw error(400,"NOTIFICATION_TEMPLATE_CHANNEL_INVALID");
        boolean email=t.channel()==Channel.EMAIL,hasSubject=t.subject()!=null&&!t.subject().isBlank();
        if(email!=hasSubject)throw error(400,"NOTIFICATION_TEMPLATE_SUBJECT_INVALID");
        var problems=new ArrayList<>(NotificationTemplates.problems(t.event(),t.body(),true));if(hasSubject)problems.addAll(NotificationTemplates.problems(t.event(),t.subject(),false));
        if(!problems.isEmpty())throw error(400,"NOTIFICATION_TEMPLATE_PLACEHOLDER_INVALID");
        if(!email&&NotificationTemplates.fill(t.body(),longest()).length()>NotificationTemplates.SMS_LIMIT)throw error(400,"NOTIFICATION_TEMPLATE_TOO_LONG");
    }
    /** Values at their longest, so an approved SMS template can never exceed two segments. */
    private static Map<String,String> longest(){var m=new HashMap<String,String>();for(var k:List.of("bank","currency","amount","last4","date","reference","days","code"))m.put(k,"x".repeat(k.equals("last4")?4:k.equals("code")?6:k.equals("date")?10:k.equals("currency")?3:k.equals("days")?4:k.equals("bank")?40:18));return m;}
    public Map<String,Object> before(UUID bankId,Template t){
        var rows=jdbc.queryForList("SELECT subject,body FROM platform.notification_template WHERE tenant_id=? AND event=? AND language=? AND channel=?",bankId,t.event().name(),t.language(),t.channel().name());
        var m=new LinkedHashMap<String,Object>();
        if(rows.isEmpty()){m.put("source","built-in");m.put("body",NotificationTemplates.defaultBody(t.language(),t.event()));}
        else{m.put("source","approved");if(rows.getFirst().get("subject")!=null)m.put("subject",rows.getFirst().get("subject"));m.put("body",rows.getFirst().get("body"));}
        return m;
    }
    public Map<String,Object> values(Template t){
        var m=new LinkedHashMap<String,Object>();m.put("event",t.event().name());m.put("language",t.language());m.put("channel",t.channel().name());
        if(t.subject()!=null&&!t.subject().isBlank())m.put("subject",t.subject());m.put("body",t.body());m.put("example",NotificationTemplates.fill(t.body(),NotificationTemplates.sampleValues()));return m;
    }
    public void apply(Actor actor,UUID bankId,Template t,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,t);String subject=t.channel()==Channel.EMAIL?t.subject().strip():null;
        jdbc.update("INSERT INTO platform.notification_template(tenant_id,event,language,channel,subject,body,approval_id) VALUES (?,?,?,?,?,?,?) ON CONFLICT(tenant_id,event,language,channel) DO UPDATE SET subject=excluded.subject,body=excluded.body,approval_id=excluded.approval_id,updated_at=clock_timestamp()",
            bankId,t.event().name(),t.language(),t.channel().name(),subject,t.body().strip(),approvalId);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"NOTIFICATION_TEMPLATE_APPROVED",bank.version()+1,bank.status(),bank.status(),t.reason(),null,null,Map.of("event",t.event().name(),"language",t.language(),"channel",t.channel().name(),"requestId",approvalId));
    }
    /** Every event, language and channel with the wording in use: the bank's approved template or the built-in text. */
    public List<TemplateView> templates(Actor actor,UUID bankId){
        AccessService.admin(actor);tenants.get(bankId,false);
        var approved=new HashMap<String,TemplateView>();
        jdbc.query("SELECT * FROM platform.notification_template WHERE tenant_id=?",r->{approved.put(r.getString("event")+":"+r.getString("language")+":"+r.getString("channel"),
            new TemplateView(Event.valueOf(r.getString("event")),r.getString("language"),Channel.valueOf(r.getString("channel")),r.getString("subject"),r.getString("body"),true,r.getObject("approval_id",UUID.class),r.getTimestamp("updated_at").toInstant()));},bankId);
        var out=new ArrayList<TemplateView>();
        for(var event:Event.values())for(var language:List.of("en","fr","sw"))for(var channel:List.of(Channel.SMS,Channel.EMAIL))
            out.add(approved.getOrDefault(event+":"+language+":"+channel,new TemplateView(event,language,channel,channel==Channel.EMAIL?NotificationTemplates.defaultSubject(language,event):null,NotificationTemplates.defaultBody(language,event),false,null,null)));
        return out;
    }

    // ---- Opt-outs ----
    public void optOut(Actor actor,UUID bankId,OptOut o){
        AccessService.admin(actor);if(!validDestination(o.channel(),o.destination()))throw error(400,"NOTIFICATION_DESTINATION_INVALID");
        tx.executeWithoutResult(t->{var bank=tenants.get(bankId,false);recordOptOut(bank,o.channel(),o.destination(),"STAFF",o.reference(),actor.subject());
            tenants.audit(actor,bankId,"NOTIFICATION_OPT_OUT_RECORDED",bank.version(),bank.status(),bank.status(),o.reference(),null,null,Map.of("channel",o.channel().name(),"destination",mask(o.channel(),o.destination())));});
    }
    private void recordOptOut(Tenant bank,Channel channel,String destination,String source,String reference,String by){
        jdbc.update("INSERT INTO platform.notification_opt_out(tenant_id,channel,destination,source,reference,recorded_by) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",bank.id(),channel.name(),destination,source,reference,by);
        jdbc.update("UPDATE platform.notification SET status='SUPPRESSED',failure_code='NOTIFICATION_OPTED_OUT' WHERE tenant_id=? AND channel=? AND destination=? AND status='PENDING'",bank.id(),channel.name(),destination);
    }
    /** Contact resumes only on the customer's renewed consent, recorded as the reference. */
    public void removeOptOut(Actor actor,UUID bankId,OptOut o){
        AccessService.admin(actor);
        tx.executeWithoutResult(t->{var bank=tenants.get(bankId,false);
            if(jdbc.update("DELETE FROM platform.notification_opt_out WHERE tenant_id=? AND channel=? AND destination=?",bankId,o.channel().name(),o.destination())!=1)throw error(404,"NOTIFICATION_OPT_OUT_NOT_FOUND");
            tenants.audit(actor,bankId,"NOTIFICATION_OPT_OUT_REMOVED",bank.version(),bank.status(),bank.status(),o.reference(),null,null,Map.of("channel",o.channel().name(),"destination",mask(o.channel(),o.destination())));});
    }
    public List<OptOutView> optOuts(Actor actor,UUID bankId){
        AccessService.admin(actor);tenants.get(bankId,false);
        return jdbc.query("SELECT * FROM platform.notification_opt_out WHERE tenant_id=? ORDER BY recorded_at DESC LIMIT 200",(r,n)->{var ch=Channel.valueOf(r.getString("channel"));
            return new OptOutView(ch,mask(ch,r.getString("destination")),r.getString("source"),r.getString("reference"),r.getString("recorded_by"),r.getTimestamp("recorded_at").toInstant());},bankId);
    }

    // ---- Provider receipts (signature checked by NotificationReceiptController) ----
    public String receipt(String provider,Receipt r){
        return tx.execute(t->{
            UUID notification=null;
            if(r.kind()!=ReceiptKind.OPTED_OUT){
                if(r.providerReference()==null||r.providerReference().isBlank())throw error(400,"NOTIFICATION_RECEIPT_INVALID");
                var ids=jdbc.queryForList("SELECT id FROM platform.notification WHERE provider=? AND provider_reference=?",UUID.class,provider,r.providerReference());
                notification=ids.isEmpty()?null:ids.getFirst();
            }else if(r.bankId()==null||r.channel()==null||!validDestination(r.channel(),r.destination()))throw error(400,"NOTIFICATION_RECEIPT_INVALID");
            if(jdbc.update("INSERT INTO platform.notification_receipt(provider,receipt_id,kind,provider_reference,notification_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",provider,r.receiptId(),r.kind().name(),r.providerReference(),notification)==0)return "DUPLICATE";
            if(r.kind()==ReceiptKind.OPTED_OUT){
                var bank=tenants.get(r.bankId(),false);recordOptOut(bank,r.channel(),r.destination(),"PROVIDER","RECEIPT/"+r.receiptId(),provider);
                tenants.audit(new Actor("provider:"+provider,provider,false,UUID.randomUUID()),bank.id(),"NOTIFICATION_OPT_OUT_RECORDED",bank.version(),bank.status(),bank.status(),"Opt-out reported by the provider",null,null,Map.of("channel",r.channel().name(),"destination",mask(r.channel(),r.destination())));
                return "OPTED_OUT";
            }
            if(notification==null)return "UNMATCHED";
            jdbc.update("UPDATE platform.notification SET receipt_status=?,receipt_at=clock_timestamp(),receipt_code=? WHERE id=?",r.kind().name(),r.code()==null||r.code().isBlank()?null:r.code(),notification);
            return "RECORDED";
        });
    }

    static TenantException error(int status,String code){return new TenantException(status,code,"Customer notification could not be completed");}
}
