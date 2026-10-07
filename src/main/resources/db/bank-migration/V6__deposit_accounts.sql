-- Opening records only. These IDs are not ledger IDs and cannot receive journal postings.
CREATE TABLE bank.account (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
 account_number VARCHAR(40) NOT NULL UNIQUE, customer_id UUID NOT NULL REFERENCES bank.customer(id),
 name VARCHAR(120) NOT NULL, version BIGINT NOT NULL CHECK(version>=0), payload JSONB NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(payload->>'state' IN ('AWAITING_FUNDING','FROZEN','CLOSED'))
);
CREATE TABLE bank.account_event (
 request_id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES bank.account(id), payload JSONB NOT NULL,
 maker_subject VARCHAR(200) NOT NULL, reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject), correlation_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER account_event_immutable BEFORE UPDATE OR DELETE ON bank.account_event
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
