ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE'));
CREATE TABLE bank.contract_reversal (
 id UUID PRIMARY KEY REFERENCES bank.contract_event(id),original_id UUID NOT NULL UNIQUE REFERENCES bank.contract_event(id),
 contract_id UUID NOT NULL REFERENCES bank.contract(id),CHECK(id<>original_id)
);
CREATE TRIGGER contract_reversal_immutable BEFORE UPDATE OR DELETE ON bank.contract_reversal FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.check_contract_reversal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original bank.contract_event; correction bank.contract_event; expected_after JSONB; opposite JSONB;
BEGIN
 SELECT * INTO original FROM bank.contract_event WHERE id=NEW.original_id;
 SELECT * INTO correction FROM bank.contract_event WHERE id=NEW.id;
 IF original.contract_id<>NEW.contract_id OR correction.contract_id<>NEW.contract_id OR original.kind NOT IN ('OPEN','ACCRUE','REPAY') OR correction.kind<>'REVERSE' OR correction.write_transaction<>pg_current_xact_id() OR (correction.payload->>'originalEventId')::uuid<>original.id OR correction.payload->>'businessDate'<>original.payload->>'businessDate' THEN RAISE EXCEPTION 'Invalid contract correction link'; END IF;
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
CREATE CONSTRAINT TRIGGER contract_reversal_exact AFTER INSERT ON bank.contract_reversal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_contract_reversal();
CREATE FUNCTION bank.require_contract_reversal_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='REVERSE' AND NOT EXISTS(SELECT 1 FROM bank.contract_reversal WHERE id=NEW.id) THEN RAISE EXCEPTION 'Correction link required'; END IF;
 IF NEW.kind<>'REVERSE' AND NEW.payload ? 'originalEventId' THEN RAISE EXCEPTION 'Unexpected correction reference'; END IF;
 IF EXISTS(SELECT 1 FROM bank.business_day_close WHERE business_date>=(NEW.payload->>'businessDate')::date) THEN RAISE EXCEPTION 'Contract business day closed'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_reversal_link AFTER INSERT ON bank.contract_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.require_contract_reversal_link();
