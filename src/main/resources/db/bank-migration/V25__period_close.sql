-- Approved month-end/year-end closes. Year-end closing journals move income and expense
-- balances into retained earnings on the last business day; afterwards that day is sealed.
CREATE TABLE bank.period_close (
 request_id UUID PRIMARY KEY,
 period_type VARCHAR(5) NOT NULL CHECK(period_type IN ('MONTH','YEAR')),
 period_end DATE NOT NULL UNIQUE,
 payload JSONB NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE bank.period_close_journal (
 journal_id UUID PRIMARY KEY,
 request_id UUID NOT NULL REFERENCES bank.period_close(request_id)
);
CREATE TRIGGER period_close_immutable BEFORE UPDATE OR DELETE ON bank.period_close FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER period_close_journal_immutable BEFORE UPDATE OR DELETE ON bank.period_close_journal FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();

CREATE OR REPLACE FUNCTION bank.require_open_business_day() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutoff DATE;
BEGIN
 SELECT closed_through INTO cutoff FROM bank.business_day_state WHERE singleton FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Business day state missing'; END IF;
 IF cutoff IS NOT NULL AND NEW.business_date<=cutoff THEN RAISE EXCEPTION 'Business day is closed'; END IF;
 -- Closing journals of an approved period close are the only postings after it.
 IF EXISTS(SELECT 1 FROM bank.period_close_journal j JOIN bank.period_close p ON p.request_id=j.request_id WHERE j.journal_id=NEW.id AND p.period_end=NEW.business_date) THEN RETURN NEW; END IF;
 IF EXISTS(SELECT 1 FROM bank.period_close WHERE period_end=NEW.business_date) THEN RAISE EXCEPTION 'Period is closed'; END IF;
 IF EXISTS(SELECT 1 FROM bank.servicing_run r WHERE r.business_date=NEW.business_date)
  AND NOT EXISTS(SELECT 1 FROM bank.servicing_item i JOIN bank.servicing_run r ON r.request_id=i.run_id WHERE i.item_id=NEW.id AND r.business_date=NEW.business_date)
  AND NOT EXISTS(SELECT 1 FROM bank.servicing_run r WHERE r.business_date=NEW.business_date AND EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(r.payload->'items','[]'::jsonb)) i WHERE (i->>'id')::uuid=NEW.id))
 THEN RAISE EXCEPTION 'Daily servicing has finalized this day'; END IF;
 RETURN NEW;
END; $$;

-- Year-end closing may zero FX profit and loss ledgers; everything else still needs a valuation.
CREATE OR REPLACE FUNCTION bank.check_fx_valuation_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bank.period_close_journal WHERE journal_id=NEW.journal_id) THEN RETURN NULL; END IF;
 IF EXISTS(SELECT 1 FROM bank.fx_valuation v WHERE NEW.ledger_id IN (
 (v.payload->'book'->'policy'->>'realizedGainLedger')::uuid,(v.payload->'book'->'policy'->>'realizedLossLedger')::uuid,
 (v.payload->'book'->'policy'->>'unrealizedGainLedger')::uuid,(v.payload->'book'->'policy'->>'unrealizedLossLedger')::uuid))
 AND NOT EXISTS(SELECT 1 FROM bank.fx_valuation WHERE journal_id=NEW.journal_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'FX profit/loss ledger requires atomic valuation'; END IF;
 RETURN NULL;
END; $$;
