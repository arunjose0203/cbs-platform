CREATE TABLE bank.ledger_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL UNIQUE CHECK(code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name VARCHAR(160) NOT NULL CHECK(length(btrim(name))>0),
    chart_account_id UUID NOT NULL,
    legal_entity_id UUID NOT NULL,
    currency CHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK(version>=0),
    last_request_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE bank.ledger_event (
    request_id UUID PRIMARY KEY,
    ledger_id UUID NOT NULL REFERENCES bank.ledger_account(id),
    payload JSONB NOT NULL,
    maker_subject VARCHAR(200) NOT NULL,
    reviewer_issuer VARCHAR(512) NOT NULL,
    reviewer_subject VARCHAR(200) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION bank.reject_ledger_event_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Ledger events are append-only'; END;
$$;
CREATE TRIGGER ledger_event_immutable BEFORE UPDATE OR DELETE ON bank.ledger_event
FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
