ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS'));


CREATE TABLE platform.payment_access (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),
 role VARCHAR(16) NOT NULL CHECK(role IN ('RECONCILER','SUPERVISOR','AUDITOR')),active BOOLEAN NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),PRIMARY KEY(tenant_id,staff_id)
);
CREATE FUNCTION platform.check_payment_access() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.bank_id=NEW.tenant_id AND a.action='PAYMENT_ACCESS' AND a.status='APPROVED' AND a.payload->>'staffId'=NEW.staff_id::text AND a.payload->>'role'=NEW.role AND (a.payload->>'active')::boolean=NEW.active) THEN RAISE EXCEPTION 'Payment grant requires matching approval'; END IF; RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER payment_access_evidence AFTER INSERT OR UPDATE ON platform.payment_access DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_payment_access();
CREATE TABLE platform.payment_case (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,payment_id UUID NOT NULL,issuer VARCHAR(512) NOT NULL,opened_by VARCHAR(255) NOT NULL,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('SETTLEMENT_DELAY','RETURNED_PAYMENT','PROVIDER_REVERSAL','DISPUTE')),
 statement_id UUID REFERENCES platform.statement_import(id),line_id UUID REFERENCES platform.statement_line(id),source JSONB NOT NULL,
 idempotency_key UUID NOT NULL,owner_id UUID NOT NULL REFERENCES platform.staff_identity(id),due_date DATE NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','CARRIED','RETURN_PENDING','RESOLVED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0,effective_from DATE,resolved_date DATE,last_decision_id UUID REFERENCES platform.approval_request(id),
 return_id UUID UNIQUE REFERENCES platform.approval_request(id),created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),UNIQUE(issuer,opened_by,idempotency_key),FOREIGN KEY(tenant_id,payment_id) REFERENCES platform.payment_order(tenant_id,id)
);
CREATE UNIQUE INDEX payment_case_active_timing ON platform.payment_case(payment_id) WHERE kind='SETTLEMENT_DELAY' AND state<>'CANCELLED';
CREATE UNIQUE INDEX payment_case_return_once ON platform.payment_case(payment_id) WHERE kind IN ('RETURNED_PAYMENT','PROVIDER_REVERSAL') AND state<>'CANCELLED';
CREATE INDEX payment_case_alert ON platform.payment_case(tenant_id,state,due_date);
CREATE TABLE platform.payment_case_event (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,case_id UUID NOT NULL,subject VARCHAR(255) NOT NULL,action VARCHAR(32) NOT NULL,
 payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(tenant_id,case_id) REFERENCES platform.payment_case(tenant_id,id)
);
CREATE TRIGGER payment_case_event_immutable BEFORE UPDATE OR DELETE ON platform.payment_case_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER payment_case_no_delete BEFORE DELETE ON platform.payment_case FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.protect_payment_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.payment_id,NEW.issuer,NEW.opened_by,NEW.kind,NEW.statement_id,NEW.line_id,NEW.source,NEW.idempotency_key,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.payment_id,OLD.issuer,OLD.opened_by,OLD.kind,OLD.statement_id,OLD.line_id,OLD.source,OLD.idempotency_key,OLD.created_at) THEN RAISE EXCEPTION 'Case origin is immutable'; END IF;
 IF OLD.state IN ('RESOLVED','CANCELLED') THEN RAISE EXCEPTION 'Terminal case is immutable'; END IF;
 IF NEW.state<>OLD.state AND NOT ((OLD.state='OPEN' AND NEW.state IN ('CARRIED','RETURN_PENDING','RESOLVED','CANCELLED')) OR (OLD.state='CARRIED' AND NEW.state='RESOLVED') OR (OLD.state='RETURN_PENDING' AND NEW.state='RESOLVED')) THEN RAISE EXCEPTION 'Invalid case transition'; END IF;
 IF OLD.effective_from IS NOT NULL AND NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN RAISE EXCEPTION 'Carry origin is immutable'; END IF;
 IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Case version must advance'; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER payment_case_protect BEFORE UPDATE ON platform.payment_case FOR EACH ROW EXECUTE FUNCTION platform.protect_payment_case();
ALTER TABLE platform.payment_order ADD return_id UUID UNIQUE REFERENCES platform.approval_request(id);
ALTER TABLE platform.payment_order DROP CONSTRAINT payment_order_state_check;
ALTER TABLE platform.payment_order ADD CHECK(state IN ('APPROVED','SUBMITTING','SUBMITTED','UNKNOWN','SETTLED','REFUND_PENDING','REFUNDED','RETURN_PENDING','RETURNED'));
ALTER TABLE platform.statement_decision DROP CONSTRAINT statement_decision_kind_check;
ALTER TABLE platform.statement_decision ADD CHECK(kind IN ('MATCH','LINK','UNLINK','BOOK','REVERSE','DUPLICATE','CLEAR_CONFLICT','CLOSE','REOPEN','SUPERSEDE','RETURN'));
CREATE OR REPLACE FUNCTION platform.protect_payment_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.account_id,NEW.payload,NEW.provider_profile,NEW.issuer,NEW.maker,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.account_id,OLD.payload,OLD.provider_profile,OLD.issuer,OLD.maker,OLD.created_at) THEN RAISE EXCEPTION 'Payment instruction is immutable'; END IF;
 IF NEW.state<>OLD.state AND NOT (
 (OLD.state='APPROVED' AND NEW.state IN ('SUBMITTING','REFUND_PENDING')) OR
 (OLD.state='SUBMITTING' AND NEW.state IN ('SUBMITTED','UNKNOWN','SETTLED','REFUND_PENDING')) OR
 (OLD.state IN ('SUBMITTED','UNKNOWN') AND NEW.state IN ('SETTLED','REFUND_PENDING')) OR
 (OLD.state='REFUND_PENDING' AND NEW.state='REFUNDED') OR (OLD.state='SETTLED' AND NEW.state='RETURN_PENDING') OR (OLD.state='RETURN_PENDING' AND NEW.state='RETURNED')) THEN RAISE EXCEPTION 'Invalid payment transition'; END IF;
 RETURN NEW;
END; $$;
CREATE OR REPLACE FUNCTION platform.check_payment_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a JOIN platform.account_operation o ON o.request_id=a.id
  WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.action='PAYMENT_ORDER' AND a.status='APPROVED'
  AND o.tenant_id=NEW.tenant_id AND o.account_id=NEW.account_id AND o.payload->'change'->>'kind'='WITHDRAWAL'
  AND o.payload->'change'->>'amount'=NEW.payload->>'amount'
  AND o.payload->'change'->>'assetLedgerId'=NEW.payload->>'settlementLedgerId') THEN RAISE EXCEPTION 'Payment requires approved matching debit'; END IF;
 IF NEW.state IN ('SETTLED','REFUND_PENDING','REFUNDED','RETURN_PENDING','RETURNED') AND NOT EXISTS(
  SELECT 1 FROM platform.payment_resolution r JOIN platform.approval_request a ON a.id=r.approval_id
  WHERE r.payment_id=NEW.id AND r.tenant_id=NEW.tenant_id AND a.status='APPROVED' AND (a.action='PAYMENT_RECONCILE' OR (a.action='STATEMENT_ACTION' AND a.payload->>'kind'='MATCH')) AND a.bank_id=NEW.tenant_id
  AND ((NEW.state IN ('SETTLED','RETURN_PENDING','RETURNED') AND r.outcome='MATCH') OR (NEW.state IN ('REFUND_PENDING','REFUNDED') AND r.outcome='REFUND' AND r.approval_id=NEW.refund_id))) THEN RAISE EXCEPTION 'Payment requires approved resolution'; END IF;
 IF NEW.state IN ('REFUND_PENDING','REFUNDED') AND NOT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.request_id=NEW.refund_id AND o.tenant_id=NEW.tenant_id AND o.account_id=NEW.account_id
  AND o.payload->'change'->>'kind'='WITHDRAWAL_REVERSAL' AND o.payload->'change'->>'originalId'=NEW.id::text
  AND (NEW.state<>'REFUNDED' OR o.completed)) THEN RAISE EXCEPTION 'Payment requires matching refund receipt'; END IF;
 IF NEW.state IN ('RETURN_PENDING','RETURNED') AND NOT EXISTS(SELECT 1 FROM platform.statement_decision d JOIN platform.account_operation o ON o.request_id=d.id JOIN platform.approval_request a ON a.id=d.id JOIN platform.payment_case c ON c.id=d.related_id WHERE d.id=NEW.return_id AND d.kind='RETURN' AND d.tenant_id=NEW.tenant_id AND a.status='APPROVED' AND a.action='STATEMENT_ACTION' AND a.payload->>'kind'='RETURN' AND c.payment_id=NEW.id AND o.account_id=NEW.account_id AND o.payload->'change'->>'kind'='WITHDRAWAL_REVERSAL' AND o.payload->'change'->>'originalId'=NEW.id::text AND (NEW.state<>'RETURNED' OR o.completed)) THEN RAISE EXCEPTION 'Return requires approved evidence and matching customer credit'; END IF;
 RETURN NULL;
END; $$;

CREATE OR REPLACE FUNCTION platform.check_statement_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='statement_import' THEN
  IF NOT EXISTS(SELECT 1 FROM platform.statement_decision d WHERE d.id=NEW.status_decision_id AND d.batch_id=NEW.id AND d.tenant_id=NEW.tenant_id AND d.line_id IS NULL AND ((NEW.status='CLOSED' AND d.kind='CLOSE') OR (NEW.status='OPEN' AND d.kind='REOPEN') OR (NEW.status='SUPERSEDED' AND d.kind='SUPERSEDE'))) THEN RAISE EXCEPTION 'Statement status requires a matching decision'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM platform.statement_decision d WHERE d.id=NEW.decision_id AND d.line_id=NEW.id AND d.batch_id=NEW.batch_id AND d.tenant_id=NEW.tenant_id AND d.kind IN ('MATCH','LINK','UNLINK','RETURN','BOOK','REVERSE','DUPLICATE')) THEN RAISE EXCEPTION 'Statement row requires a matching decision'; END IF;
 END IF;
 RETURN NULL;
END; $$;

CREATE FUNCTION platform.check_payment_case_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.last_decision_id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND ((a.action='PAYMENT_CASE' AND a.payload->>'caseId'=NEW.id::text AND (a.payload->>'caseVersion')::bigint=OLD.version AND (
 (a.payload->>'decision'='ASSIGN' AND NEW.state=OLD.state AND a.payload->>'ownerId'=NEW.owner_id::text AND (a.payload->>'dueDate')::date=NEW.due_date) OR
 (a.payload->>'decision'='CARRY' AND OLD.state='OPEN' AND NEW.state='CARRIED' AND NEW.kind='SETTLEMENT_DELAY' AND NEW.effective_from IS NOT NULL AND (a.payload->>'dueDate')::date=NEW.due_date) OR
 (a.payload->>'decision'='RESOLVE' AND NEW.state='RESOLVED' AND NEW.resolved_date IS NOT NULL) OR
 (a.payload->>'decision'='CANCEL' AND NEW.state='CANCELLED'))) OR (a.action='STATEMENT_ACTION' AND a.payload->>'kind'='RETURN' AND a.payload->>'relatedId'=NEW.id::text AND a.id=NEW.return_id AND NEW.state IN ('RETURN_PENDING','RESOLVED')))) THEN RAISE EXCEPTION 'Case change requires matching approval'; END IF;
 IF NEW.state='RESOLVED' AND NEW.return_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.request_id=NEW.return_id AND o.completed) THEN RAISE EXCEPTION 'Returned credit must be posted'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER payment_case_state_evidence AFTER UPDATE ON platform.payment_case DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_payment_case_state();

CREATE TABLE platform.payment_approval_scope(request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),maker_admin BOOLEAN NOT NULL);
CREATE TRIGGER payment_approval_scope_immutable BEFORE UPDATE OR DELETE ON platform.payment_approval_scope FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

INSERT INTO platform.payment_approval_scope SELECT id,bank_id,true FROM platform.approval_request WHERE action IN ('STATEMENT_ACTION','PAYMENT_RECONCILE');
