ALTER TABLE bank.cash_receipt DROP CONSTRAINT cash_receipt_kind_check;
ALTER TABLE bank.cash_receipt ADD CHECK(kind IN ('REGISTER','RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL','OPEN','CLOSE','COUNT','SHORTAGE','OVERAGE'));
ALTER TABLE bank.cash_receipt DROP CONSTRAINT cash_receipt_check;
ALTER TABLE bank.cash_receipt ADD CHECK((kind IN ('RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL','SHORTAGE','OVERAGE'))=(journal_id IS NOT NULL));
ALTER TABLE bank.cash_receipt ADD COLUMN exception_id UUID UNIQUE;
ALTER TABLE bank.cash_receipt ADD CHECK((kind IN ('SHORTAGE','OVERAGE'))=(exception_id IS NOT NULL));
CREATE OR REPLACE FUNCTION bank.check_cash_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n INTEGER; delta NUMERIC;
BEGIN
 SELECT count(*),coalesce(sum(amount),0) INTO n,delta FROM bank.cash_entry WHERE request_id=NEW.request_id;
 IF NEW.journal_id IS NULL THEN
  IF n<>0 THEN RAISE EXCEPTION 'Unexpected cash entries'; END IF;
 ELSE
  IF NEW.kind='DEPOSIT' AND NOT EXISTS(SELECT 1 FROM bank.funding WHERE request_id=NEW.request_id AND account_id=(NEW.payload->'change'->>'accountId')::uuid AND amount=NEW.amount) THEN RAISE EXCEPTION 'Customer cash credit missing'; END IF;
  IF NEW.kind='WITHDRAWAL' AND NOT EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=NEW.request_id AND kind='WITHDRAWAL' AND account_id=(NEW.payload->'change'->>'accountId')::uuid AND amount=NEW.amount) THEN RAISE EXCEPTION 'Customer cash debit missing'; END IF;
  IF NEW.journal_id<>NEW.request_id OR NEW.amount IS NULL OR NEW.amount<=0 THEN RAISE EXCEPTION 'Invalid cash receipt'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bank.cash_entry WHERE request_id=NEW.request_id AND location_id=NEW.location_id AND amount=CASE WHEN NEW.kind IN ('RECEIVE','DEPOSIT','OVERAGE') THEN NEW.amount ELSE -NEW.amount END) THEN RAISE EXCEPTION 'Missing cash entry'; END IF;
  IF NEW.kind='TRANSFER' THEN
   IF n<>2 OR delta<>0 OR NOT EXISTS(SELECT 1 FROM bank.cash_entry WHERE request_id=NEW.request_id AND location_id=NEW.destination_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Invalid cash transfer'; END IF;
  ELSIF n<>1 THEN RAISE EXCEPTION 'Unexpected cash entries'; END IF;
 END IF;
 IF NEW.kind IN ('SHORTAGE','OVERAGE') THEN
  IF NEW.exception_id IS DISTINCT FROM (NEW.payload->'change'->>'exceptionId')::uuid
     OR NEW.maker_subject=NEW.reviewer_subject THEN RAISE EXCEPTION 'Invalid adjustment evidence'; END IF;
  IF jsonb_typeof(NEW.payload->'change'->'counts') IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'Adjustment count required'; END IF;
  IF (SELECT balance FROM bank.cash_position WHERE id=NEW.location_id) IS DISTINCT FROM
     (SELECT coalesce(sum((v->>'denomination')::numeric*(v->>'quantity')::numeric),0) FROM jsonb_array_elements(NEW.payload->'change'->'counts') v)
  THEN RAISE EXCEPTION 'Adjusted cash differs from count'; END IF;
 END IF;
 RETURN NULL;
END; $$;
