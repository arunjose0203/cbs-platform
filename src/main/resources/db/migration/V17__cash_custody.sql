ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION'));
ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL'));
ALTER TABLE platform.branch_transaction_limit DROP CONSTRAINT branch_transaction_limit_operation_check;
ALTER TABLE platform.branch_transaction_limit ADD CHECK(operation IN ('TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL'));
CREATE TABLE platform.cash_location (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,branch_id UUID NOT NULL,ledger_id UUID NOT NULL UNIQUE REFERENCES platform.ledger_registry(id),
 code VARCHAR(32) NOT NULL,name VARCHAR(160) NOT NULL,currency VARCHAR(3) NOT NULL,kind VARCHAR(8) NOT NULL CHECK(kind IN ('VAULT','TILL')),
 legal_entity_id UUID NOT NULL,request_id UUID NOT NULL UNIQUE REFERENCES platform.approval_request(id),
 applied BOOLEAN NOT NULL DEFAULT false,session_open BOOLEAN NOT NULL DEFAULT false,custodian VARCHAR(255),session_date DATE,counted_date DATE,
 UNIQUE(tenant_id,code),FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
CREATE TABLE platform.cash_delivery (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),branch_id UUID NOT NULL,
 staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),payload JSONB NOT NULL,maker_subject VARCHAR(255) NOT NULL,reviewer_issuer VARCHAR(512) NOT NULL,reviewer_subject VARCHAR(255) NOT NULL,correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false,attempts INTEGER NOT NULL DEFAULT 0,failure_code VARCHAR(80),created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),last_attempt_at TIMESTAMPTZ,completed_at TIMESTAMPTZ,
 FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
CREATE INDEX cash_delivery_pending ON platform.cash_delivery(tenant_id) WHERE NOT completed;
