CREATE TABLE bank.contract (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),
 principal_ledger UUID UNIQUE NOT NULL REFERENCES bank.ledger_account(id),interest_ledger UUID UNIQUE NOT NULL REFERENCES bank.ledger_account(id),payload JSONB NOT NULL,
 CHECK(principal_ledger<>interest_ledger)
);
CREATE TABLE bank.contract_event (
 id UUID PRIMARY KEY,contract_id UUID NOT NULL REFERENCES bank.contract(id),kind VARCHAR(16) NOT NULL CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM')),
 journal_id UUID UNIQUE REFERENCES bank.journal(id),payload JSONB NOT NULL,event_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
 write_transaction XID8 NOT NULL DEFAULT pg_current_xact_id()
);
CREATE UNIQUE INDEX contract_single_open ON bank.contract_event(contract_id) WHERE kind='OPEN';
CREATE TABLE bank.contract_entry (
 event_id UUID PRIMARY KEY REFERENCES bank.contract_event(id),account_id UUID NOT NULL REFERENCES bank.account_ledger(account_id),amount NUMERIC(28,6) NOT NULL CHECK(amount<>0)
);
CREATE TRIGGER contract_immutable BEFORE UPDATE OR DELETE ON bank.contract FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER contract_event_immutable BEFORE UPDATE OR DELETE ON bank.contract_event FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE TRIGGER contract_entry_immutable BEFORE UPDATE OR DELETE ON bank.contract_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
CREATE OR REPLACE VIEW bank.customer_position AS
 SELECT b.account_id,b.ledger_id,
 coalesce((SELECT sum(f.amount) FROM bank.funding f WHERE f.account_id=b.account_id),0)
 +coalesce((SELECT sum(e.amount) FROM bank.customer_entry e WHERE e.account_id=b.account_id),0)
 +coalesce((SELECT sum(s.net_amount) FROM bank.servicing_event s WHERE s.account_id=b.account_id),0) +coalesce((SELECT sum(x.amount) FROM bank.fx_entry x WHERE x.account_id=b.account_id),0) +coalesce((SELECT sum(x.amount) FROM bank.contract_entry x WHERE x.account_id=b.account_id),0) posted,
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
 IF EXISTS(SELECT 1 FROM bank.contract_entry WHERE event_id=jid AND account_id=aid) THEN
  SELECT b.ledger_id,e.amount INTO lid,amt FROM bank.account_ledger b JOIN bank.contract_entry e ON e.account_id=b.account_id WHERE e.event_id=jid AND e.account_id=aid;
  SELECT coalesce(sum(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0) INTO cr FROM bank.journal_line WHERE journal_id=jid AND ledger_id=lid;
  IF cr<>amt THEN RAISE EXCEPTION 'Contract customer journal mismatch'; END IF;
 ELSIF EXISTS(SELECT 1 FROM bank.fx_entry WHERE journal_id=jid AND account_id=aid) THEN
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


CREATE FUNCTION bank.check_contract_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d RECORD; previous JSONB; movement NUMERIC; principal NUMERIC; interest NUMERIC; p RECORD; book NUMERIC; sign INTEGER;
BEGIN
 SELECT * INTO d FROM bank.contract WHERE id=NEW.contract_id;
 IF NEW.write_transaction<>pg_current_xact_id() OR NEW.payload->'contract'->>'id'<>d.id::text OR NEW.payload->'contract'->'terms' IS DISTINCT FROM d.payload->'terms' THEN RAISE EXCEPTION 'Contract receipt mismatch'; END IF;
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
CREATE CONSTRAINT TRIGGER contract_event_balanced AFTER INSERT ON bank.contract_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_contract_event();
CREATE FUNCTION bank.check_contract_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bank.contract_event WHERE id=NEW.event_id AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Contract event is sealed'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_entry_sealed AFTER INSERT ON bank.contract_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_contract_entry();

CREATE FUNCTION bank.guard_contract_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bank.contract WHERE principal_ledger=NEW.ledger_id OR interest_ledger=NEW.ledger_id)
 AND NOT EXISTS(SELECT 1 FROM bank.contract_event e JOIN bank.contract c ON c.id=e.contract_id
 WHERE e.journal_id=NEW.journal_id AND e.write_transaction=pg_current_xact_id() AND NEW.ledger_id IN (c.principal_ledger,c.interest_ledger)) THEN RAISE EXCEPTION 'Dedicated contract ledger requires atomic contract event'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_journal_managed AFTER INSERT ON bank.journal_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.guard_contract_journal();
CREATE FUNCTION bank.check_contract_open() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bank.contract_event WHERE contract_id=NEW.id AND kind='OPEN' AND write_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'Contract requires atomic opening'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_open_complete AFTER INSERT ON bank.contract DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank.check_contract_open();
