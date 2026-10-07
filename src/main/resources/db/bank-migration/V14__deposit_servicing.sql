CREATE TABLE bank.servicing_run (
 request_id UUID PRIMARY KEY,business_date DATE NOT NULL UNIQUE,next_business_date DATE NOT NULL,payload JSONB NOT NULL,
 CHECK(next_business_date>business_date)
);
CREATE TABLE bank.servicing_event (
 request_id UUID PRIMARY KEY,run_id UUID NOT NULL REFERENCES bank.servicing_run(request_id),account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),
 business_date DATE NOT NULL,opening_balance NUMERIC(28,6) NOT NULL,net_amount NUMERIC(28,6) NOT NULL,carry_after NUMERIC(38,18) NOT NULL,
 journal_id UUID UNIQUE REFERENCES bank.journal(id),payload JSONB NOT NULL,UNIQUE(account_id,business_date)
);
CREATE TRIGGER servicing_run_immutable BEFORE UPDATE OR DELETE ON bank.servicing_run FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER servicing_event_immutable BEFORE UPDATE OR DELETE ON bank.servicing_event FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE OR REPLACE VIEW bank.customer_position AS
 SELECT b.account_id,b.ledger_id,
 coalesce((SELECT sum(f.amount) FROM bank.funding f WHERE f.account_id=b.account_id),0)
 +coalesce((SELECT sum(e.amount) FROM bank.customer_entry e WHERE e.account_id=b.account_id),0)
 +coalesce((SELECT sum(s.net_amount) FROM bank.servicing_event s WHERE s.account_id=b.account_id),0) posted,
 coalesce((SELECT sum(h.amount) FROM bank.account_operation h WHERE h.account_id=b.account_id AND h.kind='HOLD'
 AND NOT EXISTS(SELECT 1 FROM bank.account_operation r WHERE r.kind='RELEASE' AND r.original_id=h.request_id)),0) held
 FROM bank.account_ledger b;
CREATE OR REPLACE FUNCTION bank.check_customer_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; aid UUID; lid UUID; amt NUMERIC; dr NUMERIC; cr NUMERIC; n INTEGER;
BEGIN
 IF TG_TABLE_NAME='journal_line' THEN
  jid:=NEW.journal_id; SELECT account_id INTO aid FROM bank.account_ledger WHERE ledger_id=NEW.ledger_id;
  IF aid IS NULL THEN RETURN NULL; END IF;
 ELSE jid:=NEW.request_id;aid:=NEW.account_id; END IF;
 IF EXISTS(SELECT 1 FROM bank.servicing_event WHERE request_id=jid AND account_id=aid) THEN
  SELECT b.ledger_id,e.net_amount INTO lid,amt FROM bank.account_ledger b JOIN bank.servicing_event e ON e.account_id=b.account_id WHERE e.request_id=jid AND e.account_id=aid;
  SELECT coalesce(sum(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0) INTO cr FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
  IF cr<>amt THEN RAISE EXCEPTION 'Servicing customer journal mismatch'; END IF;
 ELSIF EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=jid) THEN
  SELECT b.ledger_id,e.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.customer_entry e ON e.account_id=b.account_id WHERE e.request_id=jid AND e.account_id=aid;
  IF lid IS NULL THEN RAISE EXCEPTION 'Customer entry required'; END IF;
  SELECT coalesce(sum(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0) INTO cr FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
  IF cr<>amt THEN RAISE EXCEPTION 'Customer entry does not match journal'; END IF;
 ELSE
  SELECT b.ledger_id,f.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.funding f ON f.account_id=b.account_id WHERE f.request_id=jid AND f.account_id=aid;
  IF lid IS NULL THEN RAISE EXCEPTION 'Customer subledger entry required'; END IF;
  SELECT count(*),coalesce(sum(amount) FILTER(WHERE ledger_id=lid AND side='CREDIT'),0),coalesce(sum(amount) FILTER(WHERE ledger_id<>lid AND side='DEBIT'),0) INTO n,cr,dr FROM bank.journal_line WHERE journal_id=jid;
  IF n<>2 OR cr<>amt OR dr<>amt THEN RAISE EXCEPTION 'Customer funding journal mismatch'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=jid AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Customer journal is sealed'; END IF;
 RETURN NULL;
END; $$;
CREATE FUNCTION bank.check_servicing_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p RECORD; book NUMERIC;
BEGIN
 SELECT * INTO p FROM bank.customer_position WHERE account_id=NEW.account_id;
 SELECT coalesce(credit-debit,0) INTO book FROM bank.ledger_balance WHERE ledger_id=p.ledger_id;
 IF p.posted<0 OR p.held>p.posted OR p.posted<>coalesce(book,0) OR p.posted<>NEW.opening_balance+NEW.net_amount THEN RAISE EXCEPTION 'Servicing balance mismatch'; END IF;
 IF NEW.journal_id IS NULL THEN
  IF NEW.net_amount<>0 THEN RAISE EXCEPTION 'Servicing journal required'; END IF;
 ELSE
  IF NEW.journal_id<>NEW.request_id OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.journal_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Servicing journal mismatch'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER servicing_event_check AFTER INSERT ON bank.servicing_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_servicing_event();

CREATE OR REPLACE FUNCTION bank.require_open_business_day() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutoff DATE;
BEGIN
 SELECT closed_through INTO cutoff FROM bank.business_day_state WHERE singleton FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Business day state missing'; END IF;
 IF cutoff IS NOT NULL AND NEW.business_date<=cutoff THEN RAISE EXCEPTION 'Business day is closed'; END IF;
 IF EXISTS(SELECT 1 FROM bank.servicing_run r WHERE r.business_date=NEW.business_date AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.payload->'items') i WHERE (i->>'id')::uuid=NEW.id)) THEN RAISE EXCEPTION 'Daily servicing has finalized this day'; END IF;
 RETURN NEW;
END; $$;
