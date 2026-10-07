ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check
    CHECK (action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT'));
-- Control metadata reserves codes and protects chart/currency dependencies while delivery is pending.
CREATE TABLE platform.ledger_registry (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    code VARCHAR(32) NOT NULL,
    chart_account_id UUID NOT NULL,
    legal_entity_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK(version>=0),
    delivery VARCHAR(12) NOT NULL CHECK(delivery IN ('PENDING','APPLIED')),
    request_id UUID NOT NULL REFERENCES platform.approval_request(id),
    UNIQUE(tenant_id,code),
    FOREIGN KEY(tenant_id,chart_account_id) REFERENCES platform.chart_account(tenant_id,id),
    FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES platform.legal_entity(tenant_id,id)
);
CREATE TABLE platform.ledger_delivery (
    request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    ledger_id UUID NOT NULL REFERENCES platform.ledger_registry(id),
    payload JSONB NOT NULL,
    maker_subject VARCHAR(200) NOT NULL,
    reviewer_issuer VARCHAR(512) NOT NULL,
    reviewer_subject VARCHAR(200) NOT NULL,
    correlation_id UUID NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT false,
    attempts INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(64),
    last_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX ledger_delivery_pending_idx ON platform.ledger_delivery(completed,last_attempt_at,created_at);
