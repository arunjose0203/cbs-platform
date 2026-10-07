CREATE TABLE bank.account_operation (
 request_id UUID PRIMARY KEY,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','RELEASE')),
 account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),
 destination_id UUID REFERENCES bank.account_ledger(account_id),
 amount NUMERIC(28,6) NOT NULL CHECK(amount>0),
 original_id UUID UNIQUE,
 journal_id UUID UNIQUE REFERENCES bank.journal(id),
 payload JSONB NOT NULL,
 posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((kind IN ('HOLD','RELEASE')) = (journal_id IS NULL)),
 CHECK((kind='TRANSFER') = (destination_id IS NOT NULL)),
 CHECK((kind IN ('FUNDING_REVERSAL','RELEASE')) = (original_id IS NOT NULL)),
 CHECK(destination_id IS NULL OR destination_id<>account_id)
);
CREATE TABLE bank.customer_entry (
 request_id UUID NOT NULL REFERENCES bank.account_operation(request_id),
 account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),
 amount NUMERIC(28,6) NOT NULL CHECK(amount<>0),
 PRIMARY KEY(request_id,account_id)
);
CREATE TRIGGER account_operation_immutable BEFORE UPDATE OR DELETE ON bank.account_operation FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER customer_entry_immutable BEFORE UPDATE OR DELETE ON bank.customer_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE VIEW bank.customer_position AS
 SELECT b.account_id,b.ledger_id,
 coalesce((SELECT sum(f.amount) FROM bank.funding f WHERE f.account_id=b.account_id),0)
 +coalesce((SELECT sum(e.amount) FROM bank.customer_entry e WHERE e.account_id=b.account_id),0) posted,
 coalesce((SELECT sum(h.amount) FROM bank.account_operation h WHERE h.account_id=b.account_id AND h.kind='HOLD'
 AND NOT EXISTS(SELECT 1 FROM bank.account_operation r WHERE r.kind='RELEASE' AND r.original_id=h.request_id)),0) held
 FROM bank.account_ledger b;
-- The legacy funding constraint now also accepts matched immutable debit/transfer receipts.
CREATE OR REPLACE FUNCTION bank.check_customer_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; aid UUID; lid UUID; amt NUMERIC; dr NUMERIC; cr NUMERIC; n INTEGER;
BEGIN
 IF TG_TABLE_NAME='journal_line' THEN
   jid:=NEW.journal_id; SELECT account_id INTO aid FROM bank.account_ledger WHERE ledger_id=NEW.ledger_id;
   IF aid IS NULL THEN RETURN NULL; END IF;
 ELSIF TG_TABLE_NAME='customer_entry' THEN
   jid:=NEW.request_id; aid:=NEW.account_id;
 ELSE jid:=NEW.request_id; aid:=NEW.account_id; END IF;
 IF EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=jid) THEN
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
CREATE CONSTRAINT TRIGGER customer_entry_journal_check AFTER INSERT ON bank.customer_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_customer_posting();
CREATE FUNCTION bank.check_account_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pos RECORD; source RECORD; n INTEGER; delta NUMERIC;
BEGIN
 SELECT * INTO pos FROM bank.customer_position WHERE account_id=NEW.account_id;
 IF pos.posted<0 OR pos.held<0 OR pos.held>pos.posted THEN RAISE EXCEPTION 'Insufficient customer funds'; END IF;
 IF NEW.kind='RELEASE' THEN
   SELECT * INTO source FROM bank.account_operation WHERE request_id=NEW.original_id AND kind='HOLD' AND account_id=NEW.account_id;
   IF source IS NULL OR source.amount<>NEW.amount THEN RAISE EXCEPTION 'Invalid hold release'; END IF;
 ELSIF NEW.kind='FUNDING_REVERSAL' THEN
   IF NOT EXISTS(SELECT 1 FROM bank.funding WHERE request_id=NEW.original_id AND account_id=NEW.account_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Invalid funding reversal'; END IF;
 END IF;
 SELECT count(*),coalesce(sum(amount),0) INTO n,delta FROM bank.customer_entry WHERE request_id=NEW.request_id;
 IF NEW.journal_id IS NULL THEN
   IF n<>0 THEN RAISE EXCEPTION 'Hold cannot post ledger entries'; END IF;
 ELSE
   IF NEW.journal_id<>NEW.request_id OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.request_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Operation journal is sealed'; END IF;
   IF NOT EXISTS(SELECT 1 FROM bank.customer_entry WHERE request_id=NEW.request_id AND account_id=NEW.account_id AND amount=-NEW.amount) THEN RAISE EXCEPTION 'Customer debit required'; END IF;
   IF NEW.kind='TRANSFER' THEN
     IF n<>2 OR delta<>0 OR NOT EXISTS(SELECT 1 FROM bank.customer_entry WHERE request_id=NEW.request_id AND account_id=NEW.destination_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Transfer credit required'; END IF;
   ELSIF n<>1 THEN RAISE EXCEPTION 'Unexpected customer entries'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER operation_receipt_integrity AFTER INSERT ON bank.account_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_account_operation();

CREATE INDEX customer_entry_account ON bank.customer_entry(account_id,request_id);
CREATE INDEX funding_account ON bank.funding(account_id,request_id);
CREATE INDEX account_operation_holds ON bank.account_operation(account_id,request_id) WHERE kind='HOLD';
