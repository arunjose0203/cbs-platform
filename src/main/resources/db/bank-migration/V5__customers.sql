CREATE TABLE bank.customer (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
 customer_number VARCHAR(40) NOT NULL UNIQUE, document_key VARCHAR(100) NOT NULL UNIQUE,
 version BIGINT NOT NULL CHECK(version>=0), payload JSONB NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE bank.customer_event (
 request_id UUID PRIMARY KEY, customer_id UUID NOT NULL REFERENCES bank.customer(id), payload JSONB NOT NULL,
 maker_subject VARCHAR(200) NOT NULL, reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject), correlation_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER customer_event_immutable BEFORE UPDATE OR DELETE ON bank.customer_event
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
