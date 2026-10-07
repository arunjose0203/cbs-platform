package com.cbs.accounts;

import static com.cbs.accounts.AccountModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankAccountStore {
    private final BankDatabaseProvisioner databases;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public BankAccountStore(BankDatabaseProvisioner databases){this.databases=databases;}
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    public boolean ready(Tenant bank){
        try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.account') IS NOT NULL AND to_regclass('bank.account_event') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}
    }
    public Definition read(Tenant bank,UUID id){
        try(var c=connection(bank);var s=c.prepareStatement("SELECT payload FROM bank.account WHERE tenant_id=? AND id=?")){
            s.setObject(1,bank.id());s.setObject(2,id);try(var r=s.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Definition.class):null;}
        }catch(SQLException e){throw unavailable();}
    }
    public void deliver(Tenant bank,Dispatch job){
        try(var c=connection(bank)){c.setAutoCommit(false);try{deliver(c,bank,job);c.commit();}catch(Exception e){c.rollback();throw e;}}
        catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
    }
    /** Writes into the caller transaction; never commits independently. */
    public void deliver(Connection c,Tenant bank,Dispatch job)throws SQLException{
        var d=job.definition();
        if(!d.bankId().equals(bank.id()))throw new TenantException(409,"DATABASE_OWNERSHIP_MISMATCH","Wrong bank route");
                try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,job.requestId().toString());s.execute();}
                String payload=JSON.writeValueAsString(d);
                try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.account_event WHERE request_id=?")){
                    s.setString(1,payload);s.setObject(2,job.requestId());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw new SQLException("Receipt mismatch");return;}}
                }
                if(d.state()==State.AWAITING_FUNDING||d.state()==State.ACTIVE){
                    try(var s=c.prepareStatement("SELECT payload FROM bank.customer WHERE tenant_id=? AND id=? FOR SHARE")){
                        s.setObject(1,bank.id());s.setObject(2,d.customerId());try(var r=s.executeQuery()){
                            if(!r.next())throw new TenantException(409,"ACCOUNT_CUSTOMER_NOT_READY","Customer is unavailable");
                            var customer=JSON.readValue(r.getString(1),com.cbs.customers.CustomerModels.Definition.class);
                            com.cbs.customers.CustomerService.checkEligible(customer);
                        }
                    }
                }
                if(d.version()==0){
                    try(var s=c.prepareStatement("INSERT INTO bank.account(id,tenant_id,account_number,customer_id,version,payload,name) VALUES (?,?,?,?,?,?::jsonb,?)")){
                        s.setObject(1,d.id());s.setObject(2,bank.id());s.setString(3,d.accountNumber());s.setObject(4,d.customerId());s.setLong(5,d.version());s.setString(6,payload);s.setString(7,d.name());s.executeUpdate();
                    }
                }else{
                    try(var s=c.prepareStatement("UPDATE bank.account SET name=?,version=?,payload=?::jsonb,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND version=? AND account_number=?")){
                        s.setString(1,d.name());s.setLong(2,d.version());s.setString(3,payload);s.setObject(4,bank.id());s.setObject(5,d.id());s.setLong(6,d.version()-1);s.setString(7,d.accountNumber());if(s.executeUpdate()!=1)throw new SQLException("Account version mismatch");
                    }
                }
                try(var s=c.prepareStatement("INSERT INTO bank.account_event(request_id,account_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?::jsonb,?,?,?,?)")){
                    s.setObject(1,job.requestId());s.setObject(2,d.id());s.setString(3,payload);s.setString(4,job.makerSubject());s.setString(5,job.reviewerIssuer());s.setString(6,job.reviewerSubject());s.setObject(7,job.correlationId());s.executeUpdate();
                }

    }
    private static TenantException unavailable(){return new TenantException(503,"ACCOUNT_DATABASE_UNAVAILABLE","Account delivery could not be confirmed");}
}
