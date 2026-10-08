ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE'));


ALTER TABLE platform.contract_delivery DROP CONSTRAINT contract_delivery_kind_check;
ALTER TABLE platform.contract_delivery ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF','FEE_ASSESS','FEE_PAY','FEE_WAIVE'));
CREATE TABLE platform.loan_fee_book(contract_id UUID PRIMARY KEY REFERENCES platform.contract(id),receivable_ledger UUID UNIQUE NOT NULL REFERENCES platform.ledger_registry(id),income_ledger UUID NOT NULL REFERENCES platform.ledger_registry(id));
CREATE TRIGGER loan_fee_book_immutable BEFORE UPDATE OR DELETE ON platform.loan_fee_book FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE UNIQUE INDEX loan_fee_evidence_unique ON platform.contract_delivery(tenant_id,(payload->'feeServicing'->'change'->>'evidenceReference')) WHERE kind='FEE_ASSESS';
CREATE TABLE platform.loan_collection_event (
 sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,id UUID UNIQUE NOT NULL,tenant_id UUID NOT NULL,contract_id UUID NOT NULL,
 issuer VARCHAR(512) NOT NULL,subject VARCHAR(255) NOT NULL,idempotency_key UUID NOT NULL,kind VARCHAR(24) NOT NULL CHECK(kind IN ('ASSIGN','CONTACT','PROMISE','CANCEL_PROMISE','ESCALATE','RESOLVE','REOPEN')),
 payload JSONB NOT NULL,snapshot JSONB NOT NULL,business_date DATE NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(issuer,subject,idempotency_key),FOREIGN KEY(tenant_id,contract_id) REFERENCES platform.contract(tenant_id,id)
);
CREATE INDEX loan_collection_event_history ON platform.loan_collection_event(tenant_id,contract_id,sequence DESC);
CREATE TRIGGER loan_collection_event_immutable BEFORE UPDATE OR DELETE ON platform.loan_collection_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE OR REPLACE FUNCTION platform.check_contract_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE NEW.kind WHEN 'OPEN' THEN 'CONTRACT_OPEN' WHEN 'REVERSE' THEN 'CONTRACT_CORRECTION' WHEN 'EARLY_CLOSE' THEN 'TERM_SERVICING' WHEN 'RENEW' THEN 'TERM_SERVICING' WHEN 'RESTRUCTURE' THEN 'LOAN_SERVICING' WHEN 'IMPAIR' THEN 'LOAN_SERVICING' WHEN 'WRITE_OFF' THEN 'LOAN_SERVICING' WHEN 'FEE_ASSESS' THEN 'LOAN_FEE' WHEN 'FEE_PAY' THEN 'LOAN_FEE' WHEN 'FEE_WAIVE' THEN 'LOAN_FEE' ELSE 'CONTRACT_EVENT' END) THEN RAISE EXCEPTION 'Contract event requires matching approval'; END IF;
 IF NEW.payload->'journal'<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Contract journal required'; END IF;
 IF NEW.kind='REVERSE' AND NOT EXISTS(SELECT 1 FROM platform.contract_reversal r JOIN platform.contract_delivery o ON o.id=r.original_id WHERE r.id=NEW.id AND r.contract_id=NEW.contract_id AND o.contract_id=NEW.contract_id AND o.tenant_id=NEW.tenant_id AND o.completed AND o.kind IN ('OPEN','ACCRUE','REPAY') AND o.business_date=NEW.business_date AND r.original_id=(NEW.payload->>'originalEventId')::uuid) THEN RAISE EXCEPTION 'Original contract event required'; END IF;
 RETURN NULL;
END; $$;
ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY','TD_OPEN_REVERSAL','LOAN_DISBURSE_REVERSAL','LOAN_REPAY_REVERSAL','TD_EARLY_CLOSE','TD_RENEW','LOAN_IMPAIR','LOAN_RESTRUCTURE','LOAN_WRITE_OFF','LOAN_FEE_ASSESS','LOAN_FEE_PAY','LOAN_FEE_WAIVE'));
