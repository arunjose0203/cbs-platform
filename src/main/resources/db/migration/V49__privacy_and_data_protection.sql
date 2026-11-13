-- Round 18: privacy and data protection. See docs/privacy-and-data-protection.md.
-- V48 is reserved for Round 12 (deposit lifecycle completion), which is developed in parallel; merge Round 12 first.

ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY','LEGAL_HOLD'));

-- Current legal hold per customer. Rows are written only from an approved LEGAL_HOLD request;
-- the approval requests and the audit trail keep the history.
CREATE TABLE platform.legal_hold (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL,
 active BOOLEAN NOT NULL,
 reference VARCHAR(120) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,customer_id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES platform.customer_registry(tenant_id,id)
);

-- Register of data-subject requests (access, rectification, erasure, objection, portability).
CREATE TABLE platform.data_subject_request (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL,
 request_type VARCHAR(16) NOT NULL CHECK(request_type IN ('ACCESS','RECTIFICATION','ERASURE','OBJECTION','PORTABILITY')),
 received_on DATE NOT NULL,
 due_on DATE NOT NULL CHECK(due_on>=received_on),
 reference VARCHAR(120) NOT NULL,
 details VARCHAR(500) NOT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','COMPLETED','REJECTED')),
 outcome_code VARCHAR(40),
 closing_note VARCHAR(500),
 created_issuer VARCHAR(512) NOT NULL,
 created_subject VARCHAR(200) NOT NULL,
 idempotency_key UUID NOT NULL,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 report_exported_at TIMESTAMPTZ,
 closed_subject VARCHAR(200),
 closed_at TIMESTAMPTZ,
 UNIQUE(created_issuer,created_subject,idempotency_key),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES platform.customer_registry(tenant_id,id),
 CHECK((status='OPEN')=(closed_at IS NULL)),
 CHECK(status='OPEN' OR (outcome_code IS NOT NULL AND closing_note IS NOT NULL AND closed_subject IS NOT NULL))
);
CREATE INDEX data_subject_request_open ON platform.data_subject_request(tenant_id,due_on) WHERE status='OPEN';
CREATE INDEX data_subject_request_customer ON platform.data_subject_request(tenant_id,customer_id);

-- Who viewed a customer's record: the data protection officer reads the audit trail per customer.
CREATE INDEX tenant_audit_customer ON platform.tenant_audit(tenant_id,(change_details->>'customerId'),occurred_at)
 WHERE change_details ? 'customerId';
