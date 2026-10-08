package com.cbs.fx;

import static com.cbs.fx.FxValuationModels.*;
import com.cbs.accounting.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankFxValuationStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final BankDatabaseProvisioner databases;
    private final BankJournalStore journals;
    public BankFxValuationStore(BankDatabaseProvisioner databases, BankJournalStore journals) {
        this.databases=databases; this.journals=journals;
    }
    public boolean ready(Tenant bank) {
        try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.fx_valuation') IS NOT NULL")) {
            r.next(); return r.getBoolean(1);
        } catch(SQLException e) { throw unavailable(); }
    }
    public Snapshot snapshot(Tenant bank, Book book, ClosingRate rate) {
        try(var c=connection(bank)) { return snapshot(c,book,rate); }
        catch(TenantException e) { throw e; } catch(Exception e) { throw unavailable(); }
    }
    private Snapshot snapshot(Connection c, Book book, ClosingRate rate) throws Exception {
        var p=book.policy(); var flows=new ArrayList<Flow>(); var digest=MessageDigest.getInstance("SHA-256");
        try(var s=c.prepareStatement("SELECT t.payload FROM bank.fx_trade t JOIN bank.journal j ON j.id=t.source_journal WHERE t.tenant_id=? ORDER BY j.posted_at,t.id")) {
            s.setObject(1,book.bankId());
            try(var rows=s.executeQuery()) { while(rows.next()) {
                var b=JSON.readValue(rows.getString(1),FxTradeModels.Batch.class);
                UUID from=b.sourceJournal().lines().getLast().ledgerId(), to=b.targetJournal().lines().getFirst().ledgerId();
                if(!List.of(from,to).contains(p.foreignPositionLedger()) && !List.of(from,to).contains(p.functionalPositionLedger())) continue;
                boolean buy=from.equals(p.foreignPositionLedger()) && to.equals(p.functionalPositionLedger());
                boolean sell=to.equals(p.foreignPositionLedger()) && from.equals(p.functionalPositionLedger());
                if(!buy && !sell) throw error("FX_BOOK_MAPPING_CONFLICT");
                if(b.quote().businessDate().isAfter(rate.businessDate())) throw error("FX_VALUATION_DATE_INVALID");
                BigDecimal q=new BigDecimal(buy?b.quote().sourceAmount():b.quote().targetAmount());
                BigDecimal cash=new BigDecimal(buy?b.quote().targetAmount():b.quote().sourceAmount());
                flows.add(new Flow(buy?q:q.negate(),buy?cash.negate():cash));
                digest.update(JSON.writeValueAsString(b).getBytes(StandardCharsets.UTF_8));
            }}
        }
        String pr="0",pu="0";
        try(var s=c.prepareStatement("SELECT payload FROM bank.fx_valuation WHERE book_id=? ORDER BY business_date DESC LIMIT 1")) {
            s.setObject(1,book.id()); try(var r=s.executeQuery()) { if(r.next()) {
                var previous=JSON.readValue(r.getString(1),Posting.class);
                pr=previous.snapshot().totals().realized(); pu=previous.snapshot().totals().unrealized();
                digest.update(previous.id().toString().getBytes(StandardCharsets.UTF_8));
            }}
        }
        var position=position(flows);
        BigDecimal foreign=balance(c,p.foreignPositionLedger()), functional=balance(c,p.functionalPositionLedger());
        if(foreign.negate().compareTo(position.quantity())!=0 || functional.compareTo(position.cash().negate().add(new BigDecimal(pr)).add(new BigDecimal(pu)))!=0)
            throw error("FX_BOOK_UNRECONCILED");
        checkPnl(c,p.realizedGainLedger(),new BigDecimal(pr).max(BigDecimal.ZERO).negate());
        checkPnl(c,p.realizedLossLedger(),new BigDecimal(pr).negate().max(BigDecimal.ZERO));
        checkPnl(c,p.unrealizedGainLedger(),new BigDecimal(pu).max(BigDecimal.ZERO).negate());
        checkPnl(c,p.unrealizedLossLedger(),new BigDecimal(pu).negate().max(BigDecimal.ZERO));
        digest.update((foreign.toPlainString()+"/"+functional.toPlainString()).getBytes(StandardCharsets.UTF_8));
        return new Snapshot(value(position,new BigDecimal(rate.rate()),book.functionalCurrency()),pr,pu,HexFormat.of().formatHex(digest.digest()),flows.size());
    }
    private void checkPnl(Connection c, UUID ledger, BigDecimal expected) throws SQLException {
        if(balance(c,ledger).compareTo(expected)!=0) throw error("FX_BOOK_UNRECONCILED");
    }
    private BigDecimal balance(Connection c, UUID ledger) throws SQLException {
        try(var s=c.prepareStatement("SELECT coalesce((SELECT debit-credit FROM bank.ledger_balance WHERE ledger_id=?),0)")) {
            s.setObject(1,ledger);try(var r=s.executeQuery()){r.next();return r.getBigDecimal(1);}
        }
    }
    public void post(Tenant bank, Dispatch job) {
        var p=job.posting();
        if(!bank.id().equals(p.book().bankId())) throw error("DATABASE_OWNERSHIP_MISMATCH");
        try(var c=connection(bank)) { c.setAutoCommit(false); try {
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,31))")) {s.setString(1,p.book().id().toString());s.execute();}
            String payload=JSON.writeValueAsString(p);
            try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.fx_valuation WHERE id=?")) {
                s.setString(1,payload);s.setObject(2,p.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();c.commit();return;}}
            }
            if(bank.status()!=com.cbs.tenancy.TenantModels.Status.ACTIVE)throw error("LEDGER_BANK_NOT_ACTIVE");
            try(var s=c.createStatement();var r=s.executeQuery("SELECT closed_through FROM bank.business_day_state WHERE singleton FOR SHARE")) {
                if(!r.next())throw unavailable();var closed=r.getObject(1,java.time.LocalDate.class);
                if(closed!=null && !p.closingRate().businessDate().isAfter(closed))throw error("BUSINESS_DAY_CLOSED");
            }
            for(UUID ledger:p.book().policy().ledgers().stream().sorted().toList()) {
                try(var s=c.prepareStatement("SELECT id FROM bank.ledger_account WHERE id=? FOR UPDATE")){s.setObject(1,ledger);s.execute();}
            }
            if(!snapshot(c,p.book(),p.closingRate()).equals(p.snapshot())) throw error("FX_VALUATION_CHANGED");
            if(p.journal()!=null) journals.post(c,bank,new JournalModels.Dispatch(p.journal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
            try(var s=c.prepareStatement("INSERT INTO bank.fx_valuation(id,tenant_id,book_id,business_date,journal_id,payload) VALUES (?,?,?,?,?,?::jsonb)")) {
                s.setObject(1,p.id());s.setObject(2,bank.id());s.setObject(3,p.book().id());s.setObject(4,p.closingRate().businessDate());s.setObject(5,p.journal()==null?null:p.journal().id());s.setString(6,payload);s.executeUpdate();
            }
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
        catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    private static TenantException error(String code){return new TenantException(409,code,"FX valuation requires attention");}
    private static TenantException unavailable(){return new TenantException(503,"FX_VALUATION_DATABASE_UNAVAILABLE","Valuation could not be confirmed; retry the same request");}
}
