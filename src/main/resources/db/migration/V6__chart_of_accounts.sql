-- Chart definitions are bank configuration; monetary ledgers remain a later module.
CREATE TABLE platform.chart_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    code VARCHAR(32) NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name VARCHAR(160) NOT NULL CHECK (length(btrim(name)) > 0),
    description VARCHAR(500) NOT NULL DEFAULT '',
    category VARCHAR(16) NOT NULL CHECK (category IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance VARCHAR(6) NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('GROUP','POSTING')),
    parent_id UUID,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,code),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,parent_id) REFERENCES platform.chart_account(tenant_id,id),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX chart_account_parent_idx ON platform.chart_account(tenant_id,parent_id);
ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check
    CHECK (action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT'));
