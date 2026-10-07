CREATE TABLE platform.cash_exception (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 branch_id UUID NOT NULL, location_id UUID NOT NULL REFERENCES platform.cash_location(id),
 issuer VARCHAR(512) NOT NULL, reporter VARCHAR(255) NOT NULL,
 currency VARCHAR(3) NOT NULL, business_date DATE NOT NULL,
 book_amount NUMERIC(24,6) NOT NULL CHECK(book_amount>=0),
 counted_amount NUMERIC(24,6) NOT NULL CHECK(counted_amount>=0 AND counted_amount<>book_amount),
 payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
CREATE INDEX cash_exception_location ON platform.cash_exception(tenant_id,location_id);
CREATE TABLE platform.cash_exception_resolution (
 case_id UUID PRIMARY KEY REFERENCES platform.cash_exception(id),
 issuer VARCHAR(512) NOT NULL, reviewer VARCHAR(255) NOT NULL,
 payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION platform.reject_cash_exception_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Cash exception evidence is immutable'; END $$;
CREATE TRIGGER cash_exception_immutable BEFORE UPDATE OR DELETE ON platform.cash_exception
 FOR EACH ROW EXECUTE FUNCTION platform.reject_cash_exception_change();
CREATE TRIGGER cash_exception_resolution_immutable BEFORE UPDATE OR DELETE ON platform.cash_exception_resolution
 FOR EACH ROW EXECUTE FUNCTION platform.reject_cash_exception_change();
