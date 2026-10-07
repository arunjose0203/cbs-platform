ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT'));
CREATE TABLE platform.account_registry (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 account_number VARCHAR(40) NOT NULL, customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 product_id UUID NOT NULL, product_revision INTEGER NOT NULL,
 currency VARCHAR(3) NOT NULL, liability_account_id UUID NOT NULL REFERENCES platform.chart_account(id),
 state VARCHAR(20) NOT NULL CHECK(state IN ('AWAITING_FUNDING','FROZEN','CLOSED')),
 version BIGINT NOT NULL CHECK(version>=0), request_id UUID NOT NULL REFERENCES platform.approval_request(id),
 delivery VARCHAR(8) NOT NULL CHECK(delivery IN ('PENDING','APPLIED')),
 UNIQUE(tenant_id,account_number),
 FOREIGN KEY(tenant_id,product_id,product_revision) REFERENCES platform.deposit_product_revision(tenant_id,product_id,revision)
);
CREATE TABLE platform.account_delivery (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id), account_id UUID NOT NULL REFERENCES platform.account_registry(id),
 payload JSONB NOT NULL, maker_subject VARCHAR(200) NOT NULL, reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject), correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false, failure_code VARCHAR(64), attempts INTEGER NOT NULL DEFAULT 0,
 last_attempt_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ
);
CREATE INDEX account_delivery_pending ON platform.account_delivery(created_at) WHERE NOT completed;
CREATE INDEX account_customer ON platform.account_registry(tenant_id,customer_id);
