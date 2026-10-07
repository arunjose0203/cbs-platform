CREATE TABLE bank.business_day_state (
 singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
 closed_through DATE
);
INSERT INTO bank.business_day_state(singleton) VALUES (true);
CREATE TABLE bank.business_day_close (
 request_id UUID PRIMARY KEY,
 business_date DATE NOT NULL UNIQUE,
 next_business_date DATE NOT NULL CHECK(next_business_date>business_date),
 maker_subject VARCHAR(200) NOT NULL,
 reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject),
 correlation_id UUID NOT NULL,
 totals JSONB NOT NULL,
 closed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER day_close_immutable BEFORE UPDATE OR DELETE ON bank.business_day_close
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.require_open_business_day() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutoff DATE;
BEGIN
 SELECT closed_through INTO cutoff FROM bank.business_day_state WHERE singleton FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Business day state missing'; END IF;
 IF cutoff IS NOT NULL AND NEW.business_date<=cutoff THEN RAISE EXCEPTION 'Business day is closed'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER journal_open_day BEFORE INSERT ON bank.journal
 FOR EACH ROW EXECUTE FUNCTION bank.require_open_business_day();
