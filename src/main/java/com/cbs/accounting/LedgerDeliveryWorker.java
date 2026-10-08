package com.cbs.accounting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name={"cbs.tenancy.enabled","cbs.ledger.delivery-enabled"},havingValue="true",matchIfMissing=true)
public class LedgerDeliveryWorker {
    private final com.cbs.contracts.ContractService contracts;
    private final com.cbs.fx.FxValuationService valuations;
    private final com.cbs.fx.FxTradeService fx;
    private final com.cbs.payments.PaymentReturnService returns;
    private final com.cbs.servicing.ServicingService servicing;
    private final com.cbs.cash.CashService cash;
    private final com.cbs.accounts.OperationService operations;
    private final com.cbs.accounts.AccountService accounts;
    private final com.cbs.accounts.FundingService funding;
    private final LedgerService ledgers;
    private final JournalService journals;
    private final DayCloseService days;
    private final com.cbs.customers.CustomerService customers;
    public LedgerDeliveryWorker(com.cbs.fx.FxValuationService valuations,com.cbs.contracts.ContractService contracts,com.cbs.fx.FxTradeService fx,com.cbs.payments.PaymentReturnService returns,com.cbs.servicing.ServicingService servicing,com.cbs.cash.CashService cash,LedgerService ledgers,JournalService journals,DayCloseService days,com.cbs.customers.CustomerService customers,com.cbs.accounts.AccountService accounts,com.cbs.accounts.FundingService funding,com.cbs.accounts.OperationService operations){this.valuations=valuations;this.contracts=contracts;this.fx=fx;this.returns=returns;this.servicing=servicing;this.cash=cash;this.operations=operations;this.funding=funding;this.accounts=accounts;this.customers=customers;this.ledgers=ledgers;this.journals=journals;this.days=days;}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void postOperations(){
        try{for(var id:valuations.pending())try{valuations.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("FX valuation remains pending; retry is safe");}}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("FX valuation queue unavailable");}
        try{for(var id:contracts.pending())try{contracts.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Contract posting remains pending; retry is safe");}}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Contract queue unavailable");}
        try{for(var id:fx.pending())try{fx.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("FX posting remains pending; retry is safe");}}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("FX queue unavailable");}
        try{for(var id:servicing.pending())try{servicing.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Deposit servicing remains pending; retry is safe");}}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Deposit servicing queue unavailable");}
        try{for(var id:cash.pending())try{cash.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cash delivery remains pending; retry is safe");}}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cash delivery queue is unavailable");}
        try{for(var id:operations.pending())try{operations.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Account operation remains pending; retry is safe");}}
        catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Account operation queue is unavailable");}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void acknowledgeReturns(){try{for(var id:returns.pending())returns.refresh(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment return acknowledgement remains pending");}}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void postFunding(){
        try{for(var id:funding.pending())try{funding.deliver(id);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Funding remains pending; retry is safe");}}
        catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Funding queue is unavailable");}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void deliverAccounts(){
        try{for(var id:accounts.pending())try{accounts.deliver(id);}catch(RuntimeException ignored){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Deposit account delivery remains pending; retry is safe");
        }}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Deposit account queue is unavailable");}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void deliverCustomers(){
        try{for(var id:customers.pending())try{customers.deliver(id);}catch(RuntimeException ignored){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Customer delivery remains pending; retry is safe");
        }}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Customer queue is unavailable");}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void closeDays(){
        try{for(var request:days.pending())try{days.deliver(request);}catch(RuntimeException ignored){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Business day close remains pending; retry is safe");
        }}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Business day close queue is unavailable");}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void postJournals(){
        try{for(var request:journals.pending())try{journals.deliver(request);}catch(RuntimeException ignored){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Journal posting remains pending; retry is safe");
        }}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Journal queue is temporarily unavailable");}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void deliver(){
        try{for(var request:ledgers.pending())try{ledgers.deliver(request);}catch(RuntimeException ignored){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Ledger delivery remains pending; retry is safe");
        }}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Ledger delivery queue is temporarily unavailable");}
    }
}
