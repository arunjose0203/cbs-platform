-- Customer notifications: approved contact preferences and a transactional outbox.
ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE'));

-- Where a customer's alerts go is changed only through independent approval.
CREATE TABLE platform.notification_preference (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 channel VARCHAR(10) NOT NULL CHECK(channel IN ('SMS','EMAIL','NONE')),
 destination VARCHAR(254),
 language VARCHAR(2) NOT NULL CHECK(language IN ('en','fr','sw')),
 events VARCHAR(20)[] NOT NULL,
 consent_reference VARCHAR(120) NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,customer_id),
 CHECK((channel='NONE')=(destination IS NULL))
);
CREATE TABLE platform.notification (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 account_id UUID REFERENCES platform.account_registry(id),
 event VARCHAR(20) NOT NULL,
 source_id UUID NOT NULL,
 channel VARCHAR(10) NOT NULL CHECK(channel IN ('SMS','EMAIL')),
 destination VARCHAR(254) NOT NULL,
 language VARCHAR(2) NOT NULL,
 subject VARCHAR(200),
 body VARCHAR(1000) NOT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','FAILED')),
 attempts INTEGER NOT NULL DEFAULT 0,
 failure_code VARCHAR(80),
 provider VARCHAR(40),
 provider_reference VARCHAR(120),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_attempt_at TIMESTAMPTZ,
 sent_at TIMESTAMPTZ,
 UNIQUE(event,source_id,customer_id,account_id)
);
CREATE INDEX notification_pending ON platform.notification(created_at) WHERE status='PENDING';
CREATE INDEX notification_tenant ON platform.notification(tenant_id,created_at DESC);
