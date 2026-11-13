-- Round 19: Data Migration and Cutover Tooling (Gate E12)
-- Migration batches, staged data, validation logs, trial balance reconciliation

CREATE TABLE platform.migration_batch (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 batch_type VARCHAR(50) NOT NULL CHECK(batch_type IN ('CUSTOMERS','ACCOUNTS','LOANS','TERM_DEPOSITS','DELTA')),
 status VARCHAR(30) NOT NULL CHECK(status IN ('VALIDATED','STAGED','COMMITTED','ROLLED_BACK','FAILED')),
 total_records INTEGER NOT NULL DEFAULT 0,
 valid_records INTEGER NOT NULL DEFAULT 0,
 error_records INTEGER NOT NULL DEFAULT 0,
 summary JSONB NOT NULL DEFAULT '{}'::jsonb,
 created_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 committed_at TIMESTAMPTZ,
 rolled_back_at TIMESTAMPTZ
);
CREATE INDEX migration_batch_tenant ON platform.migration_batch(tenant_id, created_at DESC);

CREATE TABLE platform.migration_staged_item (
 id UUID PRIMARY KEY,
 batch_id UUID NOT NULL REFERENCES platform.migration_batch(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 legacy_id VARCHAR(100) NOT NULL,
 record_type VARCHAR(50) NOT NULL,
 payload JSONB NOT NULL,
 valid BOOLEAN NOT NULL DEFAULT true,
 error_message VARCHAR(500),
 committed BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX migration_staged_batch ON platform.migration_staged_item(batch_id, valid);

CREATE TABLE platform.migration_reconciliation (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 business_date DATE NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('MATCHED','DISCREPANCY')),
 legacy_total NUMERIC(30,6) NOT NULL,
 platform_total NUMERIC(30,6) NOT NULL,
 difference NUMERIC(30,6) NOT NULL,
 breakdown JSONB NOT NULL,
 reconciled_by VARCHAR(255) NOT NULL,
 reconciled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX migration_reconciliation_tenant ON platform.migration_reconciliation(tenant_id, business_date DESC);
CREATE TRIGGER migration_reconciliation_immutable BEFORE UPDATE OR DELETE ON platform.migration_reconciliation FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
