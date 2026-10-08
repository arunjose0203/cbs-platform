ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE'));
CREATE TABLE platform.credit_policy (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),currency VARCHAR(3) NOT NULL,payload JSONB NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),issuer VARCHAR(512) NOT NULL,PRIMARY KEY(tenant_id,currency)
);
CREATE TABLE platform.credit_application (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL REFERENCES platform.tenant(id),account_id UUID NOT NULL,customer_id UUID NOT NULL,
 customer_version BIGINT NOT NULL,reference VARCHAR(160) NOT NULL,currency VARCHAR(3) NOT NULL,payload JSONB NOT NULL,
 issuer VARCHAR(512) NOT NULL,created_by VARCHAR(255) NOT NULL,idempotency_key UUID NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED' CHECK(status IN ('SUBMITTED','APPROVED','READY','DECLINED','REVOKED')),
 review JSONB,release JSONB,policy_id UUID REFERENCES platform.approval_request(id),reviewer VARCHAR(255),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,reference),UNIQUE(issuer,created_by,idempotency_key),
 FOREIGN KEY(tenant_id,account_id) REFERENCES platform.account_registry(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES platform.customer_registry(tenant_id,id),CHECK(reviewer IS NULL OR reviewer<>created_by)
);
CREATE TABLE platform.credit_event (
 sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,application_id UUID NOT NULL,tenant_id UUID NOT NULL,
 approval_id UUID UNIQUE REFERENCES platform.approval_request(id),kind VARCHAR(40) NOT NULL,subject VARCHAR(255) NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),write_transaction XID8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(tenant_id,application_id) REFERENCES platform.credit_application(tenant_id,id)
);
CREATE TABLE platform.credit_reservation (
 application_id UUID PRIMARY KEY,contract_id UUID UNIQUE NOT NULL,tenant_id UUID NOT NULL,
 FOREIGN KEY(tenant_id,application_id) REFERENCES platform.credit_application(tenant_id,id),FOREIGN KEY(tenant_id,contract_id) REFERENCES platform.contract(tenant_id,id)
);
CREATE TRIGGER credit_event_immutable BEFORE UPDATE OR DELETE ON platform.credit_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER credit_reservation_immutable BEFORE UPDATE OR DELETE ON platform.credit_reservation FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.protect_credit_application() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['status','review','release','policy_id','reviewer']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','review','release','policy_id','reviewer']) THEN RAISE EXCEPTION 'Credit dossier is immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER credit_application_immutable BEFORE UPDATE OR DELETE ON platform.credit_application FOR EACH ROW EXECUTE FUNCTION platform.protect_credit_application();
CREATE FUNCTION platform.check_credit_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='credit_policy' THEN
  IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action='CREDIT_POLICY' AND a.payload=NEW.payload) THEN RAISE EXCEPTION 'Credit policy approval required'; END IF;
 ELSIF NEW.approval_id IS NOT NULL THEN
  IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE WHEN NEW.kind='CONDITIONS_VERIFIED' THEN 'CREDIT_RELEASE' ELSE 'CREDIT_REVIEW' END AND a.payload=NEW.payload AND a.reviewer_subject=NEW.subject) THEN RAISE EXCEPTION 'Credit decision approval required'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER credit_policy_approved AFTER INSERT OR UPDATE ON platform.credit_policy DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_credit_approval();
CREATE CONSTRAINT TRIGGER credit_event_approved AFTER INSERT ON platform.credit_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_credit_approval();
CREATE FUNCTION platform.require_credit_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='LOAN' AND NOT EXISTS(SELECT 1 FROM platform.credit_reservation r JOIN platform.credit_application a ON a.id=r.application_id WHERE r.contract_id=NEW.id AND r.tenant_id=NEW.tenant_id AND a.status='READY' AND a.account_id=NEW.account_id AND a.reference=NEW.payload->'terms'->>'agreementReference') THEN RAISE EXCEPTION 'Loan requires reserved approved credit application'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER loan_credit_required AFTER INSERT ON platform.contract DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.require_credit_reservation();

CREATE FUNCTION platform.check_credit_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e platform.credit_event;
BEGIN
 SELECT * INTO e FROM platform.credit_event WHERE application_id=NEW.id AND write_transaction=pg_current_xact_id() AND approval_id IS NOT NULL ORDER BY sequence DESC LIMIT 1;
 IF NOT FOUND THEN RAISE EXCEPTION 'Credit state requires a new approved event'; END IF;
 IF e.kind='CONDITIONS_VERIFIED' THEN
  IF NEW.status<>'READY' OR NEW.release IS DISTINCT FROM e.payload OR NEW.review IS DISTINCT FROM OLD.review OR NEW.policy_id IS DISTINCT FROM OLD.policy_id OR NEW.reviewer IS DISTINCT FROM OLD.reviewer THEN RAISE EXCEPTION 'Condition verification state mismatch'; END IF;
 ELSE
  IF NEW.review IS DISTINCT FROM e.payload OR NEW.release IS NOT NULL OR NEW.reviewer<>e.subject OR NEW.status<>(CASE e.kind WHEN 'CREDIT_APPROVE' THEN CASE WHEN jsonb_array_length(e.payload->'conditions')=0 THEN 'READY' ELSE 'APPROVED' END WHEN 'CREDIT_DECLINE' THEN 'DECLINED' WHEN 'CREDIT_REVOKE' THEN 'REVOKED' ELSE '' END) THEN RAISE EXCEPTION 'Credit review state mismatch'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER credit_state_approved AFTER UPDATE ON platform.credit_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_credit_state();
