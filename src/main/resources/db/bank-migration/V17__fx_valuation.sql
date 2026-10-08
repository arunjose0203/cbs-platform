CREATE TABLE bank.fx_valuation (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,book_id UUID NOT NULL,business_date DATE NOT NULL,
 journal_id UUID UNIQUE REFERENCES bank.journal(id),payload JSONB NOT NULL,
 write_transaction XID8 NOT NULL DEFAULT pg_current_xact_id(),UNIQUE(book_id,business_date)
);
CREATE TRIGGER fx_valuation_immutable BEFORE UPDATE OR DELETE ON bank.fx_valuation FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.check_fx_valuation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.write_transaction<>pg_current_xact_id() OR NEW.id<>(NEW.payload->>'id')::uuid OR NEW.tenant_id<>(NEW.payload->'book'->>'bankId')::uuid OR NEW.book_id<>(NEW.payload->'book'->>'id')::uuid OR NEW.business_date<>(NEW.payload->'closingRate'->>'businessDate')::date THEN RAISE EXCEPTION 'Valuation receipt mismatch'; END IF;
 IF (NEW.payload->'journal'='null'::jsonb) IS DISTINCT FROM (NEW.journal_id IS NULL) THEN RAISE EXCEPTION 'Valuation journal mismatch'; END IF;
 IF NEW.journal_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM bank.journal j WHERE j.id=NEW.journal_id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Valuation journal missing'; END IF;
 IF EXISTS(SELECT 1 FROM bank.business_day_close WHERE business_date>=NEW.business_date) THEN RAISE EXCEPTION 'Valuation business date closed'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_valuation_check AFTER INSERT ON bank.fx_valuation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_fx_valuation();
CREATE FUNCTION bank.check_fx_valuation_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bank.fx_valuation v WHERE NEW.ledger_id IN (
 (v.payload->'book'->'policy'->>'realizedGainLedger')::uuid,(v.payload->'book'->'policy'->>'realizedLossLedger')::uuid,
 (v.payload->'book'->'policy'->>'unrealizedGainLedger')::uuid,(v.payload->'book'->'policy'->>'unrealizedLossLedger')::uuid))
 AND NOT EXISTS(SELECT 1 FROM bank.fx_valuation WHERE journal_id=NEW.journal_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'FX profit/loss ledger requires atomic valuation'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_valuation_line_check AFTER INSERT ON bank.journal_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_fx_valuation_line();
