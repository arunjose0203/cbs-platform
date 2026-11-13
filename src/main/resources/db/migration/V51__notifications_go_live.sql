-- Round 10: customer notifications go-live. See docs/notifications-go-live.md.
-- Follows V48 (Round 12), V49 (Round 18) and V50 (reserved for Round 14). The action list below keeps
-- LEGAL_HOLD from V49; a later migration that rewrites it must keep NOTIFICATION_TEMPLATE.

ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY','LEGAL_HOLD','NOTIFICATION_TEMPLATE'));

-- Outbox: notices and verification codes, suppression after opt-out, and provider delivery receipts.
ALTER TABLE platform.notification DROP CONSTRAINT notification_status_check;
ALTER TABLE platform.notification ADD CONSTRAINT notification_status_check CHECK(status IN ('PENDING','SENT','FAILED','SUPPRESSED'));
ALTER TABLE platform.notification ADD CONSTRAINT notification_event_check CHECK(event IN ('CREDIT','DEBIT','TD_MATURITY','LOAN_DUE','LOAN_ARREARS','VERIFICATION'));
ALTER TABLE platform.notification ADD COLUMN receipt_status VARCHAR(12) CHECK(receipt_status IN ('DELIVERED','UNDELIVERED')),
 ADD COLUMN receipt_at TIMESTAMPTZ, ADD COLUMN receipt_code VARCHAR(80);
CREATE INDEX notification_provider_reference ON platform.notification(provider,provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX notification_customer ON platform.notification(tenant_id,customer_id,created_at DESC);

-- A destination is used for alerts only after the customer proves they receive it (one-time code).
-- Preferences approved before this round were verified by the approver re-entering the destination.
ALTER TABLE platform.notification_preference ADD COLUMN verified_at TIMESTAMPTZ,
 ADD COLUMN code_hash VARCHAR(64), ADD COLUMN code_expires_at TIMESTAMPTZ, ADD COLUMN code_issued_at TIMESTAMPTZ,
 ADD COLUMN code_attempts INTEGER NOT NULL DEFAULT 0;
UPDATE platform.notification_preference SET verified_at=updated_at WHERE channel<>'NONE';

-- Bank-approved message wording per event, language and channel (maker-checker NOTIFICATION_TEMPLATE).
CREATE TABLE platform.notification_template (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 event VARCHAR(20) NOT NULL CHECK(event IN ('CREDIT','DEBIT','TD_MATURITY','LOAN_DUE','LOAN_ARREARS','VERIFICATION')),
 language VARCHAR(2) NOT NULL CHECK(language IN ('en','fr','sw')),
 channel VARCHAR(10) NOT NULL CHECK(channel IN ('SMS','EMAIL')),
 subject VARCHAR(200),
 body VARCHAR(1000) NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,event,language,channel),
 CHECK((channel='EMAIL')=(subject IS NOT NULL))
);

-- Destinations that asked to stop receiving messages (provider STOP reply or a request at the branch).
CREATE TABLE platform.notification_opt_out (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 channel VARCHAR(10) NOT NULL CHECK(channel IN ('SMS','EMAIL')),
 destination VARCHAR(254) NOT NULL,
 source VARCHAR(10) NOT NULL CHECK(source IN ('PROVIDER','STAFF')),
 reference VARCHAR(120) NOT NULL,
 recorded_by VARCHAR(200) NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,channel,destination)
);

-- Every signed provider receipt as received; replays of the same receipt are ignored.
CREATE TABLE platform.notification_receipt (
 provider VARCHAR(40) NOT NULL,
 receipt_id VARCHAR(120) NOT NULL,
 kind VARCHAR(12) NOT NULL CHECK(kind IN ('DELIVERED','UNDELIVERED','OPTED_OUT')),
 provider_reference VARCHAR(120),
 notification_id UUID REFERENCES platform.notification(id),
 received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(provider,receipt_id)
);
CREATE TRIGGER notification_receipt_immutable BEFORE UPDATE OR DELETE ON platform.notification_receipt
 FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
