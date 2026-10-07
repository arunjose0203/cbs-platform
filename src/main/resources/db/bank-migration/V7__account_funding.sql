ALTER TABLE bank.account DROP CONSTRAINT account_payload_check;
ALTER TABLE bank.account ADD CONSTRAINT account_check CHECK(payload->>'state' IN ('AWAITING_FUNDING','ACTIVE','FROZEN','CLOSED'));
CREATE TABLE bank.account_ledger (
 account_id UUID PRIMARY KEY REFERENCES bank.account(id),ledger_id UUID NOT NULL UNIQUE REFERENCES bank.ledger_account(id)
);
CREATE TRIGGER account_ledger_immutable BEFORE UPDATE OR DELETE ON bank.account_ledger
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TABLE bank.funding (
 request_id UUID PRIMARY KEY REFERENCES bank.journal(id),account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),
 amount NUMERIC(28,6) NOT NULL CHECK(amount>0),payload JSONB NOT NULL,
 posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER funding_immutable BEFORE UPDATE OR DELETE ON bank.funding
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
-- A customer-ledger posting must have its matching subledger entry in the same transaction.
CREATE FUNCTION bank.check_customer_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; aid UUID; lid UUID; amt NUMERIC; dr NUMERIC; cr NUMERIC; n INTEGER;
BEGIN
 IF TG_TABLE_NAME='journal_line' THEN
   jid:=NEW.journal_id;
   SELECT account_id INTO aid FROM bank.account_ledger WHERE ledger_id=NEW.ledger_id;
   IF aid IS NULL THEN RETURN NULL; END IF;
 ELSE jid:=NEW.request_id; aid:=NEW.account_id; END IF;
 SELECT b.ledger_id,f.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.funding f ON f.account_id=b.account_id WHERE f.request_id=jid AND f.account_id=aid;
 IF lid IS NULL THEN RAISE EXCEPTION 'Customer subledger entry required'; END IF;
 SELECT count(*),coalesce(sum(amount) FILTER(WHERE ledger_id=lid AND side='CREDIT'),0),coalesce(sum(amount) FILTER(WHERE ledger_id<>lid AND side='DEBIT'),0)
 INTO n,cr,dr FROM bank.journal_line WHERE journal_id=jid;
 IF n<>2 OR cr<>amt OR dr<>amt THEN RAISE EXCEPTION 'Customer funding journal mismatch'; END IF;
 IF NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=jid AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Funding journal is sealed'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER funding_journal_check AFTER INSERT ON bank.funding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_customer_posting();
CREATE CONSTRAINT TRIGGER customer_journal_check AFTER INSERT ON bank.journal_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_customer_posting();
