ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF'));
CREATE TABLE bank.loan_risk_event(id UUID PRIMARY KEY REFERENCES bank.contract_event(id),contract_id UUID NOT NULL REFERENCES bank.contract(id),payload JSONB NOT NULL);
CREATE TRIGGER loan_risk_event_immutable BEFORE UPDATE OR DELETE ON bank.loan_risk_event FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE OR REPLACE FUNCTION bank.check_contract_term_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e bank.contract_event; details JSONB;
BEGIN
 SELECT * INTO e FROM bank.contract_event WHERE id=NEW.id;
 details:=CASE WHEN e.kind='RENEW' THEN e.payload->'servicing' ELSE e.payload->'loanServicing' END;
 IF e.kind='RENEW' AND ((NEW.terms->>'instalments')::integer<>0 OR (e.payload->'after'->>'carry')::numeric<>0) THEN RAISE EXCEPTION 'Deposit renewal carry and instalments must be zero'; END IF;
 IF e.kind='RESTRUCTURE' AND ((e.payload->'before'->>'principal')::numeric<>(e.payload->'after'->>'principal')::numeric OR (e.payload->'before'->>'interest')::numeric<>0 OR e.payload->'before'->>'carry' IS DISTINCT FROM e.payload->'after'->>'carry' OR (e.payload->>'customerMovement')::numeric<>0 OR e.journal_id IS NOT NULL) THEN RAISE EXCEPTION 'Restructuring cannot change principal, interest carry or customer cash'; END IF;
 IF e.kind NOT IN ('RENEW','RESTRUCTURE') OR e.contract_id<>NEW.contract_id OR e.write_transaction<>pg_current_xact_id() OR details->'renewedTerms' IS DISTINCT FROM NEW.terms OR NEW.terms->>'type'<>(CASE WHEN e.kind='RENEW' THEN 'TERM_DEPOSIT' ELSE 'LOAN' END) OR (NEW.terms->>'principal')::numeric<>(e.payload->'after'->>'principal')::numeric OR NEW.terms->>'startDate'<>e.payload->>'businessDate' OR (NEW.terms->>'maturityDate')::date<=(NEW.terms->>'startDate')::date OR (NEW.terms-ARRAY['expectedVersion','principal','annualRatePercent','startDate','maturityDate','agreementReference','reason','instalments']) IS DISTINCT FROM ((e.payload->'contract'->'terms')-ARRAY['expectedVersion','principal','annualRatePercent','startDate','maturityDate','agreementReference','reason','instalments']) OR (e.payload->'after'->>'interest')::numeric<>0 OR (e.payload->'after'->>'closed')::boolean THEN RAISE EXCEPTION 'Invalid atomic contract revision'; END IF;
 RETURN NULL;
END; $$;
CREATE OR REPLACE FUNCTION bank.require_contract_term_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind IN ('RENEW','RESTRUCTURE') AND NOT EXISTS(SELECT 1 FROM bank.contract_term_revision WHERE id=NEW.id) THEN RAISE EXCEPTION 'Contract revision required'; END IF;
 RETURN NULL;
END; $$;
CREATE FUNCTION bank.check_loan_risk_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e bank.contract_event; previous NUMERIC; ledger UUID; balance NUMERIC; exposure NUMERIC;
BEGIN
 SELECT * INTO e FROM bank.contract_event WHERE id=NEW.id;
 IF e.kind NOT IN ('IMPAIR','WRITE_OFF','RESTRUCTURE') OR e.contract_id<>NEW.contract_id OR e.payload->'contract'->'terms'->>'type'<>'LOAN' OR e.payload->'loanServicing' IS DISTINCT FROM NEW.payload OR e.write_transaction<>pg_current_xact_id() THEN RAISE EXCEPTION 'Loan risk receipt mismatch'; END IF;
 SELECT (r.payload->>'allowanceAfter')::numeric INTO previous FROM bank.loan_risk_event r JOIN bank.contract_event x ON x.id=r.id WHERE r.contract_id=NEW.contract_id AND x.event_sequence<e.event_sequence ORDER BY x.event_sequence DESC LIMIT 1;
 IF coalesce(previous,0)<>(NEW.payload->>'allowanceBefore')::numeric THEN RAISE EXCEPTION 'Loan allowance changed'; END IF;
 ledger:=(NEW.payload->'instruction'->>'allowanceLedger')::uuid;
 IF ledger IS NOT NULL THEN SELECT coalesce(credit-debit,0) INTO balance FROM bank.ledger_balance WHERE ledger_id=ledger;
  IF coalesce(balance,0)<>(NEW.payload->>'allowanceAfter')::numeric THEN RAISE EXCEPTION 'Loan allowance ledger mismatch'; END IF;
 END IF;
 exposure:=(e.payload->'after'->>'principal')::numeric+(e.payload->'after'->>'interest')::numeric;
 IF (NEW.payload->>'allowanceAfter')::numeric<0 OR (NEW.payload->>'allowanceAfter')::numeric>exposure THEN RAISE EXCEPTION 'Loan allowance exceeds exposure'; END IF;
 IF e.kind='WRITE_OFF' AND ((NEW.payload->>'allowanceBefore')::numeric<>(e.payload->'before'->>'principal')::numeric+(e.payload->'before'->>'interest')::numeric OR exposure<>0 OR (e.payload->>'customerMovement')::numeric<>0) THEN RAISE EXCEPTION 'Write-off must consume full allowance without customer debit'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER loan_risk_checked AFTER INSERT ON bank.loan_risk_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_loan_risk_event();
CREATE FUNCTION bank.require_loan_risk_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowance NUMERIC;
BEGIN
 IF NEW.kind IN ('IMPAIR','WRITE_OFF','RESTRUCTURE') AND NOT EXISTS(SELECT 1 FROM bank.loan_risk_event WHERE id=NEW.id) THEN RAISE EXCEPTION 'Loan risk receipt required'; END IF;
 SELECT (r.payload->>'allowanceAfter')::numeric INTO allowance FROM bank.loan_risk_event r JOIN bank.contract_event e ON e.id=r.id WHERE r.contract_id=NEW.contract_id AND e.event_sequence<=NEW.event_sequence ORDER BY e.event_sequence DESC LIMIT 1;
 IF coalesce(allowance,0)>(NEW.payload->'after'->>'principal')::numeric+(NEW.payload->'after'->>'interest')::numeric THEN RAISE EXCEPTION 'Release excess allowance before reducing loan exposure'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER loan_risk_required AFTER INSERT ON bank.contract_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.require_loan_risk_event();
