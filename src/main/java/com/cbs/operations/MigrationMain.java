package com.cbs.operations;

import com.cbs.tenancy.BankDatabaseProvisioner;
import com.cbs.tenancy.TenantException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline, separately credentialled migration entry point. Never starts HTTP or posting workers.
 *
 * <p>P-08: the control database is migrated first. Bank databases are then upgraded one at a time (rolling), in
 * reference order. A bank that fails does not stop the others unless {@code --stop-on-failure} is given. Each
 * bank migration runs in its own transaction, so a failure leaves that bank at its previous version, and the
 * runtime refuses to serve it until it is upgraded ({@code BANK_SCHEMA_UPGRADE_REQUIRED}). {@code --only}
 * upgrades a canary subset. {@code --report} writes a JSON report of every bank's before and after version.
 *
 * <p>P-03: the plan may name a {@code firstAdministrator} (issuer, subject, display name). Production forbids the
 * runtime bootstrap subject, so this audited offline step registers the first CBS staff record for an identity
 * provider. It applies only while no staff exist for that issuer, is repeatable for the same subject, and is
 * refused for any other subject; later staff are registered through the portal.
 *
 * <pre>java -cp cbs.jar com.cbs.operations.MigrationMain plan.json [--local-test] [--only ref1,ref2] [--stop-on-failure] [--report report.json]</pre>
 * Exit codes: 0 every selected bank is current; 2 at least one bank failed or was skipped; 1 the job could not run.
 */
public final class MigrationMain {
    public record Database(String url, String username, String passwordFile, UUID tenantId) {
        @Override public String toString(){return "MigrationDatabase[redacted]";}
    }
    public record FirstAdministrator(String issuer, String subject, String displayName, String email) {}
    public record Plan(Database control, Map<String,Database> banks, FirstAdministrator firstAdministrator) {
        public Plan(Database control, Map<String,Database> banks){this(control,banks,null);}
    }
    /** An operator error whose message is safe to print: it never contains credentials. */
    public static final class Refused extends IllegalStateException { Refused(String message){super(message);} }
    public record Options(boolean local, Set<String> only, boolean stopOnFailure, Path report) {}
    public enum Status { UPGRADED, CURRENT, FAILED, SKIPPED }
    public record BankResult(String reference, UUID tenantId, String before, String after, Status status, String code) {}
    public record Report(String expectedBankVersion, List<BankResult> banks, Instant completedAt, String firstAdministrator) {
        public boolean complete(){return banks.stream().allMatch(b->b.status()==Status.UPGRADED||b.status()==Status.CURRENT);}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private MigrationMain() {}

    public static void main(String[] args) {
        try {
            var options=options(args);
            var plan=JSON.readValue(Files.readString(Path.of(args[0])),Plan.class);
            var report=run(plan,options);
            if(report.firstAdministrator()!=null)System.out.println("First administrator: "+report.firstAdministrator());
            for(var b:report.banks())System.out.println("Bank "+b.reference()+": "+b.status()+" "+(b.before()==null?"-":b.before())+" -> "+(b.after()==null?"-":b.after())+(b.code()==null?"":" ("+b.code()+")"));
            if(!report.complete()){System.err.println("Some banks are not at version "+report.expectedBankVersion()+"; the runtime refuses them until they are upgraded.");System.exit(2);}
            System.out.println("Migration job completed; reapply runtime privileges and verify before starting CBS.");
        }catch(Refused refused){System.err.println("Migration job refused: "+refused.getMessage());System.exit(1);
        }catch(Exception failure){System.err.println("Migration job failed. Runtime remains stopped; inspect the restricted migration log. No credentials are printed by this entry point.");System.exit(1);}
    }

    static Options options(String[] args){
        if(args.length<1)throw new IllegalArgumentException();
        boolean local=false,stop=false;Set<String> only=Set.of();Path report=null;
        for(int i=1;i<args.length;i++){
            switch(args[i]){
                case "--local-test"->local=true;
                case "--stop-on-failure"->stop=true;
                case "--only"->{if(++i>=args.length)throw new IllegalArgumentException();only=new LinkedHashSet<>(Arrays.asList(args[i].split(",")));if(only.stream().anyMatch(String::isBlank))throw new IllegalArgumentException();}
                case "--report"->{if(++i>=args.length)throw new IllegalArgumentException();report=Path.of(args[i]);}
                default->throw new IllegalArgumentException();
            }
        }
        return new Options(local,Set.copyOf(only),stop,report);
    }

    /** Migrates the control database, then each selected bank in turn; never prints or reports credentials. */
    public static Report run(Plan plan,Options options)throws Exception{
        validate(plan.control(),options.local(),false);
        for(var db:plan.banks().values())validate(db,options.local(),true);
        if(!plan.banks().keySet().containsAll(options.only()))throw new IllegalArgumentException("Unknown bank reference");
        if(plan.firstAdministrator()!=null)validate(plan.firstAdministrator(),options.local());
        var control=plan.control();
        Flyway.configure().dataSource(control.url(),control.username(),password(control))
            .locations("classpath:db/migration").cleanDisabled(true).validateOnMigrate(true).load().migrate();
        String first=plan.firstAdministrator()==null?null:registerFirstAdministrator(control,plan.firstAdministrator());
        var results=new ArrayList<BankResult>();boolean stopped=false;
        for(var entry:new TreeMap<>(plan.banks()).entrySet()){
            String reference=entry.getKey();var db=entry.getValue();
            if(!options.only().isEmpty()&&!options.only().contains(reference))continue;
            String before=version(db);
            if(stopped){results.add(new BankResult(reference,db.tenantId(),before,before,Status.SKIPPED,null));continue;}
            try{
                // provision is repeatable for the same reserved bank UUID; another owner is always refused.
                String after=new BankDatabaseProvisioner(new BankDatabaseProvisioner.Settings(Map.of(reference,
                    new BankDatabaseProvisioner.Target(db.url(),db.username(),password(db))))).provision(db.tenantId(),reference);
                results.add(new BankResult(reference,db.tenantId(),before,after,Objects.equals(before,after)?Status.CURRENT:Status.UPGRADED,null));
            }catch(TenantException e){
                results.add(new BankResult(reference,db.tenantId(),before,version(db),Status.FAILED,e.code()));
                stopped=options.stopOnFailure();
            }
        }
        var report=new Report(BankDatabaseProvisioner.expectedBankVersion(),List.copyOf(results),Instant.now(),first);
        if(options.report()!=null)Files.writeString(options.report(),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    /** The bank database's current schema version, or null before its first migration. */
    static String version(Database db){
        try(var c=DriverManager.getConnection(db.url(),db.username(),password(db));var s=c.createStatement();
            var r=s.executeQuery("SELECT version FROM bank.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){
            return r.next()?r.getString(1):null;
        }catch(Exception e){return null;}
    }
    static void validate(Database db,boolean local,boolean bank){
        String pattern=local?"jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+(?:\\?sslmode=disable)?":"jdbc:postgresql://[a-zA-Z0-9.-]+(?::[0-9]+)?/[a-zA-Z0-9_]+\\?sslmode=verify-full(?:&channelBinding=require)?";
        if(db==null||db.url()==null||!db.url().matches(pattern)||db.username()==null||db.username().isBlank()||db.passwordFile()==null||bank&&db.tenantId()==null)throw new IllegalArgumentException("Invalid migration configuration");
    }
    static void validate(FirstAdministrator a,boolean local){
        String issuer=a.issuer()==null?"":a.issuer();
        boolean https=issuer.matches("https://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~-]+)*/?");
        boolean loopback=local&&issuer.matches("http://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}(?:/[A-Za-z0-9._~-]+)*/?");
        if(java.util.stream.Stream.of(issuer,a.subject(),a.displayName(),a.email()).anyMatch(v->v!=null&&(v.contains("REPLACE_")||v.contains("IDENTITY_DOMAIN"))))
            throw new Refused("firstAdministrator still contains placeholders");
        if(!(https||loopback)||issuer.length()>512)throw new Refused("firstAdministrator.issuer must be the exact HTTPS issuer CBS trusts");
        if(!printable(a.subject(),200))throw new Refused("firstAdministrator.subject must be 1-200 printable characters");
        if(!printable(a.displayName(),200))throw new Refused("firstAdministrator.displayName must be 1-200 printable characters");
        if(a.email()!=null&&!(a.email().length()<=254&&a.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))throw new Refused("firstAdministrator.email is not valid");
    }
    private static boolean printable(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&value.chars().noneMatch(Character::isISOControl);}

    /** Registers the first staff record for an issuer, with an identity audit entry; returns REGISTERED or CURRENT. */
    static String registerFirstAdministrator(Database control,FirstAdministrator a)throws Exception{
        try(var c=DriverManager.getConnection(control.url(),control.username(),password(control))){
            c.setAutoCommit(false);
            try{
                try(var lock=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('cbs-first-administrator'),hashtext(?))")){lock.setString(1,a.issuer());lock.execute();}
                var existing=new ArrayList<String>();
                try(var q=c.prepareStatement("SELECT subject FROM platform.staff_identity WHERE issuer=?")){q.setString(1,a.issuer());try(var r=q.executeQuery()){while(r.next())existing.add(r.getString(1));}}
                if(existing.contains(a.subject())){c.rollback();return "CURRENT";}
                if(!existing.isEmpty())throw new Refused("staff are already registered for this issuer; register further administrators through the portal");
                var id=UUID.randomUUID();
                try(var insert=c.prepareStatement("INSERT INTO platform.staff_identity(id,issuer,subject,display_name,email) VALUES (?,?,?,?,?)")){
                    insert.setObject(1,id);insert.setString(2,a.issuer());insert.setString(3,a.subject());insert.setString(4,a.displayName());insert.setString(5,a.email());insert.executeUpdate();}
                try(var audit=c.prepareStatement("INSERT INTO platform.identity_audit(id,actor_issuer,actor_subject,staff_id,action,reason,details,correlation_id) VALUES (?,?,?,?,?,?,?::jsonb,?)")){
                    audit.setObject(1,UUID.randomUUID());audit.setString(2,a.issuer());audit.setString(3,"system:migration-job");audit.setObject(4,id);
                    audit.setString(5,"FIRST_ADMIN_REGISTERED");audit.setString(6,"Offline migration job: first administrator for this identity provider");
                    audit.setString(7,JSON.writeValueAsString(Map.of("subject",a.subject(),"displayName",a.displayName())));audit.setObject(8,UUID.randomUUID());audit.executeUpdate();}
                c.commit();
                return "REGISTERED";
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    private static String password(Database db)throws Exception{return Files.readString(Path.of(db.passwordFile())).stripTrailing();}
}
