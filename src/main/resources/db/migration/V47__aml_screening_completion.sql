-- Round 11: AML and screening completion. See docs/aml-screening-completion.md.

-- Scheduled re-screening: an activated list version may carry a re-screen interval, set in the approved
-- activation. Runs record why they were queued.
ALTER TABLE platform.screening_list ADD COLUMN rescreen_days INTEGER CHECK(rescreen_days BETWEEN 1 AND 366);
ALTER TABLE platform.screening_list ADD COLUMN format VARCHAR(8) NOT NULL DEFAULT 'CSV' CHECK(format IN ('CSV','UN_XML'));
ALTER TABLE platform.screening_run ADD COLUMN reason VARCHAR(10) NOT NULL DEFAULT 'ACTIVATION' CHECK(reason IN ('ACTIVATION','SCHEDULED'));
CREATE INDEX screening_run_latest ON platform.screening_run(list_id,created_at DESC);

-- Beneficiary screening: M-Pesa payee names are screened before submission, approval and dispatch. A match
-- blocks payouts to that payee until compliance dismisses it (approved) or escalates it. Recorded in its own
-- transaction before the payout is refused, so it has no foreign key to the tenant row the payout locks.
CREATE TABLE platform.beneficiary_hit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 beneficiary VARCHAR(12) NOT NULL CHECK(beneficiary ~ '^254[17][0-9]{8}$'),
 beneficiary_name VARCHAR(300) NOT NULL,
 name_key VARCHAR(300) NOT NULL,
 account_id UUID NOT NULL,
 customer_id UUID NOT NULL,
 list_id UUID NOT NULL,
 entry_ref VARCHAR(80) NOT NULL,
 list_name VARCHAR(40) NOT NULL,
 entry_hash CHAR(64) NOT NULL,
 score NUMERIC(4,3) NOT NULL CHECK(score BETWEEN 0 AND 1),
 matched_name VARCHAR(300) NOT NULL,
 stage VARCHAR(10) NOT NULL CHECK(stage IN ('SUBMIT','APPROVAL','DISPATCH')),
 status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DISMISSED','ESCALATED')),
 version BIGINT NOT NULL DEFAULT 0,
 case_id UUID REFERENCES platform.compliance_case(id),
 decided_by VARCHAR(255),
 approval_id UUID REFERENCES platform.approval_request(id),
 decision JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 decided_at TIMESTAMPTZ,
 FOREIGN KEY(list_id,entry_ref) REFERENCES platform.screening_entry(list_id,entry_ref),
 UNIQUE(tenant_id,beneficiary,name_key,list_id,entry_ref),
 CHECK((status='ESCALATED')=(case_id IS NOT NULL)),
 CHECK((status='DISMISSED')=(approval_id IS NOT NULL))
);
CREATE INDEX beneficiary_hit_queue ON platform.beneficiary_hit(tenant_id,status,created_at);
CREATE INDEX beneficiary_hit_payee ON platform.beneficiary_hit(tenant_id,beneficiary,name_key);
-- Only the decision fields may change, once, from OPEN; identity and evidence columns are fixed.
CREATE FUNCTION platform.check_beneficiary_hit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Beneficiary screening evidence is immutable'; END IF;
 IF OLD.status<>'OPEN' OR NEW.version<>OLD.version+1 OR NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id
  OR NEW.beneficiary<>OLD.beneficiary OR NEW.beneficiary_name<>OLD.beneficiary_name OR NEW.name_key<>OLD.name_key
  OR NEW.account_id<>OLD.account_id OR NEW.customer_id<>OLD.customer_id OR NEW.list_id<>OLD.list_id OR NEW.entry_ref<>OLD.entry_ref
  OR NEW.entry_hash<>OLD.entry_hash OR NEW.score<>OLD.score OR NEW.matched_name<>OLD.matched_name OR NEW.stage<>OLD.stage
  OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'Beneficiary screening evidence is immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER beneficiary_hit_guard BEFORE UPDATE OR DELETE ON platform.beneficiary_hit FOR EACH ROW EXECUTE FUNCTION platform.check_beneficiary_hit();

-- Investigator assignment and case due dates (SLA), plus STR draft exports, recorded as case events.
ALTER TABLE platform.compliance_case ADD COLUMN assignee VARCHAR(255);
ALTER TABLE platform.compliance_case ADD COLUMN due_date DATE;
ALTER TABLE platform.compliance_case ADD CONSTRAINT compliance_case_assignment CHECK((assignee IS NULL)=(due_date IS NULL));
CREATE INDEX compliance_overdue ON platform.compliance_case(tenant_id,due_date) WHERE status='OPEN';
ALTER TABLE platform.compliance_event DROP CONSTRAINT compliance_event_action_check;
ALTER TABLE platform.compliance_event ADD CONSTRAINT compliance_event_action_check CHECK(action IN ('OPENED','NOTE','REVIEWED','ASSIGNED','STR_DRAFT'));

CREATE OR REPLACE FUNCTION platform.check_compliance_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.version<>OLD.version+1 OR NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.customer_id<>OLD.customer_id OR NEW.category<>OLD.category OR NEW.issuer<>OLD.issuer OR NEW.opened_by<>OLD.opened_by OR NEW.created_at<>OLD.created_at) THEN RAISE EXCEPTION 'Invalid compliance state transition'; END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.compliance_event e WHERE e.case_id=NEW.id AND e.version=NEW.version
  AND e.payload->>'status'=NEW.status
  AND (e.payload->>'reviewedCustomerVersion')::bigint IS NOT DISTINCT FROM NEW.reviewed_customer_version
  AND (e.payload->>'reviewDue')::date IS NOT DISTINCT FROM NEW.review_due
  AND e.payload->>'risk' IS NOT DISTINCT FROM NEW.risk
  AND e.payload->>'assignee' IS NOT DISTINCT FROM NEW.assignee
  AND (e.payload->>'dueDate')::date IS NOT DISTINCT FROM NEW.due_date
  AND (e.action<>'REVIEWED' OR EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=e.approval_id AND a.bank_id=NEW.tenant_id AND a.action='COMPLIANCE_REVIEW' AND a.status='APPROVED' AND a.reviewer_subject=e.subject))) THEN RAISE EXCEPTION 'Compliance state requires matching immutable evidence and approval'; END IF;
 RETURN NULL;
END; $$;
