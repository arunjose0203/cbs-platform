package com.cbs.payments;

import com.cbs.accounts.*;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Credits only full, independently verified provider returns. No remote reversal is inferred. */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PaymentReturnService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final OperationService operations;private final ReferenceDataService reference;private final TransactionTemplate tx;
 public PaymentReturnService(JdbcTemplate jdbc,TenantRepository tenants,OperationService operations,ReferenceDataService reference,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.operations=operations;this.reference=reference;this.tx=new TransactionTemplate(manager);}
 public Map<String,Object> caseRow(UUID bank,UUID id){var rows=jdbc.queryForList("SELECT c.*,p.payload AS payment,p.state AS payment_state,p.reconciliation_conflict FROM platform.payment_case c JOIN platform.payment_order p ON p.id=c.payment_id WHERE c.tenant_id=? AND c.id=?",bank,id);if(rows.isEmpty())throw error("PAYMENT_CASE_NOT_FOUND");return rows.getFirst();}
 public OperationModels.Change change(Tenant bank,UUID caseId,long version){var c=caseRow(bank.id(),caseId);var p=JSON.readValue(c.get("payment").toString(),PaymentModels.Order.class);return new OperationModels.Change(version,OperationModels.Kind.WITHDRAWAL_REVERSAL,p.accountId(),null,null,(UUID)c.get("payment_id"),p.amount(),reference.read(bank).businessDate(),"MPESA-RETURN-"+caseId,"Verified full provider return, case "+caseId);}
 public void validate(Actor actor,Tenant bank,UUID caseId,UUID batchId,UUID lineId){
  var c=caseRow(bank.id(),caseId);if(!c.get("issuer").equals(actor.issuer())||!List.of("RETURNED_PAYMENT","PROVIDER_REVERSAL").contains(c.get("kind"))||!c.get("state").equals("OPEN")||!Objects.equals(c.get("statement_id"),batchId)||!Objects.equals(c.get("line_id"),lineId)||!c.get("payment_state").equals("SETTLED")||Boolean.TRUE.equals(c.get("reconciliation_conflict")))throw error("PAYMENT_RETURN_INVALID");
  var p=JSON.readValue(c.get("payment").toString(),PaymentModels.Order.class);var rows=jdbc.queryForList("SELECT l.source,b.source AS document,b.status FROM platform.statement_line l JOIN platform.statement_import b ON b.id=l.batch_id WHERE l.tenant_id=? AND l.id=? AND l.batch_id=? AND l.decision_id IS NULL",bank.id(),lineId,batchId);if(rows.isEmpty())throw error("PAYMENT_RETURN_INVALID");var row=rows.getFirst();var line=JSON.readValue(row.get("source").toString(),StatementImportModels.Line.class);var document=JSON.readValue(row.get("document").toString(),StatementImportModels.Source.class);
  if(!row.get("status").equals("OPEN")||line.type()!=StatementImportModels.Type.REVERSAL||new BigDecimal(line.credit()).compareTo(new BigDecimal(p.amount()))!=0||new BigDecimal(line.debit()).signum()!=0||!line.paymentReference().equals(p.reference())||(!line.beneficiary().isEmpty()&&!line.beneficiary().equals(p.beneficiary()))||!document.ledgerId().equals(p.settlementLedgerId())||!line.date().equals(reference.read(bank).businessDate())||line.date().isBefore(p.businessDate()))throw error("PAYMENT_RETURN_INVALID");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_resolution WHERE tenant_id=? AND lower(provider_reference)=lower(?))",Boolean.class,bank.id(),line.receipt()))throw error("PAYMENT_RECEIPT_USED");
  operations.validatePayment(bank,change(bank,caseId,bank.version()),UUID.randomUUID());
 }
 public UUID apply(Actor actor,Tenant bank,UUID caseId,UUID batchId,UUID lineId,UUID approval,String maker){
  PaymentAccess.require(jdbc,actor,bank.id(),PaymentAccess.Capability.APPROVE);validate(actor,bank,caseId,batchId,lineId);var c=caseRow(bank.id(),caseId);if(actor.subject().equals(c.get("opened_by")))throw error("PAYMENT_CASE_INDEPENDENT_REVIEW");
  operations.enqueueSettlementReturn(actor,bank.id(),change(bank,caseId,bank.version()),approval,maker);
  jdbc.update("UPDATE platform.payment_order SET state='RETURN_PENDING',return_id=?,updated_at=clock_timestamp() WHERE id=?",approval,c.get("payment_id"));
  jdbc.update("UPDATE platform.payment_case SET state='RETURN_PENDING',return_id=?,last_decision_id=?,version=version+1 WHERE id=?",approval,approval,caseId);
  jdbc.update("INSERT INTO platform.payment_case_event VALUES (?,?,?,?,?,?::jsonb,clock_timestamp())",UUID.randomUUID(),bank.id(),caseId,actor.subject(),"RETURN_APPROVED",JSON.writeValueAsString(Map.of("approvalId",approval,"batchId",batchId,"lineId",lineId)));
  return (UUID)c.get("payment_id");
 }
 public List<UUID> pending(){return jdbc.queryForList("SELECT id FROM platform.payment_case WHERE state='RETURN_PENDING' ORDER BY created_at LIMIT 50",UUID.class);}
 public void refresh(UUID caseId){tx.executeWithoutResult(t->{var ids=jdbc.queryForList("SELECT tenant_id FROM platform.payment_case WHERE id=?",UUID.class,caseId);if(ids.isEmpty())return;var bank=tenants.get(ids.getFirst(),true);var c=caseRow(bank.id(),caseId);if(!c.get("state").equals("RETURN_PENDING")||!jdbc.queryForObject("SELECT completed FROM platform.account_operation WHERE request_id=?",Boolean.class,c.get("return_id")))return;
   var date=jdbc.queryForObject("SELECT (payload->'change'->>'businessDate')::date FROM platform.account_operation WHERE request_id=?",java.time.LocalDate.class,c.get("return_id"));
   jdbc.update("UPDATE platform.payment_order SET state='RETURNED',updated_at=clock_timestamp() WHERE id=?",c.get("payment_id"));jdbc.update("UPDATE platform.payment_case SET state='RESOLVED',resolved_date=?,version=version+1 WHERE id=?",date,caseId);
   jdbc.update("INSERT INTO platform.payment_case_event VALUES (?,?,?,?,?,?::jsonb,clock_timestamp())",UUID.randomUUID(),bank.id(),caseId,"system:posting","RETURN_POSTED",JSON.writeValueAsString(Map.of("returnId",c.get("return_id"))));tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
  });}
 public void refreshPayment(Actor actor,UUID bank,UUID payment){PaymentAccess.require(jdbc,actor,bank,PaymentAccess.Capability.MAKE);for(UUID id:jdbc.queryForList("SELECT id FROM platform.payment_case WHERE tenant_id=? AND payment_id=? AND state='RETURN_PENDING'",UUID.class,bank,payment))refresh(id);}
 private static TenantException error(String code){return new TenantException(409,code,"Provider return requires verified evidence and independent approval");}
}
