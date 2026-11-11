-- Chunked daily servicing: each chunk posts atomically with its own immutable receipt.
CREATE TABLE bank.servicing_chunk (
 run_id UUID NOT NULL REFERENCES bank.servicing_run(request_id),
 chunk_no INTEGER NOT NULL CHECK(chunk_no>=0),
 payload JSONB NOT NULL,
 posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(run_id,chunk_no)
);
CREATE TABLE bank.servicing_item (
 item_id UUID PRIMARY KEY,
 run_id UUID NOT NULL,
 chunk_no INTEGER NOT NULL,
 account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),
 FOREIGN KEY(run_id,chunk_no) REFERENCES bank.servicing_chunk(run_id,chunk_no),
 UNIQUE(run_id,account_id)
);
CREATE TRIGGER servicing_chunk_immutable BEFORE UPDATE OR DELETE ON bank.servicing_chunk FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER servicing_item_immutable BEFORE UPDATE OR DELETE ON bank.servicing_item FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();

-- Once a run exists for a date, only journals planned by that run may post on it.
-- Chunked runs register planned items per chunk; legacy runs list them inline.
CREATE OR REPLACE FUNCTION bank.require_open_business_day() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutoff DATE;
BEGIN
 SELECT closed_through INTO cutoff FROM bank.business_day_state WHERE singleton FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Business day state missing'; END IF;
 IF cutoff IS NOT NULL AND NEW.business_date<=cutoff THEN RAISE EXCEPTION 'Business day is closed'; END IF;
 IF EXISTS(SELECT 1 FROM bank.servicing_run r WHERE r.business_date=NEW.business_date)
  AND NOT EXISTS(SELECT 1 FROM bank.servicing_item i JOIN bank.servicing_run r ON r.request_id=i.run_id WHERE i.item_id=NEW.id AND r.business_date=NEW.business_date)
  AND NOT EXISTS(SELECT 1 FROM bank.servicing_run r WHERE r.business_date=NEW.business_date AND EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(r.payload->'items','[]'::jsonb)) i WHERE (i->>'id')::uuid=NEW.id))
 THEN RAISE EXCEPTION 'Daily servicing has finalized this day'; END IF;
 RETURN NEW;
END; $$;

-- Every chunked servicing event must belong to a planned item of its run.
CREATE OR REPLACE FUNCTION bank.check_servicing_item_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bank.servicing_chunk WHERE run_id=NEW.run_id) AND NOT EXISTS(SELECT 1 FROM bank.servicing_item WHERE item_id=NEW.request_id AND run_id=NEW.run_id AND account_id=NEW.account_id) THEN
  RAISE EXCEPTION 'Servicing event is not planned by its chunk';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER servicing_event_planned BEFORE INSERT ON bank.servicing_event FOR EACH ROW EXECUTE FUNCTION bank.check_servicing_item_event();
