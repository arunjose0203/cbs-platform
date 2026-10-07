ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check
 CHECK(action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE'));
CREATE TABLE platform.business_day_close (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 business_date DATE NOT NULL,
 next_business_date DATE NOT NULL CHECK(next_business_date>business_date),
 maker_subject VARCHAR(200) NOT NULL,
 reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL,
 correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false,
 failure_code VARCHAR(64),
 attempts INTEGER NOT NULL DEFAULT 0,
 last_attempt_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 completed_at TIMESTAMPTZ,
 totals JSONB,
 UNIQUE(tenant_id,business_date)
);
CREATE UNIQUE INDEX one_pending_day_close_per_bank ON platform.business_day_close(tenant_id) WHERE NOT completed;
