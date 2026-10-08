ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL'));
CREATE TABLE platform.fx_trade (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 original_id UUID UNIQUE,source_account UUID NOT NULL,target_account UUID NOT NULL,business_date DATE NOT NULL,
 source_currency VARCHAR(3) NOT NULL,source_amount NUMERIC(28,6) NOT NULL CHECK(source_amount>0),payload JSONB NOT NULL,
 maker_subject VARCHAR(255) NOT NULL,reviewer_issuer VARCHAR(512) NOT NULL,reviewer_subject VARCHAR(255) NOT NULL,correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false,attempts INTEGER NOT NULL DEFAULT 0,failure_code VARCHAR(80),last_attempt_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),CHECK(source_account<>target_account),CHECK(maker_subject<>reviewer_subject),
 FOREIGN KEY(tenant_id,original_id) REFERENCES platform.fx_trade(tenant_id,id),
 FOREIGN KEY(tenant_id,source_account) REFERENCES platform.account_registry(tenant_id,id),
 FOREIGN KEY(tenant_id,target_account) REFERENCES platform.account_registry(tenant_id,id)
);
CREATE INDEX fx_trade_pending ON platform.fx_trade(created_at) WHERE NOT completed;
CREATE TABLE platform.fx_posting (
 journal_id UUID PRIMARY KEY REFERENCES platform.journal_delivery(request_id),trade_id UUID NOT NULL REFERENCES platform.fx_trade(id)
);
CREATE TABLE platform.fx_position (
 ledger_id UUID PRIMARY KEY REFERENCES platform.ledger_registry(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id)
);
CREATE TRIGGER fx_posting_immutable BEFORE UPDATE OR DELETE ON platform.fx_posting FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER fx_position_immutable BEFORE UPDATE OR DELETE ON platform.fx_position FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.protect_fx_trade() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'FX trade is immutable'; END IF;
 IF (to_jsonb(NEW)-ARRAY['completed','attempts','failure_code','last_attempt_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['completed','attempts','failure_code','last_attempt_at']) OR (OLD.completed AND NOT NEW.completed) THEN RAISE EXCEPTION 'FX instruction is immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER fx_trade_immutable BEFORE UPDATE OR DELETE ON platform.fx_trade FOR EACH ROW EXECUTE FUNCTION platform.protect_fx_trade();
CREATE FUNCTION platform.check_fx_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE WHEN NEW.original_id IS NULL THEN 'FX_TRADE' ELSE 'FX_REVERSAL' END) THEN RAISE EXCEPTION 'FX requires approved bank request'; END IF;
 IF (SELECT count(*) FROM platform.fx_posting WHERE trade_id=NEW.id)<>2 THEN RAISE EXCEPTION 'Both FX journals required'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_approval_check AFTER INSERT ON platform.fx_trade DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_fx_approval();
CREATE OR REPLACE FUNCTION platform.check_journal_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.request_id<>NEW.approval_id AND NOT EXISTS (
 SELECT 1 FROM platform.servicing_posting p JOIN platform.servicing_run r ON r.request_id=p.run_id
 WHERE p.request_id=NEW.request_id AND r.request_id=NEW.approval_id AND r.tenant_id=NEW.tenant_id
 ) AND NOT EXISTS (
 SELECT 1 FROM platform.fx_posting p JOIN platform.fx_trade t ON t.id=p.trade_id
 WHERE p.journal_id=NEW.request_id AND t.id=NEW.approval_id AND t.tenant_id=NEW.tenant_id
 AND NEW.payload IN (t.payload->'sourceJournal',t.payload->'targetJournal')
 ) THEN RAISE EXCEPTION 'Journal must belong to its approved batch'; END IF;
 RETURN NULL;
END; $$;
ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL'));
