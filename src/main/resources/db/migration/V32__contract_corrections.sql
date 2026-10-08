ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION'));


ALTER TABLE platform.contract_delivery DROP CONSTRAINT contract_delivery_kind_check;
ALTER TABLE platform.contract_delivery ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE'));
ALTER TABLE platform.contract DROP CONSTRAINT contract_status_check;
ALTER TABLE platform.contract ADD CHECK(status IN ('AWAITING_POSTING','ACTIVE','CLOSED','CANCELLED'));
CREATE TABLE platform.contract_reversal (
 id UUID PRIMARY KEY REFERENCES platform.contract_delivery(id),original_id UUID NOT NULL UNIQUE REFERENCES platform.contract_delivery(id),
 contract_id UUID NOT NULL REFERENCES platform.contract(id),CHECK(id<>original_id)
);
CREATE TRIGGER contract_reversal_immutable BEFORE UPDATE OR DELETE ON platform.contract_reversal FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE OR REPLACE FUNCTION platform.check_contract_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE NEW.kind WHEN 'OPEN' THEN 'CONTRACT_OPEN' WHEN 'REVERSE' THEN 'CONTRACT_CORRECTION' ELSE 'CONTRACT_EVENT' END) THEN RAISE EXCEPTION 'Contract event requires matching approval'; END IF;
 IF NEW.payload->'journal'<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Contract journal required'; END IF;
 IF NEW.kind='REVERSE' AND NOT EXISTS(SELECT 1 FROM platform.contract_reversal r JOIN platform.contract_delivery o ON o.id=r.original_id WHERE r.id=NEW.id AND r.contract_id=NEW.contract_id AND o.contract_id=NEW.contract_id AND o.tenant_id=NEW.tenant_id AND o.completed AND o.kind IN ('OPEN','ACCRUE','REPAY') AND o.business_date=NEW.business_date AND r.original_id=(NEW.payload->>'originalEventId')::uuid) THEN RAISE EXCEPTION 'Original contract event required'; END IF;
 RETURN NULL;
END; $$;
ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY','TD_OPEN_REVERSAL','LOAN_DISBURSE_REVERSAL','LOAN_REPAY_REVERSAL'));
