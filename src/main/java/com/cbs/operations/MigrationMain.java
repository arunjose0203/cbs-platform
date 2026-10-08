package com.cbs.operations;

import com.cbs.tenancy.BankDatabaseProvisioner;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.json.JsonMapper;

/** Offline, separately credentialled migration entry point. Never starts HTTP or posting workers. */
public final class MigrationMain {
    public record Database(String url, String username, String passwordFile, UUID tenantId) {
        @Override public String toString(){return "MigrationDatabase[redacted]";}
    }
    public record Plan(Database control, Map<String,Database> banks) {}
    private MigrationMain() {}
    public static void main(String[] args) {
        try {
            if(args.length<1||args.length>2)throw new IllegalArgumentException();
            boolean local=args.length==2&&args[1].equals("--local-test");
            if(args.length==2&&!local)throw new IllegalArgumentException();
            var plan=JsonMapper.builder().build().readValue(Files.readString(Path.of(args[0])),Plan.class);
            validate(plan.control(),local,false);
            for(var db:plan.banks().values())validate(db,local,true);
            var control=plan.control();
            var flyway=Flyway.configure().dataSource(control.url(),control.username(),password(control))
                .locations("classpath:db/migration").cleanDisabled(true).validateOnMigrate(true).load();
            flyway.migrate();
            for(var entry:plan.banks().entrySet()){
                var db=entry.getValue();
                // provision is repeatable for the same reserved bank UUID; another owner is always refused.
                new BankDatabaseProvisioner(new BankDatabaseProvisioner.Settings(Map.of(entry.getKey(),
                    new BankDatabaseProvisioner.Target(db.url(),db.username(),password(db)))))
                    .provision(db.tenantId(),entry.getKey());
            }
            System.out.println("Migration job completed; reapply runtime privileges and verify before starting CBS.");
        }catch(Exception failure){System.err.println("Migration job failed. Runtime remains stopped; inspect the restricted migration log. No credentials are printed by this entry point.");System.exit(1);}
    }
    static void validate(Database db,boolean local,boolean bank){
        String pattern=local?"jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+(?:\\?sslmode=disable)?":"jdbc:postgresql://[a-zA-Z0-9.-]+(?::[0-9]+)?/[a-zA-Z0-9_]+\\?sslmode=verify-full(?:&channelBinding=require)?";
        if(db==null||db.url()==null||!db.url().matches(pattern)||db.username()==null||db.username().isBlank()||db.passwordFile()==null||bank&&db.tenantId()==null)throw new IllegalArgumentException("Invalid migration configuration");
    }
    private static String password(Database db)throws Exception{return Files.readString(Path.of(db.passwordFile())).stripTrailing();}
}
