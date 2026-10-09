-- A zero customer position must also agree with the general ledger at closure.
CREATE FUNCTION bank.guard_account_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.payload->>'state'='CLOSED' AND NEW.payload IS DISTINCT FROM OLD.payload THEN
  RAISE EXCEPTION 'Closed accounts cannot be changed';
 END IF;
 IF NEW.payload->>'state'='CLOSED' AND OLD.payload->>'state'<>'CLOSED' THEN
  IF EXISTS(SELECT 1 FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id
    WHERE p.account_id=NEW.id AND (p.posted<>0 OR p.held<>0 OR coalesce(l.credit,0)-coalesce(l.debit,0)<>0)) THEN
   RAISE EXCEPTION 'Account closure requires zero reconciled balances and no holds';
  END IF;
  IF EXISTS(SELECT 1 FROM bank.contract WHERE account_id=NEW.id) OR
    coalesce((SELECT carry_after FROM bank.servicing_event WHERE account_id=NEW.id ORDER BY business_date DESC LIMIT 1),0)<>0 THEN
   RAISE EXCEPTION 'Account closure requires contract and servicing settlement';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER account_closure_guard BEFORE UPDATE ON bank.account
 FOR EACH ROW EXECUTE FUNCTION bank.guard_account_closure();

-- Serialize postings against closure, including callers outside the application.
CREATE FUNCTION bank.guard_closed_account_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE aid UUID; account_state TEXT;
BEGIN
 SELECT account_id INTO aid FROM bank.account_ledger WHERE ledger_id=NEW.ledger_id;
 IF aid IS NOT NULL THEN
  SELECT payload->>'state' INTO account_state FROM bank.account WHERE id=aid FOR SHARE;
  IF account_state='CLOSED' THEN RAISE EXCEPTION 'Posting to a closed customer account is forbidden'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER closed_account_posting_guard BEFORE INSERT ON bank.journal_line
 FOR EACH ROW EXECUTE FUNCTION bank.guard_closed_account_posting();
