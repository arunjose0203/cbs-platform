ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF','FEE_ASSESS','FEE_PAY','FEE_WAIVE'));
CREATE TABLE bank.loan_fee_event(id UUID PRIMARY KEY REFERENCES bank.contract_event(id),contract_id UUID NOT NULL REFERENCES bank.contract(id),fee_id UUID NOT NULL,payload JSONB NOT NULL);
CREATE TRIGGER loan_fee_event_immutable BEFORE UPDATE OR DELETE ON bank.loan_fee_event FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.check_loan_fee_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e bank.contract_event; previous JSONB; book NUMERIC; total NUMERIC; amount NUMERIC; original JSONB;
BEGIN
 SELECT * INTO e FROM bank.contract_event WHERE id=NEW.id;
 IF e.kind NOT IN ('FEE_ASSESS','FEE_PAY','FEE_WAIVE') OR e.contract_id<>NEW.contract_id OR e.payload->'feeServicing' IS DISTINCT FROM NEW.payload OR e.write_transaction<>pg_current_xact_id() OR NEW.payload->>'feeId'<>NEW.fee_id::text OR e.payload->'contract'->'terms'->>'type'<>'LOAN' THEN RAISE EXCEPTION 'Loan fee receipt mismatch'; END IF;
 SELECT f.payload INTO previous FROM bank.loan_fee_event f JOIN bank.contract_event x ON x.id=f.id WHERE f.fee_id=NEW.fee_id AND x.event_sequence<e.event_sequence ORDER BY x.event_sequence DESC LIMIT 1;
 amount:=(NEW.payload->'change'->>'amount')::numeric;
 IF amount<=0 OR (NEW.payload->>'after')::numeric<0 OR e.payload->'before'->>'principal' IS DISTINCT FROM e.payload->'after'->>'principal' OR e.payload->'before'->>'interest' IS DISTINCT FROM e.payload->'after'->>'interest' THEN RAISE EXCEPTION 'Invalid fee amount or loan position'; END IF;
 IF e.kind='FEE_ASSESS' THEN
  IF previous IS NOT NULL OR NEW.id<>NEW.fee_id OR (NEW.payload->>'before')::numeric<>0 OR (NEW.payload->>'after')::numeric<>amount OR (NEW.payload->>'dueDate')::date<(e.payload->>'businessDate')::date THEN RAISE EXCEPTION 'Invalid fee assessment'; END IF;
 ELSE
  IF previous IS NULL OR previous->>'after' IS DISTINCT FROM NEW.payload->>'before' OR previous->>'receivableLedger' IS DISTINCT FROM NEW.payload->>'receivableLedger' OR previous->>'incomeLedger' IS DISTINCT FROM NEW.payload->>'incomeLedger' OR previous->>'dueDate' IS DISTINCT FROM NEW.payload->>'dueDate' OR (NEW.payload->>'after')::numeric<>(NEW.payload->>'before')::numeric-amount THEN RAISE EXCEPTION 'Fee position changed'; END IF;
 END IF;
 IF (e.payload->>'customerMovement')::numeric<>(CASE WHEN e.kind='FEE_PAY' THEN -amount ELSE 0 END) THEN RAISE EXCEPTION 'Fee customer amount mismatch'; END IF;
 SELECT f.payload INTO original FROM bank.loan_fee_event f JOIN bank.contract_event x ON x.id=f.id WHERE f.contract_id=NEW.contract_id ORDER BY x.event_sequence LIMIT 1;
 IF original->>'receivableLedger' IS DISTINCT FROM NEW.payload->>'receivableLedger' OR original->>'incomeLedger' IS DISTINCT FROM NEW.payload->>'incomeLedger' THEN RAISE EXCEPTION 'Fee book changed'; END IF;
 SELECT sum((v.payload->>'after')::numeric) INTO total FROM (SELECT DISTINCT ON (f.fee_id) f.payload FROM bank.loan_fee_event f JOIN bank.contract_event x ON x.id=f.id WHERE f.contract_id=NEW.contract_id ORDER BY f.fee_id,x.event_sequence DESC) v;
 SELECT coalesce(debit-credit,0) INTO book FROM bank.ledger_balance WHERE ledger_id=(NEW.payload->>'receivableLedger')::uuid;
 IF coalesce(book,0)<>coalesce(total,0) THEN RAISE EXCEPTION 'Fee ledger does not reconcile'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER loan_fee_checked AFTER INSERT ON bank.loan_fee_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_loan_fee_event();
CREATE FUNCTION bank.require_loan_fee_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE total NUMERIC;
BEGIN
 IF NEW.kind IN ('FEE_ASSESS','FEE_PAY','FEE_WAIVE') AND NOT EXISTS(SELECT 1 FROM bank.loan_fee_event WHERE id=NEW.id) THEN RAISE EXCEPTION 'Loan fee receipt required'; END IF;
 SELECT sum((v.payload->>'after')::numeric) INTO total FROM (SELECT DISTINCT ON (f.fee_id) f.payload FROM bank.loan_fee_event f JOIN bank.contract_event x ON x.id=f.id WHERE f.contract_id=NEW.contract_id AND x.event_sequence<=NEW.event_sequence ORDER BY f.fee_id,x.event_sequence DESC) v;
 IF coalesce(total,0)>0 AND ((NEW.payload->'after'->>'closed')::boolean OR NEW.kind='RESTRUCTURE') THEN RAISE EXCEPTION 'Outstanding loan fees must be settled'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER loan_fee_required AFTER INSERT ON bank.contract_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.require_loan_fee_event();
CREATE FUNCTION bank.guard_loan_fee_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bank.loan_fee_event WHERE payload->>'receivableLedger'=NEW.ledger_id::text)
 AND NOT EXISTS(SELECT 1 FROM bank.loan_fee_event f JOIN bank.contract_event e ON e.id=f.id WHERE e.journal_id=NEW.journal_id AND e.write_transaction=pg_current_xact_id() AND f.payload->>'receivableLedger'=NEW.ledger_id::text) THEN RAISE EXCEPTION 'Dedicated loan fee ledger requires atomic fee event'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER loan_fee_journal_managed AFTER INSERT ON bank.journal_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.guard_loan_fee_journal();
