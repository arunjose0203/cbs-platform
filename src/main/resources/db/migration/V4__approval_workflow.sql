CREATE TABLE platform.approval_request (
    id UUID PRIMARY KEY,
    issuer VARCHAR(512) NOT NULL,
    maker_subject VARCHAR(200) NOT NULL,
    idempotency_key UUID NOT NULL,
    action VARCHAR(32) NOT NULL CHECK (action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE')),
    target_id UUID NOT NULL,
    bank_id UUID REFERENCES platform.tenant(id),
    target_label VARCHAR(200) NOT NULL,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    reason VARCHAR(500) NOT NULL,
    payload JSONB NOT NULL,
    before_values JSONB NOT NULL,
    proposed_values JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED','STALE')),
    reviewer_subject VARCHAR(200),
    decision_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_at TIMESTAMPTZ,
    UNIQUE (issuer,maker_subject,idempotency_key),
    CHECK ((status = 'PENDING' AND decided_at IS NULL AND reviewer_subject IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL AND reviewer_subject IS NOT NULL)),
    CHECK (status NOT IN ('APPROVED','REJECTED','STALE') OR reviewer_subject <> maker_subject)
);
CREATE INDEX approval_inbox_idx ON platform.approval_request(issuer,status,created_at DESC,id);
CREATE INDEX approval_maker_idx ON platform.approval_request(issuer,maker_subject,created_at DESC,id);
CREATE INDEX approval_bank_idx ON platform.approval_request(bank_id,created_at DESC,id);
CREATE TABLE platform.approval_event (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES platform.approval_request(id),
    actor_subject VARCHAR(200) NOT NULL,
    action VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX approval_event_request_idx ON platform.approval_event(request_id,occurred_at,id);
CREATE TRIGGER approval_event_immutable BEFORE UPDATE OR DELETE ON platform.approval_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
