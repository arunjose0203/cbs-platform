-- Round 12: Deposit lifecycle completion (dormancy, closure with contracts/payments, closure settlement reversal)
ALTER TABLE bank.account DROP CONSTRAINT account_check;
ALTER TABLE bank.account ADD CONSTRAINT account_check CHECK(payload->>'state' IN ('AWAITING_FUNDING','ACTIVE','FROZEN','DORMANT','CLOSED'));

ALTER TABLE bank.account_operation DROP CONSTRAINT account_operation_kind_check;
ALTER TABLE bank.account_operation ADD CHECK(kind IN ('TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','HOLD','RELEASE','CLOSURE_SETTLEMENT','CLOSURE_SETTLEMENT_REVERSAL'));

ALTER TABLE bank.account_operation DROP CONSTRAINT account_operation_check2;
ALTER TABLE bank.account_operation ADD CHECK((kind IN ('FUNDING_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','RELEASE','CLOSURE_SETTLEMENT_REVERSAL'))=(original_id IS NOT NULL));

CREATE TABLE bank.closure_settlement_reversal (
 request_id UUID PRIMARY KEY REFERENCES bank.account_operation(request_id),
 original_id UUID NOT NULL UNIQUE REFERENCES bank.closure_settlement(request_id),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER closure_settlement_reversal_immutable BEFORE UPDATE OR DELETE ON bank.closure_settlement_reversal
 FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();

CREATE OR REPLACE FUNCTION bank.check_account_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pos RECORD; source RECORD; n INTEGER; delta NUMERIC; carry NUMERIC;
BEGIN
 SELECT * INTO pos FROM bank.customer_position WHERE account_id=NEW.account_id;
 IF pos.posted<0 OR pos.held<0 OR pos.held>pos.posted THEN RAISE EXCEPTION 'Insufficient customer funds'; END IF;
 IF NEW.kind IN ('WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','CLOSURE_SETTLEMENT_REVERSAL') THEN
   SELECT * INTO source FROM bank.account_operation WHERE request_id=NEW.original_id;
   IF source IS NULL OR source.amount<>NEW.amount OR
      (NEW.kind='WITHDRAWAL_REVERSAL' AND (source.kind<>'WITHDRAWAL' OR source.account_id<>NEW.account_id)) OR
      (NEW.kind='CLOSURE_SETTLEMENT_REVERSAL' AND (source.kind<>'CLOSURE_SETTLEMENT' OR source.account_id<>NEW.account_id)) OR
      (NEW.kind='TRANSFER_REVERSAL' AND (source.kind<>'TRANSFER' OR source.destination_id<>NEW.account_id OR source.account_id<>NEW.destination_id))
   THEN RAISE EXCEPTION 'Invalid customer reversal'; END IF;
 END IF;
 IF NEW.kind IN ('FUNDING_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','CLOSURE_SETTLEMENT_REVERSAL') AND NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.request_id AND reversal_of=NEW.original_id) THEN RAISE EXCEPTION 'Reversal journal required'; END IF;
 IF NEW.kind='RELEASE' THEN
   SELECT * INTO source FROM bank.account_operation WHERE request_id=NEW.original_id AND kind='HOLD' AND account_id=NEW.account_id;
   IF source IS NULL OR source.amount<>NEW.amount THEN RAISE EXCEPTION 'Invalid hold release'; END IF;
 ELSIF NEW.kind='FUNDING_REVERSAL' THEN
   IF NOT EXISTS(SELECT 1 FROM bank.funding WHERE request_id=NEW.original_id AND account_id=NEW.account_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Invalid funding reversal'; END IF;
 ELSIF NEW.kind='CLOSURE_SETTLEMENT' THEN
   carry:=coalesce((SELECT carry_after FROM bank.servicing_event WHERE account_id=NEW.account_id ORDER BY business_date DESC LIMIT 1),0);
   IF pos.posted<>0 OR pos.held<>0 OR NOT EXISTS(SELECT 1 FROM bank.closure_settlement WHERE request_id=NEW.request_id AND account_id=NEW.account_id AND amount=NEW.amount AND forfeited_carry=carry) THEN
     RAISE EXCEPTION 'Closure settlement must pay out the full balance';
   END IF;
 END IF;
 SELECT count(*),coalesce(sum(amount),0) INTO n,delta FROM bank.customer_entry WHERE request_id=NEW.request_id;
 IF NEW.journal_id IS NULL THEN
   IF n<>0 OR NEW.kind IN ('CLOSURE_SETTLEMENT','CLOSURE_SETTLEMENT_REVERSAL') THEN RAISE EXCEPTION 'Hold cannot post ledger entries'; END IF;
 ELSE
   IF NEW.journal_id<>NEW.request_id OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.request_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Operation journal is sealed'; END IF;
   IF NOT EXISTS(SELECT 1 FROM bank.customer_entry WHERE request_id=NEW.request_id AND account_id=NEW.account_id AND amount=CASE WHEN NEW.kind IN ('WITHDRAWAL_REVERSAL','CLOSURE_SETTLEMENT_REVERSAL') THEN NEW.amount ELSE -NEW.amount END) THEN RAISE EXCEPTION 'Customer debit required'; END IF;
   IF NEW.kind IN ('TRANSFER','TRANSFER_REVERSAL') THEN
     IF n<>2 OR delta<>0 OR NOT EXISTS(SELECT 1 FROM bank.customer_entry WHERE request_id=NEW.request_id AND account_id=NEW.destination_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Transfer credit required'; END IF;
   ELSIF n<>1 THEN RAISE EXCEPTION 'Unexpected customer entries'; END IF;
 END IF;
 RETURN NULL;
END; $$;

CREATE OR REPLACE FUNCTION bank.guard_account_closure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE carry NUMERIC;
BEGIN
 IF OLD.payload->>'state'='CLOSED' AND NEW.payload IS DISTINCT FROM OLD.payload THEN
  RAISE EXCEPTION 'Closed accounts cannot be changed';
 END IF;
 IF NEW.payload->>'state'='CLOSED' AND OLD.payload->>'state'<>'CLOSED' THEN
  IF EXISTS(SELECT 1 FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id
    WHERE p.account_id=NEW.id AND (p.posted<>0 OR p.held<>0 OR coalesce(l.credit,0)-coalesce(l.debit,0)<>0)) THEN
   RAISE EXCEPTION 'Account closure requires zero reconciled balances and no holds';
  END IF;
  carry:=coalesce((SELECT carry_after FROM bank.servicing_event WHERE account_id=NEW.id ORDER BY business_date DESC LIMIT 1),0);
  IF EXISTS(
    SELECT 1 FROM bank.contract c
    LEFT JOIN LATERAL (SELECT payload->'after' p FROM bank.contract_event e WHERE e.contract_id=c.id ORDER BY event_sequence DESC LIMIT 1) x ON true
    WHERE c.account_id=NEW.id AND (x.p IS NULL OR NOT (x.p->>'closed')::boolean OR (x.p->>'principal')::numeric<>0 OR (x.p->>'accruedInterest')::numeric<>0)
  ) OR
    (carry<>0 AND NOT EXISTS(
      SELECT 1 FROM bank.closure_settlement s
      WHERE s.account_id=NEW.id AND s.forfeited_carry=carry
      AND NOT EXISTS(SELECT 1 FROM bank.closure_settlement_reversal r WHERE r.original_id=s.request_id)
    )) THEN
   RAISE EXCEPTION 'Account closure requires contract and servicing settlement';
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION bank.guard_closed_account_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE aid UUID; account_state TEXT;
BEGIN
 SELECT account_id INTO aid FROM bank.account_ledger WHERE ledger_id=NEW.ledger_id;
 IF aid IS NOT NULL THEN
  SELECT payload->>'state' INTO account_state FROM bank.account WHERE id=aid FOR SHARE;
  IF account_state='CLOSED' THEN RAISE EXCEPTION 'Posting to a closed customer account is forbidden'; END IF;
  IF EXISTS(
    SELECT 1 FROM bank.closure_settlement s
    WHERE s.account_id=aid AND s.request_id<>NEW.journal_id
    AND NOT EXISTS(SELECT 1 FROM bank.closure_settlement_reversal r WHERE r.original_id=s.request_id)
  ) THEN
   RAISE EXCEPTION 'Account is pending closure after final settlement';
  END IF;
 END IF;
 RETURN NEW;
END $$;
