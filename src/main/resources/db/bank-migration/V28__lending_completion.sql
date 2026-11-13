-- Round 15: Lending Completion bank schema updates (matrix rows F30-F32)

ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF','FEE_ASSESS','FEE_PAY','FEE_WAIVE','PARTIAL_WRITE_OFF','RECOVERY'));

CREATE OR REPLACE FUNCTION bank.check_contract_reversal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original bank.contract_event; correction bank.contract_event; expected_after JSONB; opposite JSONB;
BEGIN
 SELECT * INTO original FROM bank.contract_event WHERE id=NEW.original_id;
 SELECT * INTO correction FROM bank.contract_event WHERE id=NEW.id;
 IF original.contract_id<>NEW.contract_id OR correction.contract_id<>NEW.contract_id OR original.kind NOT IN ('OPEN','ACCRUE','REPAY','FEE_PAY') OR correction.kind<>'REVERSE' OR correction.write_transaction<>pg_current_xact_id() OR (correction.payload->>'originalEventId')::uuid<>original.id OR correction.payload->>'businessDate'<>original.payload->>'businessDate' THEN RAISE EXCEPTION 'Invalid contract correction link'; END IF;
 IF EXISTS(SELECT 1 FROM bank.contract_event e WHERE e.contract_id=NEW.contract_id AND e.event_sequence>original.event_sequence AND e.event_sequence<correction.event_sequence AND e.kind<>'REVERSE' AND NOT EXISTS(SELECT 1 FROM bank.contract_reversal r WHERE r.original_id=e.id)) THEN RAISE EXCEPTION 'Reverse later contract events first'; END IF;
 expected_after:=original.payload->'before';
 IF original.kind='OPEN' THEN expected_after:=jsonb_set(expected_after,'{closed}','true'); END IF;
 IF correction.payload->'before' IS DISTINCT FROM original.payload->'after' OR correction.payload->'after' IS DISTINCT FROM expected_after OR (correction.payload->>'customerMovement')::numeric<>-(original.payload->>'customerMovement')::numeric OR (correction.payload->>'tax')::numeric<>-(original.payload->>'tax')::numeric THEN RAISE EXCEPTION 'Correction must restore original position and cash'; END IF;
 IF (original.journal_id IS NULL) IS DISTINCT FROM (correction.journal_id IS NULL) THEN RAISE EXCEPTION 'Correction journal mismatch'; END IF;
 IF original.journal_id IS NOT NULL THEN
  SELECT jsonb_agg(jsonb_set(line,'{side}',to_jsonb(CASE line->>'side' WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END)) ORDER BY ord) INTO opposite FROM jsonb_array_elements(original.payload->'journal'->'lines') WITH ORDINALITY x(line,ord);
  IF correction.payload->'journal'->'lines' IS DISTINCT FROM opposite THEN RAISE EXCEPTION 'Correction must exactly reverse original journal'; END IF;
 END IF;
 RETURN NULL;
END; $$;

CREATE OR REPLACE FUNCTION bank.check_loan_risk_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e bank.contract_event; previous NUMERIC; ledger UUID; balance NUMERIC; exposure NUMERIC;
BEGIN
 SELECT * INTO e FROM bank.contract_event WHERE id=NEW.id;
 IF e.kind NOT IN ('IMPAIR','WRITE_OFF','RESTRUCTURE','PARTIAL_WRITE_OFF','RECOVERY') OR e.contract_id<>NEW.contract_id OR e.payload->'contract'->'terms'->>'type'<>'LOAN' OR e.payload->'loanServicing' IS DISTINCT FROM NEW.payload OR e.write_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'Loan risk receipt mismatch'; END IF;
 SELECT (r.payload->>'allowanceAfter')::numeric INTO previous FROM bank.loan_risk_event r JOIN bank.contract_event x ON x.id=r.id WHERE r.contract_id=NEW.contract_id AND x.event_sequence<e.event_sequence ORDER BY x.event_sequence DESC LIMIT 1;
 IF e.kind='RECOVERY' THEN
  IF (NEW.payload->>'allowanceAfter')::numeric<>coalesce(previous,0) THEN RAISE EXCEPTION 'Recovery cannot change allowance'; END IF;
  RETURN NULL;
 END IF;
 IF coalesce(previous,0)<>(NEW.payload->>'allowanceBefore')::numeric THEN RAISE EXCEPTION 'Loan allowance changed'; END IF;
 ledger:=(NEW.payload->'instruction'->>'allowanceLedger')::uuid;
 IF ledger IS NOT NULL THEN SELECT coalesce(credit-debit,0) INTO balance FROM bank.ledger_balance WHERE ledger_id=ledger;
  IF coalesce(balance,0)<>(NEW.payload->>'allowanceAfter')::numeric THEN RAISE EXCEPTION 'Loan allowance ledger mismatch'; END IF;
 END IF;
 exposure:=(e.payload->'after'->>'principal')::numeric+(e.payload->'after'->>'interest')::numeric;
 IF (NEW.payload->>'allowanceAfter')::numeric<0 OR (NEW.payload->>'allowanceAfter')::numeric>exposure THEN RAISE EXCEPTION 'Loan allowance exceeds exposure'; END IF;
 IF e.kind='WRITE_OFF' AND ((NEW.payload->>'allowanceBefore')::numeric<>(e.payload->'before'->>'principal')::numeric+(e.payload->'before'->>'interest')::numeric OR exposure<>0 OR (e.payload->>'customerMovement')::numeric<>0) THEN RAISE EXCEPTION 'Write-off must consume full allowance without customer debit'; END IF;
 IF e.kind='PARTIAL_WRITE_OFF' AND ((NEW.payload->>'allowanceBefore')::numeric-(NEW.payload->>'allowanceAfter')::numeric<=0 OR (e.payload->>'customerMovement')::numeric<>0) THEN RAISE EXCEPTION 'Partial write-off must consume allowance'; END IF;
 RETURN NULL;
END; $$;

CREATE OR REPLACE FUNCTION bank.require_loan_risk_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowance NUMERIC;
BEGIN
 IF NEW.kind IN ('IMPAIR','WRITE_OFF','RESTRUCTURE','PARTIAL_WRITE_OFF','RECOVERY') AND NOT EXISTS(SELECT 1 FROM bank.loan_risk_event WHERE id=NEW.id) THEN RAISE EXCEPTION 'Loan risk receipt required'; END IF;
 SELECT (r.payload->>'allowanceAfter')::numeric INTO allowance FROM bank.loan_risk_event r JOIN bank.contract_event e ON e.id=r.id WHERE r.contract_id=NEW.contract_id AND e.event_sequence<=NEW.event_sequence ORDER BY e.event_sequence DESC LIMIT 1;
 IF coalesce(allowance,0)>(NEW.payload->'after'->>'principal')::numeric+(NEW.payload->'after'->>'interest')::numeric THEN RAISE EXCEPTION 'Release excess allowance before reducing loan exposure'; END IF;
 RETURN NULL;
END; $$;
