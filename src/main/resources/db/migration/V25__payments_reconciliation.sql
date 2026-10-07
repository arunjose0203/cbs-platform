ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE'));
CREATE TABLE platform.payment_order (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id), tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 account_id UUID NOT NULL, payload JSONB NOT NULL, issuer VARCHAR(512) NOT NULL, maker VARCHAR(255) NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'APPROVED' CHECK(state IN ('APPROVED','SUBMITTING','SUBMITTED','UNKNOWN','SETTLED','REFUND_PENDING','REFUNDED')),
 reconciliation_conflict BOOLEAN NOT NULL DEFAULT false,conversation_id VARCHAR(100),originator_id VARCHAR(100),refund_id UUID UNIQUE REFERENCES platform.approval_request(id),
 provider_profile VARCHAR(64) NOT NULL,callback_hash VARCHAR(64),query_hash VARCHAR(64),query_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),FOREIGN KEY(account_id) REFERENCES platform.account_registry(id),
 FOREIGN KEY(id) REFERENCES platform.account_operation(request_id)
);
CREATE INDEX payment_bank_state ON platform.payment_order(tenant_id,state,created_at);
CREATE TABLE platform.payment_event (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,payment_id UUID NOT NULL,action VARCHAR(40) NOT NULL,
 subject VARCHAR(255) NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,payment_id) REFERENCES platform.payment_order(tenant_id,id)
);
CREATE TABLE platform.payment_observation (
 payment_id UUID NOT NULL REFERENCES platform.payment_order(id),fingerprint VARCHAR(64) NOT NULL,
 payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(payment_id,fingerprint)
);
CREATE TABLE platform.payment_statement (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,payment_id UUID NOT NULL,issuer VARCHAR(512) NOT NULL,
 subject VARCHAR(255) NOT NULL,idempotency_key UUID NOT NULL,payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(issuer,subject,idempotency_key),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,payment_id) REFERENCES platform.payment_order(tenant_id,id)
);
CREATE TABLE platform.payment_resolution (
 payment_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,statement_id UUID NOT NULL UNIQUE,approval_id UUID NOT NULL UNIQUE REFERENCES platform.approval_request(id),
 outcome VARCHAR(6) NOT NULL CHECK(outcome IN ('MATCH','REFUND')),provider_reference VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,provider_reference),
 FOREIGN KEY(tenant_id,payment_id) REFERENCES platform.payment_order(tenant_id,id),
 FOREIGN KEY(tenant_id,statement_id) REFERENCES platform.payment_statement(tenant_id,id)
);
CREATE TRIGGER payment_event_immutable BEFORE UPDATE OR DELETE ON platform.payment_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER payment_observation_immutable BEFORE UPDATE OR DELETE ON platform.payment_observation FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER payment_statement_immutable BEFORE UPDATE OR DELETE ON platform.payment_statement FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER payment_resolution_immutable BEFORE UPDATE OR DELETE ON platform.payment_resolution FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER payment_order_no_delete BEFORE DELETE ON platform.payment_order FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.protect_payment_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.account_id,NEW.payload,NEW.provider_profile,NEW.issuer,NEW.maker,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.account_id,OLD.payload,OLD.provider_profile,OLD.issuer,OLD.maker,OLD.created_at) THEN RAISE EXCEPTION 'Payment instruction is immutable'; END IF;
 IF NEW.state<>OLD.state AND NOT (
 (OLD.state='APPROVED' AND NEW.state IN ('SUBMITTING','REFUND_PENDING')) OR
 (OLD.state='SUBMITTING' AND NEW.state IN ('SUBMITTED','UNKNOWN','SETTLED','REFUND_PENDING')) OR
 (OLD.state IN ('SUBMITTED','UNKNOWN') AND NEW.state IN ('SETTLED','REFUND_PENDING')) OR
 (OLD.state='REFUND_PENDING' AND NEW.state='REFUNDED')) THEN RAISE EXCEPTION 'Invalid payment transition'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER payment_order_transition BEFORE UPDATE ON platform.payment_order FOR EACH ROW EXECUTE FUNCTION platform.protect_payment_order();
CREATE FUNCTION platform.check_payment_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a JOIN platform.account_operation o ON o.request_id=a.id
  WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.action='PAYMENT_ORDER' AND a.status='APPROVED'
  AND o.tenant_id=NEW.tenant_id AND o.account_id=NEW.account_id AND o.payload->'change'->>'kind'='WITHDRAWAL'
  AND o.payload->'change'->>'amount'=NEW.payload->>'amount'
  AND o.payload->'change'->>'assetLedgerId'=NEW.payload->>'settlementLedgerId') THEN RAISE EXCEPTION 'Payment requires approved matching debit'; END IF;
 IF NEW.state IN ('SETTLED','REFUND_PENDING','REFUNDED') AND NOT EXISTS(
  SELECT 1 FROM platform.payment_resolution r JOIN platform.approval_request a ON a.id=r.approval_id
  WHERE r.payment_id=NEW.id AND r.tenant_id=NEW.tenant_id AND a.status='APPROVED' AND a.action='PAYMENT_RECONCILE' AND a.bank_id=NEW.tenant_id
  AND ((NEW.state='SETTLED' AND r.outcome='MATCH') OR (NEW.state IN ('REFUND_PENDING','REFUNDED') AND r.outcome='REFUND' AND r.approval_id=NEW.refund_id))) THEN RAISE EXCEPTION 'Payment requires approved resolution'; END IF;
 IF NEW.state IN ('REFUND_PENDING','REFUNDED') AND NOT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.request_id=NEW.refund_id AND o.tenant_id=NEW.tenant_id AND o.account_id=NEW.account_id
  AND o.payload->'change'->>'kind'='WITHDRAWAL_REVERSAL' AND o.payload->'change'->>'originalId'=NEW.id::text
  AND (NEW.state<>'REFUNDED' OR o.completed)) THEN RAISE EXCEPTION 'Payment requires matching refund receipt'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER payment_order_evidence AFTER INSERT OR UPDATE ON platform.payment_order
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_payment_evidence();

CREATE UNIQUE INDEX payment_provider_reference_unique ON platform.payment_resolution(tenant_id,lower(provider_reference));
