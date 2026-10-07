CREATE TABLE bank.journal (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 reference VARCHAR(80) NOT NULL UNIQUE,
 business_date DATE NOT NULL,
 legal_entity_id UUID NOT NULL,
 currency CHAR(3) NOT NULL,
 reversal_of UUID UNIQUE REFERENCES bank.journal(id),
 payload JSONB NOT NULL,
 maker_subject VARCHAR(200) NOT NULL,
 reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(200) NOT NULL CHECK(reviewer_subject<>maker_subject),
 correlation_id UUID NOT NULL,
 posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 write_transaction XID8 NOT NULL DEFAULT pg_current_xact_id()
);
CREATE TABLE bank.journal_line (
 journal_id UUID NOT NULL REFERENCES bank.journal(id),
 line_number INTEGER NOT NULL,
 ledger_id UUID NOT NULL REFERENCES bank.ledger_account(id),
 side VARCHAR(6) NOT NULL CHECK(side IN ('DEBIT','CREDIT')),
 amount NUMERIC(28,6) NOT NULL CHECK(amount>0),
 PRIMARY KEY(journal_id,line_number)
);
CREATE INDEX journal_line_ledger_idx ON bank.journal_line(ledger_id,journal_id);
CREATE TABLE bank.ledger_balance (
 ledger_id UUID PRIMARY KEY REFERENCES bank.ledger_account(id),
 debit NUMERIC(38,6) NOT NULL DEFAULT 0 CHECK(debit>=0),
 credit NUMERIC(38,6) NOT NULL DEFAULT 0 CHECK(credit>=0)
);
CREATE TRIGGER journal_immutable BEFORE UPDATE OR DELETE ON bank.journal
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER journal_line_immutable BEFORE UPDATE OR DELETE ON bank.journal_line
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.apply_journal_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.journal_id AND write_transaction=pg_current_xact_id())
 THEN RAISE EXCEPTION 'Posted journal is sealed'; END IF;
 PERFORM id FROM bank.ledger_account WHERE id=NEW.ledger_id AND active FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Inactive ledger'; END IF;
 INSERT INTO bank.ledger_balance(ledger_id,debit,credit)
 VALUES(NEW.ledger_id,CASE WHEN NEW.side='DEBIT' THEN NEW.amount ELSE 0 END,CASE WHEN NEW.side='CREDIT' THEN NEW.amount ELSE 0 END)
 ON CONFLICT(ledger_id) DO UPDATE SET debit=bank.ledger_balance.debit+EXCLUDED.debit,credit=bank.ledger_balance.credit+EXCLUDED.credit;
 RETURN NEW;
END; $$;
CREATE TRIGGER journal_line_balance BEFORE INSERT ON bank.journal_line
 FOR EACH ROW EXECUTE FUNCTION bank.apply_journal_line();
-- Deferred checks also reject partial or unbalanced SQL writes at commit time.
CREATE FUNCTION bank.check_journal_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; dr NUMERIC; cr NUMERIC; n INTEGER;
BEGIN
 IF TG_TABLE_NAME='journal' THEN jid := NEW.id; ELSE jid := NEW.journal_id; END IF;
 SELECT count(*),coalesce(sum(amount) FILTER(WHERE side='DEBIT'),0),coalesce(sum(amount) FILTER(WHERE side='CREDIT'),0)
 INTO n,dr,cr FROM bank.journal_line WHERE journal_id=jid;
 IF n<2 OR dr<>cr THEN RAISE EXCEPTION 'Unbalanced journal'; END IF;
 IF EXISTS(SELECT 1 FROM bank.journal_line l JOIN bank.journal j ON j.id=l.journal_id
 JOIN bank.ledger_account a ON a.id=l.ledger_id WHERE j.id=jid
 AND (a.tenant_id<>j.tenant_id OR a.legal_entity_id<>j.legal_entity_id OR a.currency<>j.currency))
 THEN RAISE EXCEPTION 'Journal scope mismatch'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER journal_balanced AFTER INSERT ON bank.journal
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_journal_balance();
CREATE CONSTRAINT TRIGGER journal_line_balanced AFTER INSERT ON bank.journal_line
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_journal_balance();
