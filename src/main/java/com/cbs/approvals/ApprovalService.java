package com.cbs.approvals;

import static com.cbs.approvals.ApprovalModels.*;
import com.cbs.access.AccessModels.StaffUpdate;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import com.cbs.tenancy.TenantModels.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ApprovalService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final com.cbs.cash.CashService cash;
    private final com.cbs.access.BranchOperations branchOperations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantService tenants;
    private final TenantRepository tenantRepository;
    private final AccessService access;
    private final com.cbs.reference.ReferenceDataService reference;
    private final com.cbs.accounting.ChartService chart;
    private final com.cbs.accounting.LedgerService ledger;
    private final com.cbs.accounting.JournalService journals;
    private final com.cbs.accounting.DayCloseService days;
    private final com.cbs.products.ProductService products;
    private final com.cbs.customers.CustomerService customers;
    private final com.cbs.accounts.AccountService accounts;
    private final com.cbs.accounts.FundingService funding;
    private final com.cbs.accounts.OperationService operations;
    public ApprovalService(com.cbs.cash.CashService cash,com.cbs.access.BranchOperations branchOperations,JdbcTemplate jdbc, PlatformTransactionManager manager, TenantService tenants,
            TenantRepository tenantRepository, AccessService access,com.cbs.reference.ReferenceDataService reference,
            com.cbs.accounting.ChartService chart,com.cbs.accounting.LedgerService ledger,com.cbs.accounting.JournalService journals,com.cbs.accounting.DayCloseService days,com.cbs.products.ProductService products,com.cbs.customers.CustomerService customers,com.cbs.accounts.AccountService accounts,com.cbs.accounts.FundingService funding,com.cbs.accounts.OperationService operations) {this.cash=cash;this.branchOperations=branchOperations;this.operations=operations;this.funding=funding;
        this.accounts=accounts;
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.tenants=tenants;
        this.tenantRepository=tenantRepository; this.access=access;
        this.reference=reference;
        this.chart=chart;
        this.ledger=ledger;
        this.journals=journals;this.days=days;this.products=products;this.customers=customers;
    }

    public Request bank(Actor actor, UUID key, UUID id, Action action, ChangeRequest input) {
        if(action!=Action.BANK_SUSPEND && action!=Action.BANK_RESUME) throw error(400,"INVALID_REQUEST");
        return submit(actor,key,id,action,input.expectedVersion(),input.reason(),input);
    }
    public Request staff(Actor actor, UUID key, UUID id, StaffUpdate input) {
        return submit(actor,key,id,Action.STAFF_UPDATE,input.expectedVersion(),input.reason(),input);
    }
    public Request settings(Actor actor,UUID key,UUID bankId,com.cbs.reference.ReferenceModels.Change input) {
        return submit(actor,key,bankId,Action.BANK_SETTINGS,input.expectedVersion(),input.reason(),input);
    }
    public Request chart(Actor actor,UUID key,UUID bankId,com.cbs.accounting.ChartModels.Change input) {
        return submit(actor,key,bankId,Action.CHART_ACCOUNT,input.expectedVersion(),input.reason(),input);
    }
    public Request ledger(Actor actor,UUID key,UUID bankId,com.cbs.accounting.LedgerModels.Change input) {
        return submit(actor,key,bankId,Action.LEDGER_ACCOUNT,input.expectedVersion(),input.reason(),input);
    }
    public Request cash(Actor actor,UUID key,UUID bank,com.cbs.cash.CashModels.Change input){return submit(actor,key,bank,Action.CASH_OPERATION,input.expectedVersion(),input.reason(),input);}
    public Request assignment(Actor actor,UUID key,UUID bank,com.cbs.access.BranchOperations.Assignment input){return submit(actor,key,bank,Action.ACCOUNT_BRANCH,input.expectedVersion(),input.reason(),input);}
    public Request branchLimit(Actor actor,UUID key,UUID bank,com.cbs.access.BranchOperations.Limit input){return submit(actor,key,bank,Action.BRANCH_LIMIT,input.expectedVersion(),input.reason(),input);}
    public Request limits(Actor actor,UUID key,UUID bankId,com.cbs.access.TransactionLimits.Change input){return submit(actor,key,bankId,Action.TRANSACTION_LIMIT,input.expectedVersion(),input.reason(),input);}
    public Request operation(Actor actor,UUID key,UUID bankId,com.cbs.accounts.OperationModels.Change input){return submit(actor,key,bankId,Action.ACCOUNT_OPERATION,input.expectedVersion(),input.reason(),input);}
    public Request branchOperation(Actor actor,UUID key,UUID bankId,UUID branchId,com.cbs.accounts.OperationModels.Change input){
        return tx.execute(t->{
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},actor.issuer()+":"+actor.subject()+":"+key);
            tenantRepository.get(bankId,true);
            if(!branchId.equals(branchOperations.branch(bankId,input.accountId())))throw error(403,"BRANCH_ACCESS_DENIED");
            return operation(actor,key,bankId,input);
        });
    }
    public Request funding(Actor actor,UUID key,UUID bankId,com.cbs.accounts.FundingModels.Change input){return submit(actor,key,bankId,Action.ACCOUNT_FUNDING,input.expectedVersion(),input.reason(),input);}
    public Request account(Actor actor,UUID key,UUID bankId,com.cbs.accounts.AccountModels.Change input){
        return submit(actor,key,bankId,Action.DEPOSIT_ACCOUNT,input.expectedVersion(),input.reason(),input);
    }
    public Request customer(Actor actor,UUID key,UUID bankId,com.cbs.customers.CustomerModels.Change input){
        return submit(actor,key,bankId,Action.CUSTOMER_CHANGE,input.expectedVersion(),input.reason(),input);
    }
    public Request product(Actor actor,UUID key,UUID bankId,com.cbs.products.ProductModels.Change input) {
        return submit(actor,key,bankId,Action.DEPOSIT_PRODUCT,input.expectedVersion(),input.reason(),input);
    }
    public Request closeDay(Actor actor,UUID key,UUID bankId,com.cbs.accounting.DayCloseModels.Change input) {
        return submit(actor,key,bankId,Action.DAY_CLOSE,input.expectedVersion(),input.reason(),input);
    }
    public Request journal(Actor actor,UUID key,UUID bankId,com.cbs.accounting.JournalModels.Change input) {
        return submit(actor,key,bankId,Action.JOURNAL_POST,input.expectedVersion(),input.reason(),input);
    }
    public Request reverseJournal(Actor actor,UUID key,UUID bankId,UUID original,com.cbs.accounting.JournalModels.Reversal input) {
        AccessService.admin(actor);
        return tx.execute(t->{
            access.requireAssignable(actor.issuer(),actor.subject());
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},actor.issuer()+":"+actor.subject()+":"+key);
            var existing=jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=? AND maker_subject=? AND idempotency_key=?",(r,n)->request(r),actor.issuer(),actor.subject(),key);
            if(!existing.isEmpty()) {
                Request saved=existing.getFirst();
                if(saved.action()!=Action.JOURNAL_POST||!saved.targetId().equals(bankId))throw error(409,"IDEMPOTENCY_CONFLICT");
                var previous=JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,saved.id()),com.cbs.accounting.JournalModels.Change.class);
                if(!original.equals(previous.reversalOf())||input.expectedVersion()!=previous.expectedVersion()||!input.reference().equals(previous.reference())||!input.reason().equals(previous.reason()))throw error(409,"IDEMPOTENCY_CONFLICT");
                return saved;
            }
            tenantRepository.get(bankId,true);
            return journal(actor,key,bankId,journals.reversal(actor,bankId,original,input));
        });
    }
    private Request submit(Actor actor, UUID key, UUID id, Action action, long version, String reason, Object input) {
        if(action!=Action.ACCOUNT_OPERATION&&action!=Action.CASH_OPERATION)AccessService.admin(actor);
        return tx.execute(transaction->{
            access.requireAssignable(actor.issuer(),actor.subject());
            // Serialize retries before checking the target, including retries after the request was approved.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},actor.issuer()+":"+actor.subject()+":"+key);
            var existing=jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=? AND maker_subject=? AND idempotency_key=?",
                    (row,n)->request(row),actor.issuer(),actor.subject(),key);
            String payload=JSON.writeValueAsString(input);
            if(!existing.isEmpty()) {
                Request saved=existing.getFirst();
                Boolean same=jdbc.queryForObject("SELECT payload=?::jsonb FROM platform.approval_request WHERE id=?",Boolean.class,payload,saved.id());
                if(saved.action()!=action || !saved.targetId().equals(id) || !Boolean.TRUE.equals(same)) throw error(409,"IDEMPOTENCY_CONFLICT");
                if(action==Action.ACCOUNT_OPERATION)branchOperations.readRequest(actor,saved.id());
                if(action==Action.CASH_OPERATION)cash.readRequest(actor,saved.id(),false,false);
                return saved;
            }
            Map<String,Object> before,after;
            String label;
            UUID bankId=null;
            if(action==Action.STAFF_UPDATE) {
                var staff=access.staff(id,true);
                if(!staff.issuer().equals(actor.issuer())) throw error(404,"STAFF_NOT_FOUND");
                if(staff.version()!=version) throw error(409,"STALE_VERSION");
                StaffUpdate update=(StaffUpdate)input;
                if(!update.active() && staff.subject().equals(actor.subject())) throw error(409,"SELF_DEACTIVATION_FORBIDDEN");
                before=staffValues(staff.displayName(),staff.email(),staff.active());
                after=staffValues(update.displayName(),update.email(),update.active());
                label=staff.displayName();
            } else if(action==Action.CASH_OPERATION) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.cash.CashModels.Change)input;var entry=cash.validate(actor,bank,change,UUID.randomUUID());before=cash.before(bank,entry);after=cash.values(entry);label=entry.location().code()+" — "+change.kind();bankId=id;
            } else if(action==Action.ACCOUNT_BRANCH) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.access.BranchOperations.Assignment)input;branchOperations.validate(bank,change);
                var old=branchOperations.branch(id,change.accountId());before=old==null?Map.of():Map.of("branchId",old);after=branchOperations.values(change);label="Account branch assignment";bankId=id;
            } else if(action==Action.BRANCH_LIMIT) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.access.BranchOperations.Limit)input;branchOperations.validate(bank,change);
                before=branchOperations.before(id,change);after=branchOperations.values(change);label="Branch transaction limit";bankId=id;
            } else if(action==Action.TRANSACTION_LIMIT) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.access.TransactionLimits.Change)input;
                com.cbs.access.TransactionLimits.validate(bank,change);before=com.cbs.access.TransactionLimits.current(jdbc,id,change.currency(),change.operation());after=com.cbs.access.TransactionLimits.values(change);label=bank.displayName()+" — transaction limits";bankId=id;
            } else if(action==Action.ACCOUNT_OPERATION) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounts.OperationModels.Change)input;
                branchOperations.authorize(actor,id,change,false);var entry=operations.validate(bank,change,UUID.randomUUID());before=Map.of();after=operations.values(bank,change);label=entry.account().accountNumber()+" — "+change.kind();bankId=id;
            } else if(action==Action.ACCOUNT_FUNDING) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounts.FundingModels.Change)input;
                var account=funding.validate(bank,change);before=Map.of();after=funding.values(id,change);label=account.accountNumber()+" — "+change.amount()+" "+account.terms().currency();bankId=id;
            } else if(action==Action.DEPOSIT_ACCOUNT) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounts.AccountModels.Change)input;
                accounts.validate(bank,change);before=accounts.before(id,change);after=accounts.values(id,change);
                label=change.accountId()==null?"New deposit account": "Deposit account "+change.accountId();bankId=id;
            } else if(action==Action.CUSTOMER_CHANGE) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.customers.CustomerModels.Change)input;
                customers.validate(bank,change);before=customers.before(id,change);after=customers.values(change);
                label=change.customerId()==null?"New individual customer":"Customer "+change.customerId();bankId=id;
            } else if(action==Action.DEPOSIT_PRODUCT) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.products.ProductModels.Change)input;
                products.validate(bank,change);before=products.before(id,change);after=products.values(id,change);
                label=change.code()+" — "+change.name().trim();bankId=id;
            } else if(action==Action.DAY_CLOSE) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.DayCloseModels.Change)input;
                days.validate(bank,change,null);before=Map.of("businessDate",change.businessDate().toString());after=days.values(change);
                label=bank.displayName()+" — "+change.businessDate();bankId=id;
            } else if(action==Action.JOURNAL_POST) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.JournalModels.Change)input;
                journals.validate(bank,change);
                before=Map.of();after=journals.values(id,change);
                label=change.reference().trim()+" — "+change.currency();bankId=id;
            } else if(action==Action.LEDGER_ACCOUNT) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.LedgerModels.Change)input;
                ledger.validate(bank,change);
                before=ledger.before(id,change);after=ledger.values(id,change);
                label=change.code()+" — "+change.name().trim();bankId=id;
            } else if(action==Action.CHART_ACCOUNT) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.ChartModels.Change)input;
                chart.validate(bank,change);
                before=chart.before(id,change);after=chart.values(id,change);
                label=change.code()+" — "+change.name().trim();bankId=id;
            } else if(action==Action.BANK_SETTINGS) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.reference.ReferenceModels.Change)input;
                reference.validate(bank,change);
                before=reference.values(reference.read(bank));after=reference.values(change);
                label=bank.displayName();bankId=id;
            } else {
                var bank=tenantRepository.get(id,true);
                if(bank.version()!=version) throw error(409,"STALE_VERSION");
                String from=action==Action.BANK_SUSPEND?"ACTIVE":"SUSPENDED";
                if(!bank.status().name().equals(from)) throw error(409,"INVALID_TRANSITION");
                before=Map.of("status",from);
                after=Map.of("status",action==Action.BANK_SUSPEND?"SUSPENDED":"ACTIVE");
                label=bank.displayName(); bankId=id;
            }
            UUID requestId=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform.approval_request
                    (id,issuer,maker_subject,idempotency_key,action,target_id,bank_id,target_label,expected_version,reason,payload,before_values,proposed_values)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                    """,requestId,actor.issuer(),actor.subject(),key,action.name(),id,bankId,label,version,reason,payload,
                    JSON.writeValueAsString(before),JSON.writeValueAsString(after));
            if(action==Action.ACCOUNT_OPERATION)branchOperations.record(actor,id,(com.cbs.accounts.OperationModels.Change)input,requestId);
            if(action==Action.CASH_OPERATION)cash.record(actor,id,(com.cbs.cash.CashModels.Change)input,requestId);
            audit(actor,requestId,"SUBMITTED",reason);
            return get(actor,requestId,false);
        });
    }

    public Page<Request> list(Actor actor, Scope scope, Action action, UUID bankId, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit,offset);
        String condition=switch(scope) {
            case INBOX -> " AND status='PENDING'";
            case MINE -> " AND maker_subject=?";
            case HISTORY -> " AND status<>'PENDING'";
        };
        List<Object> args=new ArrayList<>(); args.add(actor.issuer());
        if(scope==Scope.MINE) args.add(actor.subject());
        if(action!=null) { condition+=" AND action=?"; args.add(action.name()); }
        if(bankId!=null) { condition+=" AND bank_id=?"; args.add(bankId); }
        args.add(limit); args.add(offset);
        return new Page<>(jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=?"+condition+
                " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(row,n)->request(row),args.toArray()),limit,offset);
    }
    public Details details(Actor actor, UUID id) {
        Request request=get(actor,id,false);
        return new Details(request,jdbc.query("SELECT * FROM platform.approval_event WHERE request_id=? ORDER BY occurred_at,id",
                (row,n)->new Event(row.getObject("id",UUID.class),row.getString("actor_subject"),row.getString("action"),
                        row.getString("reason"),row.getObject("correlation_id",UUID.class),row.getTimestamp("occurred_at").toInstant()),id));
    }

    public Request decide(Actor actor, UUID id, Decision decision, String reason) {
        return tx.execute(transaction->{
            Request request=get(actor,id,true);
            if(request.action()==Action.ACCOUNT_OPERATION)branchOperations.review(actor,id,decision==Decision.CANCEL);else if(request.action()==Action.CASH_OPERATION)cash.readRequest(actor,id,true,decision==Decision.CANCEL);else AccessService.admin(actor);
            boolean own=request.makerSubject().equals(actor.subject());
            if(decision==Decision.CANCEL && !own) throw error(403,"ONLY_MAKER_CAN_CANCEL");
            if(decision!=Decision.CANCEL && own) throw error(403,"SELF_APPROVAL_FORBIDDEN");
            Status next=switch(decision) { case APPROVE -> Status.APPROVED; case REJECT -> Status.REJECTED; case CANCEL -> Status.CANCELLED; };
            if(request.status()!=Status.PENDING) {
                if(request.reviewerSubject().equals(actor.subject()) && (request.status()==next || (decision==Decision.APPROVE && request.status()==Status.STALE))) return request;
                throw error(409,"APPROVAL_ALREADY_DECIDED");
            }
            access.requireAssignable(actor.issuer(),actor.subject());
            if(decision==Decision.APPROVE) {
                // Keep the maker active until the change and its decision have committed.
                var maker=jdbc.queryForObject("SELECT active FROM platform.staff_identity WHERE issuer=? AND subject=? FOR SHARE",
                        Boolean.class,actor.issuer(),request.makerSubject());
                if(!Boolean.TRUE.equals(maker)) throw error(409,"MAKER_DISABLED");
                long version;
                if(request.action()==Action.STAFF_UPDATE) {
                    var staff=access.staff(request.targetId(),true);
                    if(!staff.issuer().equals(actor.issuer())) throw error(404,"STAFF_NOT_FOUND");
                    version=staff.version();
                } else version=tenantRepository.get(request.targetId(),true).version();
                if(version!=request.expectedVersion()) next=Status.STALE;
                else {
                    String auditReason="Approved request "+request.id();
                    ChangeRequest change=new ChangeRequest(request.expectedVersion(),auditReason);
                    switch(request.action()) {
                        case BANK_SUSPEND -> tenants.suspend(actor,request.targetId(),change);
                        case BANK_RESUME -> tenants.resume(actor,request.targetId(),change);
                        case CASH_OPERATION -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            cash.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.cash.CashModels.Change.class),id,request.makerSubject());
                        }
                        case ACCOUNT_BRANCH -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            branchOperations.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.access.BranchOperations.Assignment.class),auditReason);
                        }
                        case BRANCH_LIMIT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            branchOperations.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.access.BranchOperations.Limit.class),auditReason);
                        }
                        case TRANSACTION_LIMIT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            com.cbs.access.TransactionLimits.apply(jdbc,tenantRepository,actor,request.targetId(),JSON.readValue(payload,com.cbs.access.TransactionLimits.Change.class),auditReason);
                        }
                        case ACCOUNT_OPERATION -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            operations.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounts.OperationModels.Change.class),id,request.makerSubject());
                        }
                        case ACCOUNT_FUNDING -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            funding.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounts.FundingModels.Change.class),id,request.makerSubject());
                        }
                        case DEPOSIT_ACCOUNT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            accounts.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounts.AccountModels.Change.class),id,request.makerSubject());
                        }
                        case CUSTOMER_CHANGE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            customers.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.customers.CustomerModels.Change.class),id,request.makerSubject());
                        }
                        case DEPOSIT_PRODUCT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            products.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.products.ProductModels.Change.class),auditReason);
                        }
                        case DAY_CLOSE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            days.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.DayCloseModels.Change.class),id,request.makerSubject());
                        }
                        case JOURNAL_POST -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            journals.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.JournalModels.Change.class),id,request.makerSubject());
                        }
                        case LEDGER_ACCOUNT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            ledger.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.LedgerModels.Change.class),id,request.makerSubject());
                        }
                        case CHART_ACCOUNT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            chart.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.ChartModels.Change.class),auditReason);
                        }
                        case BANK_SETTINGS -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            reference.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.reference.ReferenceModels.Change.class),auditReason);
                        }
                        case STAFF_UPDATE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            StaffUpdate update=JSON.readValue(payload,StaffUpdate.class);
                            access.update(actor,request.targetId(),new StaffUpdate(update.expectedVersion(),update.displayName(),update.email(),update.active(),auditReason));
                        }
                    }
                }
            }
            jdbc.update("UPDATE platform.approval_request SET status=?,reviewer_subject=?,decision_reason=?,decided_at=clock_timestamp() WHERE id=?",
                    next.name(),actor.subject(),reason,id);
            audit(actor,id,next.name(),reason);
            return get(actor,id,false);
        });
    }
    private Request get(Actor actor, UUID id, boolean lock) {
        var rows=jdbc.query("SELECT * FROM platform.approval_request WHERE id=? AND issuer=?"+(lock?" FOR UPDATE":""),
                (row,n)->request(row),id,actor.issuer());
        if(rows.isEmpty()) throw error(404,"APPROVAL_NOT_FOUND");
        if(rows.getFirst().action()==Action.ACCOUNT_OPERATION)branchOperations.readRequest(actor,id);else if(rows.getFirst().action()==Action.CASH_OPERATION)cash.readRequest(actor,id,false,false);else AccessService.admin(actor);
        return rows.getFirst();
    }
    private void audit(Actor actor, UUID id, String action, String reason) {
        jdbc.update("INSERT INTO platform.approval_event(id,request_id,actor_subject,action,reason,correlation_id) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),id,actor.subject(),action,reason,actor.correlationId());
    }
    private static Map<String,Object> staffValues(String name,String email,boolean active) {
        return Map.of("displayName",name,"email",email==null?"":email,"active",active);
    }
    private static Request request(ResultSet row) throws SQLException {
        var decided=row.getTimestamp("decided_at");
        return new Request(row.getObject("id",UUID.class),Action.valueOf(row.getString("action")),row.getObject("target_id",UUID.class),
                row.getObject("bank_id",UUID.class),row.getString("target_label"),row.getLong("expected_version"),row.getString("maker_subject"),
                row.getString("reason"),JSON.readValue(row.getString("before_values"),new TypeReference<Map<String,Object>>(){}),
                JSON.readValue(row.getString("proposed_values"),new TypeReference<Map<String,Object>>(){}),Status.valueOf(row.getString("status")),
                row.getString("reviewer_subject"),row.getString("decision_reason"),row.getTimestamp("created_at").toInstant(),decided==null?null:decided.toInstant());
    }
    private static TenantException error(int status,String code) { return new TenantException(status,code,"Approval request could not be completed"); }
}
