ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT'));
CREATE TABLE platform.contract (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),account_id UUID NOT NULL,
 agreement_reference VARCHAR(160) GENERATED ALWAYS AS (payload->'terms'->>'agreementReference') STORED,
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('TERM_DEPOSIT','LOAN')),payload JSONB NOT NULL,position JSONB NOT NULL,
 status VARCHAR(24) NOT NULL CHECK(status IN ('AWAITING_POSTING','ACTIVE','CLOSED')),created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,agreement_reference),FOREIGN KEY(tenant_id,account_id) REFERENCES platform.account_registry(tenant_id,id)
);
CREATE TABLE platform.contract_ledger (
 ledger_id UUID PRIMARY KEY REFERENCES platform.ledger_registry(id),contract_id UUID NOT NULL REFERENCES platform.contract(id)
);
CREATE TABLE platform.contract_delivery (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL,contract_id UUID NOT NULL,account_id UUID NOT NULL,
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM')),business_date DATE NOT NULL,amount NUMERIC(28,6) NOT NULL CHECK(amount>=0),currency VARCHAR(3) NOT NULL,payload JSONB NOT NULL,
 maker_subject VARCHAR(255) NOT NULL,reviewer_issuer VARCHAR(512) NOT NULL,reviewer_subject VARCHAR(255) NOT NULL,correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false,attempts INTEGER NOT NULL DEFAULT 0,failure_code VARCHAR(80),last_attempt_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES platform.contract(tenant_id,id),FOREIGN KEY(tenant_id,account_id) REFERENCES platform.account_registry(tenant_id,id),CHECK(maker_subject<>reviewer_subject)
);
CREATE UNIQUE INDEX contract_one_pending ON platform.contract_delivery(contract_id) WHERE NOT completed;
CREATE INDEX contract_delivery_pending ON platform.contract_delivery(created_at) WHERE NOT completed;
CREATE TRIGGER contract_ledger_immutable BEFORE UPDATE OR DELETE ON platform.contract_ledger FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER contract_delivery_immutable BEFORE UPDATE OR DELETE ON platform.contract_delivery FOR EACH ROW EXECUTE FUNCTION platform.protect_fx_trade();
CREATE FUNCTION platform.protect_contract() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Contract history is immutable'; END IF;
 -- Generated columns are recomputed after BEFORE triggers; immutable payload protects the reference.
 IF (to_jsonb(NEW)-ARRAY['position','status','agreement_reference']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['position','status','agreement_reference']) THEN RAISE EXCEPTION 'Contract terms are immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER contract_terms_immutable BEFORE UPDATE OR DELETE ON platform.contract FOR EACH ROW EXECUTE FUNCTION platform.protect_contract();
CREATE FUNCTION platform.check_contract_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE WHEN NEW.kind='OPEN' THEN 'CONTRACT_OPEN' ELSE 'CONTRACT_EVENT' END) THEN RAISE EXCEPTION 'Contract event requires matching approval'; END IF;
 IF NEW.payload->'journal'<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Contract journal required'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER contract_approval_check AFTER INSERT ON platform.contract_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_contract_approval();
ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY'));
