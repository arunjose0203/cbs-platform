-- P-07: scheduled preparation of the daily cycle. See docs/batch-operations.md.
-- Follows V50 (reserved for Round 14) and V51 (Round 10); no approval action is added.

-- What the scheduler did for each step of each business day; append-only evidence for the operations console.
CREATE TABLE platform.batch_event (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 business_date DATE NOT NULL,
 step VARCHAR(16) NOT NULL CHECK(step IN ('SERVICING','PERIOD_CLOSE','DAY_CLOSE')),
 outcome VARCHAR(20) NOT NULL CHECK(outcome IN ('SUBMITTED','BLOCKED','WAITING_APPROVAL','WAITING_DELIVERY')),
 code VARCHAR(80),
 request_id UUID REFERENCES platform.approval_request(id),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX batch_event_bank ON platform.batch_event(tenant_id,recorded_at DESC);
CREATE TRIGGER batch_event_immutable BEFORE UPDATE OR DELETE ON platform.batch_event
 FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
