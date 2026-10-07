ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check
 CHECK(action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT'));
CREATE TABLE platform.deposit_product (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 code VARCHAR(32) NOT NULL CHECK(code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
 name VARCHAR(160) NOT NULL CHECK(length(btrim(name))>0),
 description VARCHAR(500) NOT NULL,
 product_type VARCHAR(16) NOT NULL CHECK(product_type IN ('SAVINGS','CURRENT')),
 currency CHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 minimum_opening NUMERIC(24,6) NOT NULL CHECK(minimum_opening>=0),
 minimum_balance NUMERIC(24,6) NOT NULL CHECK(minimum_balance>=0 AND minimum_balance<=minimum_opening),
 liability_account_id UUID NOT NULL,
 active BOOLEAN NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,code), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,liability_account_id) REFERENCES platform.chart_account(tenant_id,id)
);
CREATE INDEX deposit_product_mapping ON platform.deposit_product(tenant_id,liability_account_id) WHERE active;
CREATE TABLE platform.deposit_product_revision (
 tenant_id UUID NOT NULL,
 product_id UUID NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>0),
 definition JSONB NOT NULL,
 approved_by VARCHAR(200) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,product_id,revision),
 FOREIGN KEY(tenant_id,product_id) REFERENCES platform.deposit_product(tenant_id,id)
);
CREATE FUNCTION platform.reject_product_revision_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Product revisions are immutable'; END; $$;
CREATE TRIGGER product_revision_immutable BEFORE UPDATE OR DELETE ON platform.deposit_product_revision
 FOR EACH ROW EXECUTE FUNCTION platform.reject_product_revision_change();
