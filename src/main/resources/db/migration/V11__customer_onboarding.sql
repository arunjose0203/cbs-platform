ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE'));
CREATE TABLE platform.customer_registry (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_number VARCHAR(40) NOT NULL, document_key VARCHAR(100) NOT NULL,
 name_key VARCHAR(200) NOT NULL, birth_date DATE NOT NULL,
 version BIGINT NOT NULL CHECK(version>=0), request_id UUID NOT NULL REFERENCES platform.approval_request(id),
 delivery VARCHAR(8) NOT NULL CHECK(delivery IN ('PENDING','APPLIED')),
 UNIQUE(tenant_id,customer_number), UNIQUE(tenant_id,document_key)
);
CREATE INDEX customer_name_match ON platform.customer_registry(tenant_id,name_key,birth_date);
CREATE TABLE platform.customer_delivery (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id), customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 payload JSONB NOT NULL, maker_subject VARCHAR(200) NOT NULL, reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject), correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false, failure_code VARCHAR(64), attempts INTEGER NOT NULL DEFAULT 0,
 last_attempt_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ
);
CREATE INDEX customer_delivery_pending ON platform.customer_delivery(created_at) WHERE NOT completed;
