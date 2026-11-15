-- Round 20: complaints and disputes case management. See docs/complaints-and-disputes.md.
-- A complaint is registered against a customer, handled through append-only events and closed only by an
-- approved COMPLAINT_RESOLUTION request (maker-checker). Redress money is never posted from here: the
-- resolution records the decision and the reference of the posting made through the normal money paths.

ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY','LEGAL_HOLD','NOTIFICATION_TEMPLATE','LOAN_MORATORIUM','FX_RATE_FEED','FX_VALUATION_CORRECTION','TERM_STANDING_INSTRUCTION','KYC_REVIEW','CUSTOMER_DOCUMENT_ARCHIVE','COMPLAINT_RESOLUTION'));

-- One running number per bank and year, for references such as CMP-2026-000001.
CREATE TABLE platform.complaint_counter (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 year INTEGER NOT NULL CHECK(year BETWEEN 2000 AND 9999),
 last INTEGER NOT NULL CHECK(last BETWEEN 1 AND 999999),
 PRIMARY KEY(tenant_id,year)
);

-- The register. `details` is the customer's own account of what happened and is stored sealed with the
-- bank's field key (pd1:...). `summary` is a short staff label shown in lists: no personal data belongs in it.
CREATE TABLE platform.complaint (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 reference VARCHAR(20) NOT NULL,
 customer_id UUID NOT NULL,
 account_id UUID REFERENCES platform.account_registry(id),
 channel VARCHAR(12) NOT NULL CHECK(channel IN ('BRANCH','PHONE','EMAIL','LETTER','MOBILE','SOCIAL_MEDIA','REGULATOR')),
 category VARCHAR(20) NOT NULL CHECK(category IN ('SERVICE','FEES_AND_CHARGES','TRANSACTION_DISPUTE','MOBILE_MONEY','CASH','ACCOUNT','LOAN','FRAUD','DATA_PRIVACY','STAFF_CONDUCT','OTHER')),
 summary VARCHAR(200) NOT NULL,
 details TEXT NOT NULL CHECK(details LIKE 'pd1:%'),
 transaction_reference VARCHAR(120),
 disputed_amount NUMERIC(24,6) CHECK(disputed_amount>0),
 disputed_currency VARCHAR(3),
 received_on DATE NOT NULL,
 acknowledge_due DATE NOT NULL CHECK(acknowledge_due>=received_on),
 resolve_due DATE NOT NULL CHECK(resolve_due>=received_on),
 status VARCHAR(8) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')),
 escalation_level SMALLINT NOT NULL DEFAULT 1 CHECK(escalation_level BETWEEN 1 AND 3),
 assignee VARCHAR(200),
 acknowledged_at TIMESTAMPTZ,
 acknowledged_on DATE,
 outcome VARCHAR(14) CHECK(outcome IN ('UPHELD','PARTLY_UPHELD','NOT_UPHELD','WITHDRAWN')),
 redress VARCHAR(12) CHECK(redress IN ('NONE','APOLOGY','EXPLANATION','CORRECTION','REFUND','COMPENSATION')),
 redress_amount NUMERIC(24,6) CHECK(redress_amount>0),
 redress_currency VARCHAR(3),
 root_cause VARCHAR(40),
 resolved_at TIMESTAMPTZ,
 resolved_on DATE,
 resolution_approval_id UUID REFERENCES platform.approval_request(id),
 -- The approved money request (refund, fee reversal, journal) that paid a monetary redress; cleared on reopening.
 redress_request_id UUID REFERENCES platform.approval_request(id),
 reopened_count INTEGER NOT NULL DEFAULT 0 CHECK(reopened_count>=0),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 created_issuer VARCHAR(512) NOT NULL,
 created_subject VARCHAR(200) NOT NULL,
 idempotency_key UUID NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,reference),
 UNIQUE(created_issuer,created_subject,idempotency_key),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES platform.customer_registry(tenant_id,id),
 CHECK((disputed_amount IS NULL)=(disputed_currency IS NULL)),
 CHECK((redress_amount IS NULL)=(redress_currency IS NULL)),
 CHECK((acknowledged_at IS NULL)=(acknowledged_on IS NULL)),
 CHECK((status='RESOLVED')=(resolved_at IS NOT NULL)),
 CHECK(status='OPEN' OR (outcome IS NOT NULL AND redress IS NOT NULL AND root_cause IS NOT NULL AND resolved_on IS NOT NULL AND resolution_approval_id IS NOT NULL)),
 CHECK(status='RESOLVED' OR (outcome IS NULL AND redress IS NULL AND redress_amount IS NULL AND root_cause IS NULL AND resolved_on IS NULL AND resolution_approval_id IS NULL AND redress_request_id IS NULL)),
 CHECK(redress_request_id IS NULL OR redress IN ('REFUND','COMPENSATION'))
);
CREATE INDEX complaint_open ON platform.complaint(tenant_id,resolve_due) WHERE status='OPEN';
CREATE INDEX complaint_customer ON platform.complaint(tenant_id,customer_id);
CREATE INDEX complaint_received ON platform.complaint(tenant_id,received_on);

-- What was received never changes; only the handling state moves, one version at a time.
CREATE FUNCTION platform.guard_complaint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Complaints are evidence and cannot be deleted';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.reference, NEW.customer_id, NEW.account_id, NEW.channel, NEW.category, NEW.summary, NEW.details,
        NEW.transaction_reference, NEW.disputed_amount, NEW.disputed_currency, NEW.received_on, NEW.acknowledge_due,
        NEW.created_issuer, NEW.created_subject, NEW.idempotency_key, NEW.payload_sha256, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.reference, OLD.customer_id, OLD.account_id, OLD.channel, OLD.category, OLD.summary, OLD.details,
        OLD.transaction_reference, OLD.disputed_amount, OLD.disputed_currency, OLD.received_on, OLD.acknowledge_due,
        OLD.created_issuer, OLD.created_subject, OLD.idempotency_key, OLD.payload_sha256, OLD.created_at) THEN
        RAISE EXCEPTION 'A registered complaint cannot be rewritten';
    END IF;
    IF OLD.acknowledged_at IS NOT NULL AND (NEW.acknowledged_at, NEW.acknowledged_on) IS DISTINCT FROM (OLD.acknowledged_at, OLD.acknowledged_on) THEN
        RAISE EXCEPTION 'A complaint acknowledgement cannot be changed';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Complaint changes must advance the version by one';
    END IF;
    IF NEW.reopened_count < OLD.reopened_count OR NEW.escalation_level < OLD.escalation_level THEN
        RAISE EXCEPTION 'Complaint escalations and reopenings are never undone';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER complaint_guard BEFORE UPDATE OR DELETE ON platform.complaint
FOR EACH ROW EXECUTE FUNCTION platform.guard_complaint();

-- Handling history. Notes may quote the customer, so they are sealed like the details.
CREATE TABLE platform.complaint_event (
 id UUID PRIMARY KEY,
 complaint_id UUID NOT NULL REFERENCES platform.complaint(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 action VARCHAR(14) NOT NULL CHECK(action IN ('RECEIVED','ACKNOWLEDGED','ASSIGNED','NOTE','ESCALATED','RESOLVED','REDRESS_POSTED','REOPENED')),
 actor_issuer VARCHAR(512) NOT NULL,
 actor_subject VARCHAR(200) NOT NULL,
 note TEXT NOT NULL CHECK(note LIKE 'pd1:%'),
 details JSONB NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(details)='object'),
 complaint_version BIGINT NOT NULL CHECK(complaint_version>=0),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(complaint_id,complaint_version)
);
CREATE TRIGGER complaint_event_immutable BEFORE UPDATE OR DELETE ON platform.complaint_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

-- One approved money request can settle the redress of one complaint only, across the whole history.
CREATE UNIQUE INDEX complaint_redress_request ON platform.complaint_event((details->>'requestId')) WHERE action='REDRESS_POSTED';
