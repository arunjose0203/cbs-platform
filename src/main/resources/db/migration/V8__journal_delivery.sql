ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check
 CHECK(action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST'));
CREATE TABLE platform.journal_delivery (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 reference VARCHAR(80) NOT NULL,
 reversal_of UUID REFERENCES platform.journal_delivery(request_id),
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
 completed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,reference), UNIQUE(reversal_of)
);
CREATE INDEX journal_delivery_pending_idx ON platform.journal_delivery(completed,last_attempt_at,created_at);
CREATE TABLE platform.journal_ledger (
 request_id UUID NOT NULL REFERENCES platform.journal_delivery(request_id),
 ledger_id UUID NOT NULL REFERENCES platform.ledger_registry(id),
 PRIMARY KEY(request_id,ledger_id)
);
CREATE INDEX journal_ledger_dependency_idx ON platform.journal_ledger(ledger_id);
