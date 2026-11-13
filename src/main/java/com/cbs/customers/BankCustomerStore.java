package com.cbs.customers;

import static com.cbs.customers.CustomerModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankCustomerStore {
    private org.springframework.beans.factory.ObjectProvider<com.cbs.access.BankDuties> duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(org.springframework.beans.factory.ObjectProvider<com.cbs.access.BankDuties> duties){this.duties=duties;}
    /** Duty recheck for a request the bank has no receipt for yet (see BankDuties.requireAtDelivery). */
    private void recheck(java.util.UUID request){var service=duties==null?null:duties.getIfAvailable();if(service!=null)service.requireAtDelivery(request);}

    private final BankDatabaseProvisioner databases;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public BankCustomerStore(BankDatabaseProvisioner databases){this.databases=databases;}
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    public boolean ready(Tenant bank){
        try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.customer') IS NOT NULL AND to_regclass('bank.customer_event') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}
    }
    /** Bulk read for batch eligibility checks: one connection, one query. */
    public Map<UUID,Definition> readAll(Tenant bank,Collection<UUID> ids){
        var result=new HashMap<UUID,Definition>();if(ids.isEmpty())return result;
        try(var c=connection(bank);var s=c.prepareStatement("SELECT id,payload FROM bank.customer WHERE tenant_id=? AND id=ANY(?)")){
            s.setObject(1,bank.id());s.setArray(2,c.createArrayOf("uuid",ids.toArray()));
            try(var r=s.executeQuery()){while(r.next())result.put(r.getObject(1,UUID.class),JSON.readValue(r.getString(2),Definition.class));}
            return result;
        }catch(SQLException e){throw unavailable();}
    }
    public Definition read(Tenant bank,UUID id){
        try(var c=connection(bank);var s=c.prepareStatement("SELECT payload FROM bank.customer WHERE tenant_id=? AND id=?")){
            s.setObject(1,bank.id());s.setObject(2,id);try(var r=s.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Definition.class):null;}
        }catch(SQLException e){throw unavailable();}
    }
    public void deliver(Tenant bank,Dispatch job){
        var d=job.definition();if(!d.bankId().equals(bank.id()))throw new TenantException(409,"DATABASE_OWNERSHIP_MISMATCH","Wrong bank route");
        try(var c=connection(bank)){
            c.setAutoCommit(false);
            try{
                try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,job.requestId().toString());s.execute();}
                String payload=JSON.writeValueAsString(d);
                try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.customer_event WHERE request_id=?")){
                    s.setString(1,payload);s.setObject(2,job.requestId());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw new SQLException("Receipt mismatch");c.commit();return;}}
                }
                recheck(job.requestId());
                if(d.version()==0){
                    try(var s=c.prepareStatement("INSERT INTO bank.customer(id,tenant_id,customer_number,document_key,version,payload) VALUES (?,?,?,?,?,?::jsonb)")){
                        s.setObject(1,d.id());s.setObject(2,bank.id());s.setString(3,d.customerNumber());s.setString(4,CustomerService.documentKey(d.profile()));s.setLong(5,d.version());s.setString(6,payload);s.executeUpdate();
                    }
                }else{
                    try(var s=c.prepareStatement("UPDATE bank.customer SET document_key=?,version=?,payload=?::jsonb,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND version=? AND customer_number=?")){
                        s.setString(1,CustomerService.documentKey(d.profile()));s.setLong(2,d.version());s.setString(3,payload);s.setObject(4,bank.id());s.setObject(5,d.id());s.setLong(6,d.version()-1);s.setString(7,d.customerNumber());if(s.executeUpdate()!=1)throw new SQLException("Customer version mismatch");
                    }
                }
                try(var s=c.prepareStatement("INSERT INTO bank.customer_event(request_id,customer_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?::jsonb,?,?,?,?)")){
                    s.setObject(1,job.requestId());s.setObject(2,d.id());s.setString(3,payload);s.setString(4,job.makerSubject());s.setString(5,job.reviewerIssuer());s.setString(6,job.reviewerSubject());s.setObject(7,job.correlationId());s.executeUpdate();
                }
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static TenantException unavailable(){return new TenantException(503,"CUSTOMER_DATABASE_UNAVAILABLE","Customer delivery could not be confirmed");}
}
