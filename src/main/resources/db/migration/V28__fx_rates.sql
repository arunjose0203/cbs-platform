ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW'));
CREATE TABLE platform.fx_rate (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 pair_key VARCHAR(7) NOT NULL,effective_from DATE NOT NULL,effective_to DATE NOT NULL,payload JSONB NOT NULL,
 reviewer_subject VARCHAR(255) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),UNIQUE(tenant_id,id),CHECK(effective_to>=effective_from)
);
CREATE TABLE platform.fx_rate_withdrawal (
 rate_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,approval_id UUID UNIQUE NOT NULL REFERENCES platform.approval_request(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(tenant_id,rate_id) REFERENCES platform.fx_rate(tenant_id,id)
);
CREATE TRIGGER fx_rate_immutable BEFORE UPDATE OR DELETE ON platform.fx_rate FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER fx_withdrawal_immutable BEFORE UPDATE OR DELETE ON platform.fx_rate_withdrawal FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.check_fx_rate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='fx_rate' THEN
  PERFORM id FROM platform.tenant WHERE id=NEW.tenant_id FOR UPDATE;
  IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.action='FX_RATE' AND a.status='APPROVED' AND a.payload=NEW.payload) THEN RAISE EXCEPTION 'Rate requires approved matching request'; END IF;
  IF EXISTS(SELECT 1 FROM platform.fx_rate r WHERE r.tenant_id=NEW.tenant_id AND r.pair_key=NEW.pair_key AND r.id<>NEW.id AND r.effective_from<=NEW.effective_to AND r.effective_to>=NEW.effective_from AND NOT EXISTS(SELECT 1 FROM platform.fx_rate_withdrawal w WHERE w.rate_id=r.id)) THEN RAISE EXCEPTION 'Rate periods overlap'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.bank_id=NEW.tenant_id AND a.action='FX_RATE_WITHDRAW' AND a.status='APPROVED' AND a.payload->>'rateId'=NEW.rate_id::text) THEN RAISE EXCEPTION 'Withdrawal requires approved matching request'; END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_rate_approval AFTER INSERT ON platform.fx_rate DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_fx_rate();
CREATE CONSTRAINT TRIGGER fx_withdrawal_approval AFTER INSERT ON platform.fx_rate_withdrawal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_fx_rate();
