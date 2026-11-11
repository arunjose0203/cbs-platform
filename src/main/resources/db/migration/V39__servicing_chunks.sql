-- Chunked daily servicing progress and approved per-account exclusions.
CREATE TABLE platform.servicing_chunk (
 run_id UUID NOT NULL REFERENCES platform.servicing_run(request_id),
 chunk_no INTEGER NOT NULL CHECK(chunk_no>=0),
 payload JSONB NOT NULL,
 item_count INTEGER NOT NULL CHECK(item_count>0),
 completed BOOLEAN NOT NULL DEFAULT false,
 failure_code VARCHAR(80),
 attempts INTEGER NOT NULL DEFAULT 0,
 last_attempt_at TIMESTAMPTZ,
 completed_at TIMESTAMPTZ,
 PRIMARY KEY(run_id,chunk_no)
);
CREATE INDEX servicing_chunk_pending ON platform.servicing_chunk(run_id,chunk_no) WHERE NOT completed;
CREATE TABLE platform.servicing_exclusion (
 run_id UUID NOT NULL REFERENCES platform.servicing_run(request_id),
 account_id UUID NOT NULL,
 account_number VARCHAR(64) NOT NULL,
 reason VARCHAR(80) NOT NULL,
 PRIMARY KEY(run_id,account_id)
);
CREATE INDEX servicing_exclusion_account ON platform.servicing_exclusion(account_id);
CREATE TRIGGER servicing_exclusion_immutable BEFORE UPDATE OR DELETE ON platform.servicing_exclusion FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
