package com.cbs.operations;

import com.cbs.tenancy.BankDatabaseProvisioner;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/** Refuse database owners and DDL-capable identities in the production runtime. */
@Component
@Profile("production")
public class RuntimeDatabaseGuard implements ApplicationRunner {
    private final DataSource control;
    private final BankDatabaseProvisioner.Settings banks;
    public RuntimeDatabaseGuard(DataSource control,BankDatabaseProvisioner.Settings banks){this.control=control;this.banks=banks;}
    @Override public void run(ApplicationArguments args){
        try {
            try(var connection=control.getConnection()){verify(connection);}
            for(var target:banks.databases().values()){
                var source=new DriverManagerDataSource(target.url(),target.username(),target.password());
                var properties=new java.util.Properties();
                properties.setProperty("connectTimeout","10");
                properties.setProperty("socketTimeout","15");
                source.setConnectionProperties(properties);
                try(var connection=source.getConnection()){verify(connection);}
            }
        }catch(Exception failure){throw new IllegalStateException("Production database privilege verification failed; use restricted runtime roles and completed migrations");}
    }
    public static void verify(Connection connection)throws Exception {
        try(var statement=connection.createStatement()){
            statement.setQueryTimeout(10);
            try(var row=statement.executeQuery("""
                SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND
                  (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
                OR EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role(current_user,datdba,'MEMBER'))
                OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname<>'information_schema'
                  AND (pg_has_role(current_user,nspowner,'MEMBER') OR has_schema_privilege(current_user,oid,'CREATE')))
                OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema' AND pg_has_role(current_user,c.relowner,'MEMBER'))
                """)){
                row.next();if(row.getBoolean(1))throw new IllegalStateException("Runtime role must not own or create database objects");
            }
            try(var row=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='platform') OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='bank')")){
                row.next();if(!row.getBoolean(1))throw new IllegalStateException("Run migrations before starting runtime");
            }
        }
    }
}
