-- Round 9: bank-scoped duties for platform administrators (segregation of duties).
-- Platform administration stays configuration authority. When a bank enforces duties, an
-- administrator must also hold an approved, unexpired duty for that bank to submit or approve
-- the bank's requests. Lending, payment-operator, branch and cash grants keep their own models.
ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY'));

-- Current duty grant per bank and staff identity. Rows are only written from an approved request.
CREATE TABLE platform.bank_duty (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),
 active BOOLEAN NOT NULL,
 payload JSONB NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 PRIMARY KEY(tenant_id,staff_id),
 CONSTRAINT bank_duty_active_matches CHECK(active=(payload->>'active')::boolean)
);
CREATE TRIGGER bank_duty_no_delete BEFORE DELETE ON platform.bank_duty FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

-- Whether the bank enforces duties. No row means not enforced (the pre-Round 9 behaviour).
CREATE TABLE platform.bank_duty_policy (
 tenant_id UUID PRIMARY KEY REFERENCES platform.tenant(id),
 enforced BOOLEAN NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER bank_duty_policy_no_delete BEFORE DELETE ON platform.bank_duty_policy FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

-- Immutable history of every approved grant and policy change.
CREATE TABLE platform.bank_duty_event (
 sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 approval_id UUID UNIQUE NOT NULL REFERENCES platform.approval_request(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 kind VARCHAR(10) NOT NULL CHECK(kind IN ('GRANT','POLICY')),
 staff_id UUID REFERENCES platform.staff_identity(id),
 payload JSONB NOT NULL,
 CHECK((kind='GRANT')=(staff_id IS NOT NULL))
);
CREATE TRIGGER bank_duty_event_immutable BEFORE UPDATE OR DELETE ON platform.bank_duty_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE INDEX bank_duty_history ON platform.bank_duty_event(tenant_id,sequence DESC);

-- Signed periodic access reviews: the reviewer attests to an exact snapshot (by hash).
CREATE TABLE platform.access_review (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 reviewer_issuer VARCHAR(500) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL,
 snapshot_hash CHAR(64) NOT NULL CHECK(snapshot_hash ~ '^[0-9a-f]{64}$'),
 snapshot JSONB NOT NULL,
 findings INTEGER NOT NULL CHECK(findings>=0),
 statement VARCHAR(500) NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER access_review_immutable BEFORE UPDATE OR DELETE ON platform.access_review FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE INDEX access_review_history ON platform.access_review(tenant_id,reviewed_at DESC);

-- A grant row and its history must come from an approved BANK_DUTY request with the same payload,
-- decided by someone other than the grantee.
CREATE FUNCTION platform.check_bank_duty() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.action='BANK_DUTY' AND a.status='APPROVED'
   AND a.bank_id=NEW.tenant_id AND a.payload=NEW.payload AND a.payload->>'staffId'=NEW.staff_id::text
   AND a.reviewer_subject<>(SELECT subject FROM platform.staff_identity WHERE id=NEW.staff_id)) THEN
  RAISE EXCEPTION 'Bank duty grant requires independent approval';
 END IF;
 IF TG_TABLE_NAME='bank_duty' AND NOT EXISTS(SELECT 1 FROM platform.bank_duty_event e WHERE e.approval_id=NEW.approval_id
   AND e.kind='GRANT' AND e.tenant_id=NEW.tenant_id AND e.staff_id=NEW.staff_id AND e.payload=NEW.payload) THEN
  RAISE EXCEPTION 'Bank duty grant requires immutable history';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER bank_duty_checked AFTER INSERT OR UPDATE ON platform.bank_duty DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_bank_duty();

CREATE FUNCTION platform.check_bank_duty_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.action='BANK_DUTY_POLICY' AND a.status='APPROVED'
   AND a.bank_id=NEW.tenant_id AND (a.payload->>'enforced')::boolean=NEW.enforced) THEN
  RAISE EXCEPTION 'Bank duty policy requires independent approval';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.bank_duty_event e WHERE e.approval_id=NEW.approval_id AND e.kind='POLICY' AND e.tenant_id=NEW.tenant_id) THEN
  RAISE EXCEPTION 'Bank duty policy requires immutable history';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER bank_duty_policy_checked AFTER INSERT OR UPDATE ON platform.bank_duty_policy DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_bank_duty_policy();

CREATE FUNCTION platform.check_bank_duty_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.status='APPROVED' AND a.bank_id=NEW.tenant_id
   AND a.action=CASE NEW.kind WHEN 'GRANT' THEN 'BANK_DUTY' ELSE 'BANK_DUTY_POLICY' END AND a.payload=NEW.payload) THEN
  RAISE EXCEPTION 'Bank duty history requires a matching approved request';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER bank_duty_event_checked AFTER INSERT ON platform.bank_duty_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_bank_duty_event();
