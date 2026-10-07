ALTER TABLE bank.account_operation DROP CONSTRAINT account_operation_kind_check;
ALTER TABLE bank.account_operation ADD CHECK(kind IN ('TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','HOLD','RELEASE'));
ALTER TABLE bank.account_operation DROP CONSTRAINT account_operation_check1;
ALTER TABLE bank.account_operation ADD CHECK((kind IN ('TRANSFER','TRANSFER_REVERSAL'))=(destination_id IS NOT NULL));
ALTER TABLE bank.account_operation DROP CONSTRAINT account_operation_check2;
ALTER TABLE bank.account_operation ADD CHECK((kind IN ('FUNDING_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','RELEASE'))=(original_id IS NOT NULL));
ALTER TABLE bank.cash_receipt DROP CONSTRAINT cash_receipt_kind_check;
ALTER TABLE bank.cash_receipt ADD CHECK(kind IN ('REGISTER','RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL','OPEN','CLOSE','COUNT','SHORTAGE','OVERAGE','REVERSAL'));
ALTER TABLE bank.cash_receipt DROP CONSTRAINT cash_receipt_check;
ALTER TABLE bank.cash_receipt ADD CHECK((kind IN ('RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL','SHORTAGE','OVERAGE','REVERSAL'))=(journal_id IS NOT NULL));
ALTER TABLE bank.cash_receipt DROP CONSTRAINT cash_receipt_check1;
ALTER TABLE bank.cash_receipt ADD CHECK(kind='REVERSAL' OR (kind='TRANSFER')=(destination_id IS NOT NULL));
ALTER TABLE bank.cash_receipt ADD COLUMN original_id UUID UNIQUE REFERENCES bank.cash_receipt(request_id);
ALTER TABLE bank.cash_receipt ADD CHECK((kind='REVERSAL')=(original_id IS NOT NULL));
CREATE OR REPLACE FUNCTION bank.check_account_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pos RECORD; source RECORD; n INTEGER; delta NUMERIC;
BEGIN
 SELECT * INTO pos FROM bank.customer_position WHERE account_id=NEW.account_id;
 IF pos.posted<0 OR pos.held<0 OR pos.held>pos.posted THEN RAISE EXCEPTION 'Insufficient customer funds'; END IF;
 IF NEW.kind IN ('WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL') THEN
   SELECT * INTO source FROM bank.account_operation WHERE request_id=NEW.original_id;
   IF source IS NULL OR source.amount<>NEW.amount OR
      (NEW.kind='WITHDRAWAL_REVERSAL' AND (source.kind<>'WITHDRAWAL' OR source.account_id<>NEW.account_id)) OR
      (NEW.kind='TRANSFER_REVERSAL' AND (source.kind<>'TRANSFER' OR source.destination_id<>NEW.account_id OR source.account_id<>NEW.destination_id))
   THEN RAISE EXCEPTION 'Invalid customer reversal'; END IF;
 END IF;
 IF NEW.kind IN ('FUNDING_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL') AND NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.request_id AND reversal_of=NEW.original_id) THEN RAISE EXCEPTION 'Reversal journal required'; END IF;
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
   IF NOT EXISTS(SELECT 1 FROM bank.customer_entry WHERE request_id=NEW.request_id AND account_id=NEW.account_id AND amount=CASE WHEN NEW.kind='WITHDRAWAL_REVERSAL' THEN NEW.amount ELSE -NEW.amount END) THEN RAISE EXCEPTION 'Customer debit required'; END IF;
   IF NEW.kind IN ('TRANSFER','TRANSFER_REVERSAL') THEN
     IF n<>2 OR delta<>0 OR NOT EXISTS(SELECT 1 FROM bank.customer_entry WHERE request_id=NEW.request_id AND account_id=NEW.destination_id AND amount=NEW.amount) THEN RAISE EXCEPTION 'Transfer credit required'; END IF;
   ELSIF n<>1 THEN RAISE EXCEPTION 'Unexpected customer entries'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE OR REPLACE FUNCTION bank.check_cash_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n INTEGER; delta NUMERIC; original RECORD;
BEGIN
 SELECT count(*),coalesce(sum(amount),0) INTO n,delta FROM bank.cash_entry WHERE request_id=NEW.request_id;
 IF NEW.kind='REVERSAL' THEN
  SELECT * INTO original FROM bank.cash_receipt WHERE request_id=NEW.original_id;
  IF NEW.amount IS NULL OR NEW.amount<=0 OR NEW.original_id IS DISTINCT FROM (NEW.payload->'change'->>'originalId')::uuid OR NEW.maker_subject=NEW.reviewer_subject OR original IS NULL OR original.kind NOT IN ('RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL') OR original.amount<>NEW.amount OR original.location_id<>NEW.location_id OR original.destination_id IS DISTINCT FROM NEW.destination_id OR NEW.journal_id IS DISTINCT FROM NEW.request_id THEN RAISE EXCEPTION 'Invalid cash reversal'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.request_id AND reversal_of=NEW.original_id) OR
     n<>(SELECT count(*) FROM bank.cash_entry WHERE request_id=NEW.original_id) OR
     EXISTS(SELECT 1 FROM bank.cash_entry o WHERE o.request_id=NEW.original_id AND NOT EXISTS(SELECT 1 FROM bank.cash_entry r WHERE r.request_id=NEW.request_id AND r.location_id=o.location_id AND r.amount=-o.amount))
  THEN RAISE EXCEPTION 'Cash reversal must exactly offset original'; END IF;
  IF original.kind IN ('DEPOSIT','WITHDRAWAL') AND NOT EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=NEW.request_id AND original_id=NEW.original_id AND kind=CASE WHEN original.kind='DEPOSIT' THEN 'FUNDING_REVERSAL' ELSE 'WITHDRAWAL_REVERSAL' END) THEN RAISE EXCEPTION 'Customer cash reversal missing'; END IF;
  RETURN NULL;
 END IF;
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
