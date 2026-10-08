CREATE TABLE bank.fx_trade (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,original_id UUID UNIQUE REFERENCES bank.fx_trade(id),source_journal UUID UNIQUE NOT NULL REFERENCES bank.journal(id),target_journal UUID UNIQUE NOT NULL REFERENCES bank.journal(id),payload JSONB NOT NULL,
 write_transaction XID8 NOT NULL DEFAULT pg_current_xact_id(),CHECK(source_journal<>target_journal)
);
CREATE TABLE bank.fx_entry (
 trade_id UUID NOT NULL REFERENCES bank.fx_trade(id),account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),journal_id UUID UNIQUE NOT NULL REFERENCES bank.journal(id),amount NUMERIC(28,6) NOT NULL CHECK(amount<>0),PRIMARY KEY(trade_id,account_id)
);
CREATE TRIGGER fx_trade_immutable BEFORE UPDATE OR DELETE ON bank.fx_trade FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER fx_entry_immutable BEFORE UPDATE OR DELETE ON bank.fx_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE OR REPLACE VIEW bank.customer_position AS
 SELECT b.account_id,b.ledger_id,
 coalesce((SELECT sum(f.amount) FROM bank.funding f WHERE f.account_id=b.account_id),0)
 +coalesce((SELECT sum(e.amount) FROM bank.customer_entry e WHERE e.account_id=b.account_id),0)
 +coalesce((SELECT sum(s.net_amount) FROM bank.servicing_event s WHERE s.account_id=b.account_id),0) +coalesce((SELECT sum(x.amount) FROM bank.fx_entry x WHERE x.account_id=b.account_id),0) posted,
 coalesce((SELECT sum(h.amount) FROM bank.account_operation h WHERE h.account_id=b.account_id AND h.kind='HOLD'
 AND NOT EXISTS(SELECT 1 FROM bank.account_operation r WHERE r.kind='RELEASE' AND r.original_id=h.request_id)),0) held
 FROM bank.account_ledger b;
CREATE OR REPLACE FUNCTION bank.check_customer_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; aid UUID; lid UUID; amt NUMERIC; dr NUMERIC; cr NUMERIC; n INTEGER;
BEGIN
 IF TG_TABLE_NAME='journal_line' THEN
  jid:=NEW.journal_id; SELECT account_id INTO aid FROM bank.account_ledger WHERE ledger_id=NEW.ledger_id;
  IF aid IS NULL THEN RETURN NULL; END IF;
 ELSE jid:=NEW.request_id;aid:=NEW.account_id; END IF;
 IF EXISTS(SELECT 1 FROM bank.fx_entry WHERE journal_id=jid AND account_id=aid) THEN
  SELECT b.ledger_id,e.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.fx_entry e ON e.account_id=b.account_id WHERE e.journal_id=jid AND e.account_id=aid;
  SELECT coalesce(sum(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0) INTO cr FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
  IF cr<>amt THEN RAISE EXCEPTION 'FX customer journal mismatch'; END IF;
 ELSIF EXISTS(SELECT 1 FROM bank.servicing_event WHERE request_id=jid AND account_id=aid) THEN
  SELECT b.ledger_id,e.net_amount INTO lid,amt FROM bank.account_ledger b JOIN bank.servicing_event e ON e.account_id=b.account_id WHERE e.request_id=jid AND e.account_id=aid;
  SELECT coalesce(sum(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0) INTO cr FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
  IF cr<>amt THEN RAISE EXCEPTION 'Servicing customer journal mismatch'; END IF;
 ELSIF EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=jid) THEN
  SELECT b.ledger_id,e.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.customer_entry e ON e.account_id=b.account_id WHERE e.request_id=jid AND e.account_id=aid;
  IF lid IS NULL THEN RAISE EXCEPTION 'Customer entry required'; END IF;
  SELECT coalesce(sum(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0) INTO cr FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
  IF cr<>amt THEN RAISE EXCEPTION 'Customer entry does not match journal'; END IF;
 ELSE
  SELECT b.ledger_id,f.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.funding f ON f.account_id=b.account_id WHERE f.request_id=jid AND f.account_id=aid;
  IF lid IS NULL THEN RAISE EXCEPTION 'Customer subledger entry required'; END IF;
  SELECT count(*),coalesce(sum(amount) FILTER(WHERE ledger_id=lid AND side='CREDIT'),0),coalesce(sum(amount) FILTER(WHERE ledger_id<>lid AND side='DEBIT'),0) INTO n,cr,dr FROM bank.journal_line WHERE journal_id=jid;
  IF n<>2 OR cr<>amt OR dr<>amt THEN RAISE EXCEPTION 'Customer funding journal mismatch'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=jid AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Customer journal is sealed'; END IF;
 RETURN NULL;
END; $$;

CREATE FUNCTION bank.check_fx_trade() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t RECORD; p RECORD; book NUMERIC;
BEGIN
 SELECT * INTO t FROM bank.fx_trade WHERE id=NEW.trade_id;
 IF t.write_transaction<>pg_current_xact_id() OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.journal_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'FX trade is sealed'; END IF;
 IF NOT ((NEW.journal_id=t.source_journal AND NEW.account_id::text=t.payload->'source'->>'id' AND NEW.amount=-(t.payload->'quote'->>'sourceAmount')::numeric) OR (NEW.journal_id=t.target_journal AND NEW.account_id::text=t.payload->'target'->>'id' AND NEW.amount=(t.payload->'quote'->>'targetAmount')::numeric)) THEN RAISE EXCEPTION 'FX amount or account mismatch'; END IF;
 IF (SELECT count(*) FROM bank.fx_entry WHERE trade_id=t.id)<>2 THEN RAISE EXCEPTION 'Both FX legs are required'; END IF;
 SELECT * INTO p FROM bank.customer_position WHERE account_id=NEW.account_id;
 SELECT coalesce(credit-debit,0) INTO book FROM bank.ledger_balance WHERE ledger_id=p.ledger_id;
 IF p.posted<0 OR p.held>p.posted OR p.posted<>coalesce(book,0) THEN RAISE EXCEPTION 'FX customer balance mismatch'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_entry_balanced AFTER INSERT ON bank.fx_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_fx_trade();

CREATE FUNCTION bank.check_fx_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original JSONB;
BEGIN
 IF NEW.write_transaction<>pg_current_xact_id() OR (SELECT count(*) FROM bank.fx_entry WHERE trade_id=NEW.id)<>2 THEN RAISE EXCEPTION 'Complete FX posting required'; END IF;
 IF NEW.payload->>'id'<>NEW.id::text OR NEW.payload->>'bankId'<>NEW.tenant_id::text
 OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.source_journal AND payload=NEW.payload->'sourceJournal' AND write_transaction=pg_current_xact_id())
 OR NOT EXISTS(SELECT 1 FROM bank.journal WHERE id=NEW.target_journal AND payload=NEW.payload->'targetJournal' AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'FX journals do not match receipt'; END IF;
 IF NEW.original_id IS NOT NULL THEN
  SELECT payload INTO original FROM bank.fx_trade WHERE id=NEW.original_id AND tenant_id=NEW.tenant_id AND original_id IS NULL;
  IF original IS NULL OR NEW.payload->'source'->>'id'<>original->'target'->>'id' OR NEW.payload->'target'->>'id'<>original->'source'->>'id'
  OR (NEW.payload->'quote'->>'sourceAmount')::numeric<>(original->'quote'->>'targetAmount')::numeric
  OR (NEW.payload->'quote'->>'targetAmount')::numeric<>(original->'quote'->>'sourceAmount')::numeric THEN RAISE EXCEPTION 'Reversal must undo the original conversion'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_receipt_complete AFTER INSERT ON bank.fx_trade DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_fx_receipt();
