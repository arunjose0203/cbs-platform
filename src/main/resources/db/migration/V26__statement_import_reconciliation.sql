ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION'));

CREATE TABLE platform.statement_import (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL REFERENCES platform.tenant(id),ledger_id UUID NOT NULL REFERENCES platform.ledger_registry(id),
 issuer VARCHAR(512) NOT NULL,importer VARCHAR(255) NOT NULL,reference VARCHAR(80) NOT NULL,
 source JSONB NOT NULL,fingerprint VARCHAR(64) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED','SUPERSEDED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),status_decision_id UUID,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,ledger_id,fingerprint),UNIQUE(tenant_id,ledger_id,reference)
);
CREATE TABLE platform.statement_import_command (
 issuer VARCHAR(512) NOT NULL,subject VARCHAR(255) NOT NULL,idempotency_key UUID NOT NULL,
 tenant_id UUID NOT NULL,batch_id UUID NOT NULL,request_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(issuer,subject,idempotency_key),FOREIGN KEY(tenant_id,batch_id) REFERENCES platform.statement_import(tenant_id,id)
);
CREATE TABLE platform.statement_line (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,batch_id UUID NOT NULL,line_number INTEGER NOT NULL,
 source JSONB NOT NULL,identity_hash VARCHAR(64) NOT NULL,decision_id UUID,
 UNIQUE(batch_id,line_number),UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,batch_id) REFERENCES platform.statement_import(tenant_id,id)
);
CREATE INDEX statement_line_identity ON platform.statement_line(tenant_id,identity_hash);
CREATE TABLE platform.statement_decision (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL,batch_id UUID NOT NULL,
 line_id UUID,kind VARCHAR(20) NOT NULL CHECK(kind IN ('MATCH','LINK','UNLINK','BOOK','REVERSE','DUPLICATE','CLEAR_CONFLICT','CLOSE','REOPEN','SUPERSEDE')),
 related_id UUID,journal_id UUID REFERENCES platform.journal_delivery(request_id),
 subject VARCHAR(255) NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,batch_id) REFERENCES platform.statement_import(tenant_id,id),FOREIGN KEY(tenant_id,line_id) REFERENCES platform.statement_line(tenant_id,id)
);
ALTER TABLE platform.statement_line ADD FOREIGN KEY(decision_id) REFERENCES platform.statement_decision(id);
CREATE TABLE platform.statement_journal_claim (
 tenant_id UUID NOT NULL,ledger_id UUID NOT NULL,journal_id UUID NOT NULL REFERENCES platform.journal_delivery(request_id),
 line_id UUID NOT NULL,decision_id UUID NOT NULL REFERENCES platform.statement_decision(id),
 PRIMARY KEY(tenant_id,ledger_id,journal_id),FOREIGN KEY(tenant_id,line_id) REFERENCES platform.statement_line(tenant_id,id)
);
CREATE TRIGGER statement_command_immutable BEFORE UPDATE OR DELETE ON platform.statement_import_command FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER statement_decision_immutable BEFORE UPDATE OR DELETE ON platform.statement_decision FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER statement_claim_immutable BEFORE UPDATE OR DELETE ON platform.statement_journal_claim FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER statement_import_no_delete BEFORE DELETE ON platform.statement_import FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER statement_line_no_delete BEFORE DELETE ON platform.statement_line FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.protect_statement_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='statement_import' THEN
  IF (to_jsonb(NEW)-'status'-'status_decision_id') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'status_decision_id') THEN RAISE EXCEPTION 'Statement source is immutable'; END IF;
 ELSE
  IF (to_jsonb(NEW)-'decision_id') IS DISTINCT FROM (to_jsonb(OLD)-'decision_id') THEN RAISE EXCEPTION 'Statement line is immutable'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER statement_source_immutable BEFORE UPDATE ON platform.statement_import FOR EACH ROW EXECUTE FUNCTION platform.protect_statement_source();
CREATE TRIGGER statement_line_immutable BEFORE UPDATE ON platform.statement_line FOR EACH ROW EXECUTE FUNCTION platform.protect_statement_source();
CREATE FUNCTION platform.check_statement_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a JOIN platform.statement_import b ON b.id=NEW.batch_id
  WHERE a.id=NEW.id AND a.action='STATEMENT_ACTION' AND a.status='APPROVED' AND a.bank_id=NEW.tenant_id AND a.reviewer_subject=NEW.subject AND b.importer<>NEW.subject
  AND a.payload->>'batchId'=NEW.batch_id::text AND a.payload->>'kind'=NEW.kind
  AND (a.payload->>'lineId') IS NOT DISTINCT FROM NEW.line_id::text
  AND (NEW.journal_id IS NULL OR EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.journal_id AND j.tenant_id=NEW.tenant_id))
  AND (NEW.line_id IS NULL OR EXISTS(SELECT 1 FROM platform.statement_line l WHERE l.id=NEW.line_id AND l.batch_id=NEW.batch_id))) THEN RAISE EXCEPTION 'Statement decision requires independent approval'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER statement_decision_evidence AFTER INSERT ON platform.statement_decision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_statement_decision();
CREATE OR REPLACE FUNCTION platform.check_payment_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a JOIN platform.account_operation o ON o.request_id=a.id
  WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.action='PAYMENT_ORDER' AND a.status='APPROVED'
  AND o.tenant_id=NEW.tenant_id AND o.account_id=NEW.account_id AND o.payload->'change'->>'kind'='WITHDRAWAL'
  AND o.payload->'change'->>'amount'=NEW.payload->>'amount'
  AND o.payload->'change'->>'assetLedgerId'=NEW.payload->>'settlementLedgerId') THEN RAISE EXCEPTION 'Payment requires approved matching debit'; END IF;
 IF NEW.state IN ('SETTLED','REFUND_PENDING','REFUNDED') AND NOT EXISTS(
  SELECT 1 FROM platform.payment_resolution r JOIN platform.approval_request a ON a.id=r.approval_id
  WHERE r.payment_id=NEW.id AND r.tenant_id=NEW.tenant_id AND a.status='APPROVED' AND (a.action='PAYMENT_RECONCILE' OR (a.action='STATEMENT_ACTION' AND a.payload->>'kind'='MATCH')) AND a.bank_id=NEW.tenant_id
  AND ((NEW.state='SETTLED' AND r.outcome='MATCH') OR (NEW.state IN ('REFUND_PENDING','REFUNDED') AND r.outcome='REFUND' AND r.approval_id=NEW.refund_id))) THEN RAISE EXCEPTION 'Payment requires approved resolution'; END IF;
 IF NEW.state IN ('REFUND_PENDING','REFUNDED') AND NOT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.request_id=NEW.refund_id AND o.tenant_id=NEW.tenant_id AND o.account_id=NEW.account_id
  AND o.payload->'change'->>'kind'='WITHDRAWAL_REVERSAL' AND o.payload->'change'->>'originalId'=NEW.id::text
  AND (NEW.state<>'REFUNDED' OR o.completed)) THEN RAISE EXCEPTION 'Payment requires matching refund receipt'; END IF;
 RETURN NULL;
END; $$;

ALTER TABLE platform.statement_import ADD FOREIGN KEY(status_decision_id) REFERENCES platform.statement_decision(id) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION platform.check_statement_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='statement_import' THEN
  IF NOT EXISTS(SELECT 1 FROM platform.statement_decision d WHERE d.id=NEW.status_decision_id AND d.batch_id=NEW.id AND d.tenant_id=NEW.tenant_id AND d.line_id IS NULL AND ((NEW.status='CLOSED' AND d.kind='CLOSE') OR (NEW.status='OPEN' AND d.kind='REOPEN') OR (NEW.status='SUPERSEDED' AND d.kind='SUPERSEDE'))) THEN RAISE EXCEPTION 'Statement status requires a matching decision'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM platform.statement_decision d WHERE d.id=NEW.decision_id AND d.line_id=NEW.id AND d.batch_id=NEW.batch_id AND d.tenant_id=NEW.tenant_id AND d.kind IN ('MATCH','LINK','UNLINK','BOOK','REVERSE','DUPLICATE')) THEN RAISE EXCEPTION 'Statement row requires a matching decision'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER statement_status_evidence AFTER UPDATE ON platform.statement_import DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_statement_projection();
CREATE CONSTRAINT TRIGGER statement_line_evidence AFTER UPDATE ON platform.statement_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_statement_projection();
