ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW'));
ALTER TABLE platform.customer_registry ADD UNIQUE(tenant_id,id);
CREATE TABLE platform.compliance_case (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES platform.tenant(id), customer_id UUID NOT NULL,
 category VARCHAR(30) NOT NULL CHECK(category IN ('KYC_REVIEW','SCREENING_ALERT','TRANSACTION_REVIEW')),
 status VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLEARED','RESTRICTED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), reviewed_customer_version BIGINT,
 risk VARCHAR(6) CHECK(risk IN ('LOW','MEDIUM','HIGH')), review_due DATE,
 issuer VARCHAR(512) NOT NULL, opened_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,customer_id) REFERENCES platform.customer_registry(tenant_id,id),
 CHECK(status<>'CLEARED' OR (reviewed_customer_version IS NOT NULL AND review_due IS NOT NULL AND risk IS NOT NULL))
);
CREATE INDEX compliance_customer ON platform.compliance_case(tenant_id,customer_id,status);
CREATE INDEX compliance_inbox ON platform.compliance_case(tenant_id,updated_at DESC,id);
CREATE TABLE platform.compliance_event (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,case_id UUID NOT NULL,version BIGINT NOT NULL,
 action VARCHAR(10) NOT NULL CHECK(action IN ('OPENED','NOTE','REVIEWED')),
 issuer VARCHAR(512) NOT NULL,subject VARCHAR(255) NOT NULL,correlation_id UUID NOT NULL,
 approval_id UUID REFERENCES platform.approval_request(id),payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(case_id,version),FOREIGN KEY(tenant_id,case_id) REFERENCES platform.compliance_case(tenant_id,id),
 CHECK((action='REVIEWED')=(approval_id IS NOT NULL))
);
CREATE TRIGGER compliance_event_immutable BEFORE UPDATE OR DELETE ON platform.compliance_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TABLE platform.compliance_command (
 issuer VARCHAR(512) NOT NULL,subject VARCHAR(255) NOT NULL,idempotency_key UUID NOT NULL,
 tenant_id UUID NOT NULL,case_id UUID NOT NULL,payload JSONB NOT NULL,
 PRIMARY KEY(issuer,subject,idempotency_key),FOREIGN KEY(tenant_id,case_id) REFERENCES platform.compliance_case(tenant_id,id)
);
CREATE TRIGGER compliance_command_immutable BEFORE UPDATE OR DELETE ON platform.compliance_command FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.check_compliance_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.version<>OLD.version+1 OR NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.customer_id<>OLD.customer_id OR NEW.category<>OLD.category OR NEW.issuer<>OLD.issuer OR NEW.opened_by<>OLD.opened_by OR NEW.created_at<>OLD.created_at) THEN RAISE EXCEPTION 'Invalid compliance state transition'; END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.compliance_event e WHERE e.case_id=NEW.id AND e.version=NEW.version
  AND e.payload->>'status'=NEW.status
  AND (e.payload->>'reviewedCustomerVersion')::bigint IS NOT DISTINCT FROM NEW.reviewed_customer_version
  AND (e.payload->>'reviewDue')::date IS NOT DISTINCT FROM NEW.review_due
  AND e.payload->>'risk' IS NOT DISTINCT FROM NEW.risk
  AND (e.action<>'REVIEWED' OR EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=e.approval_id AND a.bank_id=NEW.tenant_id AND a.action='COMPLIANCE_REVIEW' AND a.status='APPROVED' AND a.reviewer_subject=e.subject))) THEN RAISE EXCEPTION 'Compliance state requires matching immutable evidence and approval'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER compliance_state_evidence AFTER INSERT OR UPDATE ON platform.compliance_case
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_compliance_state();
CREATE TRIGGER compliance_case_no_delete BEFORE DELETE ON platform.compliance_case FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
