package com.cbs.accounting;

import static com.cbs.accounting.LedgerModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** No caller-supplied connection strings; ownership is checked on every connection. */
@Component
public class BankLedgerStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final BankDatabaseProvisioner databases;
    public BankLedgerStore(BankDatabaseProvisioner databases){this.databases=databases;}
    public boolean ready(Tenant bank){
        try(Connection c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.ledger_account') IS NOT NULL AND to_regclass('bank.ledger_event') IS NOT NULL")){
            r.next();return r.getBoolean(1);
        }catch(SQLException exception){throw unavailable();}
    }
    public List<Definition> read(Tenant bank,List<UUID> ids){
        if(ids.isEmpty())return List.of();
        try(Connection c=connection(bank);var s=c.prepareStatement("SELECT * FROM bank.ledger_account WHERE tenant_id=? AND id=ANY(?)")){
            s.setObject(1,bank.id());s.setArray(2,c.createArrayOf("uuid",ids.toArray()));
            try(var r=s.executeQuery()){
                List<Definition> result=new ArrayList<>();while(r.next())result.add(new Definition(r.getObject("id",UUID.class),bank.id(),r.getString("code"),r.getString("name"),r.getObject("chart_account_id",UUID.class),r.getObject("legal_entity_id",UUID.class),r.getString("currency"),r.getBoolean("active"),r.getLong("version")));return result;
            }
        }catch(SQLException exception){throw unavailable();}
    }
    public void deliver(Tenant bank,Dispatch dispatch){
        Definition d=dispatch.definition();
        if(!d.bankId().equals(bank.id()))throw new TenantException(409,"DATABASE_OWNERSHIP_MISMATCH","Wrong bank route");
        try(Connection c=connection(bank)){
            c.setAutoCommit(false);
            try {
                // Serialize duplicate deliveries even if more than one application instance retries.
                try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,dispatch.requestId().toString());s.execute();}
                String payload=JSON.writeValueAsString(d);
                try(var s=c.prepareStatement("SELECT payload=?::jsonb AS same FROM bank.ledger_event WHERE request_id=?")){
                    s.setString(1,payload);s.setObject(2,dispatch.requestId());
                    try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw new SQLException("Delivery mismatch");c.commit();return;}}
                }
                if(d.version()==0){
                    try(var s=c.prepareStatement("INSERT INTO bank.ledger_account(id,tenant_id,code,name,chart_account_id,legal_entity_id,currency,active,version,last_request_id) VALUES (?,?,?,?,?,?,?,?,?,?)")){
                        s.setObject(1,d.id());s.setObject(2,d.bankId());s.setString(3,d.code());s.setString(4,d.name());s.setObject(5,d.chartAccountId());s.setObject(6,d.legalEntityId());s.setString(7,d.currency());s.setBoolean(8,d.active());s.setLong(9,d.version());s.setObject(10,dispatch.requestId());s.executeUpdate();
                    }
                }else{
                    try(var s=c.prepareStatement("UPDATE bank.ledger_account SET name=?,active=?,version=?,last_request_id=?,updated_at=clock_timestamp() WHERE id=? AND tenant_id=? AND version=? AND code=? AND chart_account_id=? AND legal_entity_id=? AND currency=?")){
                        s.setString(1,d.name());s.setBoolean(2,d.active());s.setLong(3,d.version());s.setObject(4,dispatch.requestId());s.setObject(5,d.id());s.setObject(6,d.bankId());s.setLong(7,d.version()-1);s.setString(8,d.code());s.setObject(9,d.chartAccountId());s.setObject(10,d.legalEntityId());s.setString(11,d.currency());
                        if(s.executeUpdate()!=1)throw new SQLException("Ledger version mismatch");
                    }
                }
                try(var s=c.prepareStatement("INSERT INTO bank.ledger_event(request_id,ledger_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?::jsonb,?,?,?,?)")){
                    s.setObject(1,dispatch.requestId());s.setObject(2,d.id());s.setString(3,payload);s.setString(4,dispatch.makerSubject());s.setString(5,dispatch.reviewerIssuer());s.setString(6,dispatch.reviewerSubject());s.setObject(7,dispatch.correlationId());s.executeUpdate();
                }
                c.commit();
            }catch(Exception exception){c.rollback();throw exception;}
        }catch(TenantException exception){throw exception;}catch(Exception exception){throw unavailable();}
    }
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    private static TenantException unavailable(){return new TenantException(503,"LEDGER_DATABASE_UNAVAILABLE","Ledger database operation needs attention");}
}
