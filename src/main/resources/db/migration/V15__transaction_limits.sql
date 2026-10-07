ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT'));
CREATE TABLE platform.transaction_limit (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id), currency VARCHAR(3) NOT NULL,
 operation VARCHAR(32) NOT NULL CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD')),
 per_transaction NUMERIC(24,6) NOT NULL CHECK(per_transaction>0),
 daily_total NUMERIC(24,6) NOT NULL CHECK(daily_total>=per_transaction),
 PRIMARY KEY(tenant_id,currency,operation)
);
