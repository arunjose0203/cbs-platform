CREATE TABLE platform.cash_transit (
 dispatch_id UUID PRIMARY KEY REFERENCES platform.cash_delivery(request_id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 source_id UUID NOT NULL REFERENCES platform.cash_location(id),
 destination_id UUID NOT NULL REFERENCES platform.cash_location(id),
 ledger_id UUID NOT NULL REFERENCES platform.ledger_registry(id),
 expected_date DATE NOT NULL,
 CHECK(source_id<>destination_id)
);
CREATE INDEX cash_transit_ledger ON platform.cash_transit(ledger_id);
CREATE TRIGGER cash_transit_immutable BEFORE UPDATE OR DELETE ON platform.cash_transit
 FOR EACH ROW EXECUTE FUNCTION platform.reject_cash_exception_change();
