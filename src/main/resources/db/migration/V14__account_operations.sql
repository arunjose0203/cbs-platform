ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION'));
CREATE TABLE platform.account_operation (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 account_id UUID NOT NULL REFERENCES platform.account_registry(id),
 destination_id UUID REFERENCES platform.account_registry(id),
 payload JSONB NOT NULL, maker_subject VARCHAR(200) NOT NULL,
 reviewer_issuer VARCHAR(512) NOT NULL, reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject),
 correlation_id UUID NOT NULL, completed BOOLEAN NOT NULL DEFAULT false,
 failure_code VARCHAR(64), attempts INTEGER NOT NULL DEFAULT 0, last_attempt_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ
);
CREATE INDEX account_operation_queue ON platform.account_operation(created_at) WHERE NOT completed;
