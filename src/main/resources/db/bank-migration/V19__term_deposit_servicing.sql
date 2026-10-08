ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW'));
CREATE TABLE bank.contract_term_revision (
 id UUID PRIMARY KEY REFERENCES bank.contract_event(id),contract_id UUID NOT NULL REFERENCES bank.contract(id),terms JSONB NOT NULL
);
CREATE TRIGGER contract_term_revision_immutable BEFORE UPDATE OR DELETE ON bank.contract_term_revision FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.check_contract_term_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e bank.contract_event;
BEGIN
 SELECT * INTO e FROM bank.contract_event WHERE id=NEW.id;
 IF e.kind<>'RENEW' OR e.contract_id<>NEW.contract_id OR e.write_transaction<>pg_current_xact_id() OR e.payload->'servicing'->'renewedTerms' IS DISTINCT FROM NEW.terms OR NEW.terms->>'type'<>'TERM_DEPOSIT' OR (NEW.terms->>'principal')::numeric<>(e.payload->'after'->>'principal')::numeric OR NEW.terms->>'startDate'<>e.payload->>'businessDate' OR (NEW.terms->>'maturityDate')::date<=(NEW.terms->>'startDate')::date OR (NEW.terms-ARRAY['expectedVersion','principal','annualRatePercent','startDate','maturityDate','agreementReference','reason']) IS DISTINCT FROM ((e.payload->'contract'->'terms')-ARRAY['expectedVersion','principal','annualRatePercent','startDate','maturityDate','agreementReference','reason']) OR (e.payload->'after'->>'interest')::numeric<>0 OR (e.payload->'after'->>'carry')::numeric<>0 OR (e.payload->'after'->>'closed')::boolean THEN RAISE EXCEPTION 'Invalid atomic renewal terms'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_term_revision_check AFTER INSERT ON bank.contract_term_revision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_contract_term_revision();
CREATE FUNCTION bank.require_contract_term_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='RENEW' AND NOT EXISTS(SELECT 1 FROM bank.contract_term_revision WHERE id=NEW.id) THEN RAISE EXCEPTION 'Renewal revision required'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_term_revision_required AFTER INSERT ON bank.contract_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.require_contract_term_revision();
CREATE OR REPLACE FUNCTION bank.check_contract_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d RECORD; previous JSONB; movement NUMERIC; principal NUMERIC; interest NUMERIC; p RECORD; book NUMERIC; sign INTEGER;
BEGIN
 SELECT * INTO d FROM bank.contract WHERE id=NEW.contract_id;
 IF NEW.write_transaction<>pg_current_xact_id() OR NEW.payload->'contract'->>'id'<>d.id::text OR NEW.payload->'contract'->'terms' IS DISTINCT FROM coalesce((SELECT r.terms FROM bank.contract_term_revision r JOIN bank.contract_event e ON e.id=r.id WHERE r.contract_id=d.id AND e.event_sequence<NEW.event_sequence ORDER BY e.event_sequence DESC LIMIT 1),d.payload->'terms') THEN RAISE EXCEPTION 'Contract receipt mismatch'; END IF;
 SELECT payload->'after' INTO previous FROM bank.contract_event WHERE contract_id=d.id AND event_sequence<NEW.event_sequence ORDER BY event_sequence DESC LIMIT 1;
 IF (NEW.kind='OPEN' AND previous IS NOT NULL) OR (NEW.kind<>'OPEN' AND (previous IS NULL OR previous IS DISTINCT FROM NEW.payload->'before')) THEN RAISE EXCEPTION 'Contract position changed'; END IF;
 IF NEW.journal_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.journal_id AND payload=NEW.payload->'journal' AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Contract journal mismatch'; END IF;
 movement:=(NEW.payload->>'customerMovement')::numeric;
 IF movement<>0 AND NOT EXISTS(SELECT 1 FROM bank.contract_entry WHERE event_id=NEW.id AND account_id=d.account_id AND amount=movement) THEN RAISE EXCEPTION 'Contract customer movement required'; END IF;
 IF movement=0 AND EXISTS(SELECT 1 FROM bank.contract_entry WHERE event_id=NEW.id) THEN RAISE EXCEPTION 'Unexpected customer movement'; END IF;
 principal:=(NEW.payload->'after'->>'principal')::numeric;interest:=(NEW.payload->'after'->>'interest')::numeric;
 IF principal<0 OR interest<0 THEN RAISE EXCEPTION 'Negative contract balance'; END IF;
 sign:=CASE WHEN d.payload->'terms'->>'type'='TERM_DEPOSIT' THEN 1 ELSE -1 END;
 SELECT coalesce(credit-debit,0)*sign INTO book FROM bank.ledger_balance WHERE ledger_id=d.principal_ledger;
 IF coalesce(book,0)<>principal THEN RAISE EXCEPTION 'Contract principal ledger mismatch'; END IF;
 SELECT coalesce(credit-debit,0)*sign INTO book FROM bank.ledger_balance WHERE ledger_id=d.interest_ledger;
 IF coalesce(book,0)<>interest THEN RAISE EXCEPTION 'Contract interest ledger mismatch'; END IF;
 SELECT * INTO p FROM bank.customer_position WHERE account_id=d.account_id;
 SELECT coalesce(credit-debit,0) INTO book FROM bank.ledger_balance WHERE ledger_id=p.ledger_id;
 IF p.posted<0 OR p.held>p.posted OR p.posted<>coalesce(book,0) THEN RAISE EXCEPTION 'Contract customer balance mismatch'; END IF;
 RETURN NULL;
END; $$;
