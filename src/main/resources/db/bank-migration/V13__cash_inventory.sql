ALTER TABLE bank.cash_receipt DROP CONSTRAINT cash_receipt_kind_check;
ALTER TABLE bank.cash_receipt ADD CHECK(kind IN ('REGISTER','INVENTORY','RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL','OPEN','CLOSE','COUNT','SHORTAGE','OVERAGE','REVERSAL'));
ALTER TABLE bank.cash_receipt ADD COLUMN receipt_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE;
CREATE TABLE bank.cash_inventory_start (
 location_id UUID PRIMARY KEY REFERENCES bank.cash_location(id),request_id UUID NOT NULL UNIQUE REFERENCES bank.cash_receipt(request_id)
);
CREATE TABLE bank.cash_note_entry (
 request_id UUID NOT NULL REFERENCES bank.cash_receipt(request_id),location_id UUID NOT NULL REFERENCES bank.cash_location(id),
 denomination NUMERIC(24,6) NOT NULL CHECK(denomination>0),quantity NUMERIC(28,0) NOT NULL CHECK(quantity<>0),
 PRIMARY KEY(request_id,location_id,denomination)
);
CREATE VIEW bank.cash_note_position AS SELECT location_id,denomination,sum(quantity) quantity FROM bank.cash_note_entry GROUP BY location_id,denomination;
CREATE TRIGGER cash_inventory_start_immutable BEFORE UPDATE OR DELETE ON bank.cash_inventory_start FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER cash_note_entry_immutable BEFORE UPDATE OR DELETE ON bank.cash_note_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE FUNCTION bank.record_cash_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE enabled BOOLEAN; other BOOLEAN; c JSONB; total NUMERIC; sign INTEGER; lid UUID; original UUID;
BEGIN
 SELECT EXISTS(SELECT 1 FROM bank.cash_inventory_start WHERE location_id=NEW.location_id) INTO enabled;
 IF NEW.kind='INVENTORY' THEN
  IF enabled OR (SELECT session_open FROM bank.cash_location WHERE id=NEW.location_id) THEN RAISE EXCEPTION 'Inventory already enabled or till open'; END IF;
  INSERT INTO bank.cash_inventory_start VALUES(NEW.location_id,NEW.request_id); enabled:=true;
 END IF;
 IF NEW.destination_id IS NOT NULL THEN
  SELECT EXISTS(SELECT 1 FROM bank.cash_inventory_start WHERE location_id=NEW.destination_id) INTO other;
  IF enabled<>other THEN RAISE EXCEPTION 'Both cash locations must use inventory'; END IF;
 END IF;
 IF NOT enabled THEN RETURN NULL; END IF;
 IF NEW.kind='REVERSAL' THEN
  IF NOT EXISTS(SELECT 1 FROM bank.cash_note_entry WHERE request_id=NEW.original_id) THEN RAISE EXCEPTION 'Original has no denomination evidence'; END IF;
  INSERT INTO bank.cash_note_entry SELECT NEW.request_id,location_id,denomination,-quantity FROM bank.cash_note_entry WHERE request_id=NEW.original_id;
 ELSE
  IF jsonb_typeof(NEW.payload->'change'->'counts') IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'Denomination evidence required'; END IF;
  SELECT coalesce(sum((v->>'denomination')::numeric*(v->>'quantity')::numeric),0) INTO total FROM jsonb_array_elements(NEW.payload->'change'->'counts') v;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.payload->'change'->'counts') v WHERE (v->>'quantity')::numeric<0 OR (v->>'quantity')::numeric<>trunc((v->>'quantity')::numeric) OR (v->>'denomination')::numeric<=0) THEN RAISE EXCEPTION 'Invalid denomination evidence'; END IF;
  IF NEW.kind IN ('INVENTORY','OPEN','CLOSE','COUNT','SHORTAGE','OVERAGE') THEN
   IF total IS DISTINCT FROM (SELECT balance FROM bank.cash_position WHERE id=NEW.location_id) THEN RAISE EXCEPTION 'Count differs from cash'; END IF;
   IF NEW.kind IN ('INVENTORY','SHORTAGE','OVERAGE') THEN
    INSERT INTO bank.cash_note_entry
     SELECT NEW.request_id,NEW.location_id,denomination,sum(q) FROM (
      SELECT (v->>'denomination')::numeric denomination,(v->>'quantity')::numeric q FROM jsonb_array_elements(NEW.payload->'change'->'counts') v
      UNION ALL SELECT denomination,-quantity FROM bank.cash_note_position WHERE location_id=NEW.location_id
     ) x GROUP BY denomination HAVING sum(q)<>0;
   ELSE
    IF EXISTS(SELECT 1 FROM (
      SELECT (v->>'denomination')::numeric denomination,(v->>'quantity')::numeric q FROM jsonb_array_elements(NEW.payload->'change'->'counts') v
      UNION ALL SELECT denomination,-quantity FROM bank.cash_note_position WHERE location_id=NEW.location_id
     ) x GROUP BY denomination HAVING sum(q)<>0) THEN RAISE EXCEPTION 'Denomination count mismatch'; END IF;
   END IF;
  ELSE
   IF total<>NEW.amount THEN RAISE EXCEPTION 'Denominations differ from movement'; END IF;
   sign:=CASE WHEN NEW.kind IN ('RECEIVE','DEPOSIT') THEN 1 ELSE -1 END;
   INSERT INTO bank.cash_note_entry SELECT NEW.request_id,NEW.location_id,(v->>'denomination')::numeric,sign*(v->>'quantity')::numeric FROM jsonb_array_elements(NEW.payload->'change'->'counts') v WHERE (v->>'quantity')::numeric<>0;
   IF NEW.destination_id IS NOT NULL THEN
    INSERT INTO bank.cash_note_entry SELECT NEW.request_id,NEW.destination_id,denomination,-quantity FROM bank.cash_note_entry WHERE request_id=NEW.request_id AND location_id=NEW.location_id;
   END IF;
   original:=(NEW.payload->'change'->'transit'->>'dispatchId')::uuid;
   IF original IS NOT NULL AND EXISTS(SELECT 1 FROM bank.cash_transit_receipt r JOIN bank.cash_note_entry n ON n.request_id=r.request_id WHERE r.dispatch_id=original GROUP BY n.denomination HAVING sum(n.quantity)>coalesce((SELECT -sum(o.quantity) FROM bank.cash_note_entry o WHERE o.request_id=original AND o.denomination=n.denomination),0)) THEN RAISE EXCEPTION 'Shipment denomination excess'; END IF;
  END IF;
 END IF;
 FOR lid IN SELECT NEW.location_id UNION SELECT NEW.destination_id WHERE NEW.destination_id IS NOT NULL LOOP
  IF EXISTS(SELECT 1 FROM bank.cash_note_position WHERE location_id=lid AND (quantity<0 OR quantity>1000000000)) OR (SELECT coalesce(sum(denomination*quantity),0) FROM bank.cash_note_position WHERE location_id=lid) IS DISTINCT FROM (SELECT balance FROM bank.cash_position WHERE id=lid) THEN RAISE EXCEPTION 'Inventory insufficient or unreconciled'; END IF;
 END LOOP;
 RETURN NULL;
END; $$;
-- Runs after cash entries and transit receipts exist, in the same transaction.
CREATE CONSTRAINT TRIGGER cash_inventory_record AFTER INSERT ON bank.cash_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.record_cash_inventory();
