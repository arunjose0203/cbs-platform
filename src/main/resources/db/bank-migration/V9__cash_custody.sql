CREATE TABLE bank.cash_location (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,branch_id UUID NOT NULL,ledger_id UUID NOT NULL UNIQUE REFERENCES bank.ledger_account(id),
 code VARCHAR(32) NOT NULL UNIQUE,name VARCHAR(160) NOT NULL,currency VARCHAR(3) NOT NULL,kind VARCHAR(8) NOT NULL CHECK(kind IN ('VAULT','TILL')),
 legal_entity_id UUID NOT NULL,payload JSONB NOT NULL,session_open BOOLEAN NOT NULL DEFAULT false,custodian VARCHAR(255),session_date DATE,counted_date DATE
);
CREATE TABLE bank.cash_receipt (
 request_id UUID PRIMARY KEY,kind VARCHAR(12) NOT NULL CHECK(kind IN ('REGISTER','RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL','OPEN','CLOSE','COUNT','DEPOSIT','WITHDRAWAL')),
 location_id UUID NOT NULL REFERENCES bank.cash_location(id),destination_id UUID REFERENCES bank.cash_location(id),
 journal_id UUID UNIQUE REFERENCES bank.journal(id),amount NUMERIC(24,6),payload JSONB NOT NULL,
 maker_subject VARCHAR(255) NOT NULL,reviewer_subject VARCHAR(255) NOT NULL,posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((kind IN ('RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL'))=(journal_id IS NOT NULL)),CHECK((kind='TRANSFER')=(destination_id IS NOT NULL))
);
CREATE TABLE bank.cash_entry (
 request_id UUID NOT NULL REFERENCES bank.cash_receipt(request_id),location_id UUID NOT NULL REFERENCES bank.cash_location(id),
 amount NUMERIC(24,6) NOT NULL CHECK(amount<>0),PRIMARY KEY(request_id,location_id)
);
CREATE TRIGGER cash_receipt_immutable BEFORE UPDATE OR DELETE ON bank.cash_receipt FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER cash_entry_immutable BEFORE UPDATE OR DELETE ON bank.cash_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE VIEW bank.cash_position AS SELECT l.id,coalesce(sum(e.amount),0) balance FROM bank.cash_location l LEFT JOIN bank.cash_entry e ON e.location_id=l.id GROUP BY l.id;
CREATE FUNCTION bank.check_cash_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; loc UUID; lid UUID; delta NUMERIC; total NUMERIC; book NUMERIC;
BEGIN
 IF TG_TABLE_NAME='journal_line' THEN
  jid:=NEW.journal_id; SELECT id INTO loc FROM bank.cash_location WHERE ledger_id=NEW.ledger_id;
  IF loc IS NULL THEN RETURN NULL; END IF;
 ELSE jid:=NEW.request_id;loc:=NEW.location_id; END IF;
 SELECT ledger_id INTO lid FROM bank.cash_location WHERE id=loc;
 SELECT e.amount INTO delta FROM bank.cash_entry e WHERE e.request_id=jid AND e.location_id=loc;
 IF delta IS NULL OR NOT EXISTS(SELECT 1 FROM bank.cash_receipt WHERE request_id=jid AND journal_id=jid) THEN RAISE EXCEPTION 'Cash receipt required'; END IF;
 SELECT coalesce(sum(CASE WHEN side='DEBIT' THEN amount ELSE -amount END),0) INTO total FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
 IF total<>delta OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=jid AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Cash journal mismatch'; END IF;
 SELECT balance INTO total FROM bank.cash_position WHERE id=loc;
 SELECT coalesce(debit-credit,0) INTO book FROM bank.ledger_balance WHERE ledger_id=lid;
 IF total<0 OR total<>coalesce(book,0) THEN RAISE EXCEPTION 'Cash position mismatch'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER cash_journal_check AFTER INSERT ON bank.journal_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_cash_posting();
CREATE CONSTRAINT TRIGGER cash_entry_check AFTER INSERT ON bank.cash_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_cash_posting();
CREATE FUNCTION bank.check_cash_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n INTEGER; delta NUMERIC;
BEGIN
 SELECT count(*),coalesce(sum(amount),0) INTO n,delta FROM bank.cash_entry WHERE request_id=NEW.request_id;
 IF NEW.journal_id IS NULL THEN
  IF n<>0 THEN RAISE EXCEPTION 'Unexpected cash entries'; END IF;
 ELSE
  IF NEW.kind='DEPOSIT' AND NOT EXISTS(SELECT 1 FROM bank.funding WHERE request_id=NEW.request_id AND account_id=(NEW.payload->'change'->>'accountId')::uuid AND amount=NEW.amount) THEN RAISE EXCEPTION 'Customer cash credit missing'; END IF;
  IF NEW.kind='WITHDRAWAL' AND NOT EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=NEW.request_id AND kind='WITHDRAWAL' AND account_id=(NEW.payload->'change'->>'accountId')::uuid AND amount=NEW.amount) THEN RAISE EXCEPTION 'Customer cash debit missing'; END IF;
  IF NEW.journal_id<>NEW.request_id OR NEW.amount IS NULL OR NEW.amount<=0 THEN RAISE EXCEPTION 'Invalid cash receipt'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bank.cash_entry WHERE request_id=NEW.request_id AND location_id=NEW.location_id AND amount=CASE WHEN NEW.kind IN ('RECEIVE','DEPOSIT') THEN NEW.amount ELSE -NEW.amount END) THEN RAISE EXCEPTION 'Missing cash entry'; END IF;
  IF NEW.kind='TRANSFER' THEN
   IF n<>2 OR delta<>0 OR NOT EXISTS(SELECT 1 FROM bank.cash_entry WHERE request_id=NEW.request_id AND location_id=NEW.destination_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Invalid cash transfer'; END IF;
  ELSIF n<>1 THEN RAISE EXCEPTION 'Unexpected cash entries'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER cash_receipt_integrity AFTER INSERT ON bank.cash_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_cash_receipt();
