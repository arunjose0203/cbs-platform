CREATE TABLE bank.cash_transit (
 dispatch_id UUID PRIMARY KEY REFERENCES bank.cash_receipt(request_id),
 source_id UUID NOT NULL REFERENCES bank.cash_location(id),
 destination_id UUID NOT NULL REFERENCES bank.cash_location(id),
 ledger_id UUID NOT NULL REFERENCES bank.ledger_account(id),
 amount NUMERIC(24,6) NOT NULL CHECK(amount>0), expected_date DATE NOT NULL
);
CREATE INDEX cash_transit_ledger ON bank.cash_transit(ledger_id);
CREATE TABLE bank.cash_transit_receipt (
 request_id UUID PRIMARY KEY REFERENCES bank.cash_receipt(request_id),
 dispatch_id UUID NOT NULL REFERENCES bank.cash_transit(dispatch_id),
 amount NUMERIC(24,6) NOT NULL CHECK(amount>0), returned BOOLEAN NOT NULL
);
CREATE TRIGGER cash_transit_immutable BEFORE UPDATE OR DELETE ON bank.cash_transit FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER cash_transit_receipt_immutable BEFORE UPDATE OR DELETE ON bank.cash_transit_receipt FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.record_cash_transit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE meta JSONB; src RECORD; dst RECORD; original RECORD; lid UUID; used NUMERIC; physical NUMERIC;
BEGIN
 meta:=NEW.payload->'change'->'transit';
 IF meta IS NULL THEN RETURN NULL; END IF;
 lid:=(NEW.payload->'change'->>'ledgerId')::uuid;
 SELECT * INTO src FROM bank.cash_location WHERE id=NEW.location_id;
 IF NEW.kind NOT IN ('DISPATCH','RECEIVE') OR src.kind<>'VAULT' OR lid IS NULL OR NEW.maker_subject=NEW.reviewer_subject OR NEW.amount IS NULL OR NEW.amount<=0 THEN RAISE EXCEPTION 'Invalid transit movement'; END IF;
 IF NEW.kind='DISPATCH' THEN
  SELECT * INTO dst FROM bank.cash_location WHERE id=(meta->>'destinationId')::uuid;
  IF dst IS NULL OR dst.kind<>'VAULT' OR dst.branch_id=src.branch_id OR dst.tenant_id<>src.tenant_id OR dst.legal_entity_id<>src.legal_entity_id OR dst.currency<>src.currency OR meta->>'dispatchId' IS NOT NULL OR (meta->>'returned')::boolean IS DISTINCT FROM false OR meta->>'expectedDate' IS NULL OR (meta->>'expectedDate')::date<(NEW.payload->'change'->>'businessDate')::date THEN RAISE EXCEPTION 'Invalid transit route'; END IF;
  IF EXISTS(SELECT 1 FROM bank.cash_location WHERE ledger_id=lid) OR EXISTS(SELECT 1 FROM bank.account_ledger WHERE ledger_id=lid) THEN RAISE EXCEPTION 'Transit ledger already bound'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bank.cash_transit WHERE ledger_id=lid) AND EXISTS(SELECT 1 FROM bank.journal_line WHERE ledger_id=lid AND journal_id<>NEW.request_id) THEN RAISE EXCEPTION 'Transit ledger must start unused'; END IF;
  INSERT INTO bank.cash_transit VALUES(NEW.request_id,src.id,dst.id,lid,NEW.amount,(meta->>'expectedDate')::date);
 ELSE
  SELECT * INTO original FROM bank.cash_transit WHERE dispatch_id=(meta->>'dispatchId')::uuid FOR UPDATE;
  IF original IS NULL OR original.ledger_id<>lid OR src.id<>(CASE WHEN (meta->>'returned')::boolean THEN original.source_id ELSE original.destination_id END) OR meta->>'destinationId' IS NOT NULL OR meta->>'expectedDate' IS NOT NULL THEN RAISE EXCEPTION 'Invalid transit receipt'; END IF;
  SELECT coalesce(sum(amount),0) INTO used FROM bank.cash_transit_receipt WHERE dispatch_id=original.dispatch_id;
  IF NEW.amount+used>original.amount THEN RAISE EXCEPTION 'Transit receipt exceeds outstanding amount'; END IF;
  IF jsonb_typeof(NEW.payload->'change'->'counts') IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'Physical count required'; END IF;
  SELECT coalesce(sum((v->>'denomination')::numeric*(v->>'quantity')::numeric),0) INTO physical FROM jsonb_array_elements(NEW.payload->'change'->'counts') v;
  IF physical<>NEW.amount THEN RAISE EXCEPTION 'Transit physical count mismatch'; END IF;
  INSERT INTO bank.cash_transit_receipt VALUES(NEW.request_id,original.dispatch_id,NEW.amount,(meta->>'returned')::boolean);
 END IF;
 IF (SELECT count(*) FROM bank.journal_line WHERE journal_id=NEW.request_id)<>2 OR NOT EXISTS(SELECT 1 FROM bank.journal_line WHERE journal_id=NEW.request_id AND ledger_id=lid AND amount=NEW.amount AND side=CASE WHEN NEW.kind='DISPATCH' THEN 'DEBIT' ELSE 'CREDIT' END) THEN RAISE EXCEPTION 'Invalid transit accounting'; END IF;
 RETURN NULL;
END; $$;
-- Record the route before deferred journal checks run; every change rolls back with the journal.
CREATE TRIGGER cash_transit_record AFTER INSERT ON bank.cash_receipt FOR EACH ROW EXECUTE FUNCTION bank.record_cash_transit();
CREATE FUNCTION bank.check_transit_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bank.cash_transit WHERE ledger_id=NEW.ledger_id) AND NOT EXISTS(SELECT 1 FROM bank.cash_receipt c WHERE c.request_id=NEW.journal_id AND c.kind IN ('DISPATCH','RECEIVE') AND c.payload->'change'->'transit' IS NOT NULL AND (c.payload->'change'->>'ledgerId')::uuid=NEW.ledger_id) THEN RAISE EXCEPTION 'Transit ledger requires a linked cash movement'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER transit_journal_check AFTER INSERT ON bank.journal_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_transit_journal();
