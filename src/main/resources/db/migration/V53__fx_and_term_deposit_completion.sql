-- Round 16: FX and Term-Deposit Completion (Matrix rows F26-F28)
-- Partial term-deposit closure, standing renewal instructions, cross rates, rate feed, valuation corrections, monetary item translation

ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY','LEGAL_HOLD','NOTIFICATION_TEMPLATE','LOAN_MORATORIUM','FX_RATE_FEED','FX_VALUATION_CORRECTION','TERM_STANDING_INSTRUCTION'));

ALTER TABLE platform.contract_delivery DROP CONSTRAINT contract_delivery_kind_check;
ALTER TABLE platform.contract_delivery ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF','FEE_ASSESS','FEE_PAY','FEE_WAIVE','PARTIAL_WRITE_OFF','RECOVERY','PARTIAL_CLOSE'));

ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY','TD_OPEN_REVERSAL','LOAN_DISBURSE_REVERSAL','LOAN_REPAY_REVERSAL','LOAN_FEE_ASSESS','LOAN_FEE_PAY','LOAN_FEE_WAIVE','LOAN_IMPAIR','LOAN_RESTRUCTURE','LOAN_WRITE_OFF','TD_EARLY_CLOSE','TD_RENEW','CLOSURE_SETTLEMENT','CLOSURE_SETTLEMENT_REVERSAL','LOAN_PARTIAL_WRITE_OFF','LOAN_RECOVERY','LOAN_FEE_REVERSAL','TD_PARTIAL_CLOSE'));

CREATE OR REPLACE FUNCTION platform.check_contract_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE NEW.kind WHEN 'OPEN' THEN 'CONTRACT_OPEN' WHEN 'REVERSE' THEN 'CONTRACT_CORRECTION' WHEN 'EARLY_CLOSE' THEN 'TERM_SERVICING' WHEN 'PARTIAL_CLOSE' THEN 'TERM_SERVICING' WHEN 'RENEW' THEN 'TERM_SERVICING' WHEN 'RESTRUCTURE' THEN 'LOAN_SERVICING' WHEN 'IMPAIR' THEN 'LOAN_SERVICING' WHEN 'WRITE_OFF' THEN 'LOAN_SERVICING' WHEN 'PARTIAL_WRITE_OFF' THEN 'LOAN_SERVICING' WHEN 'RECOVERY' THEN 'LOAN_SERVICING' WHEN 'FEE_ASSESS' THEN 'LOAN_FEE' WHEN 'FEE_PAY' THEN 'LOAN_FEE' WHEN 'FEE_WAIVE' THEN 'LOAN_FEE' ELSE 'CONTRACT_EVENT' END) THEN RAISE EXCEPTION 'Contract event requires matching approval'; END IF;
 IF NEW.payload->'journal'<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Contract journal required'; END IF;
 IF NEW.kind='REVERSE' AND NOT EXISTS(SELECT 1 FROM platform.contract_reversal r JOIN platform.contract_delivery o ON o.id=r.original_id WHERE r.id=NEW.id AND r.contract_id=NEW.contract_id AND o.contract_id=NEW.contract_id AND o.tenant_id=NEW.tenant_id AND o.completed AND o.kind IN ('OPEN','ACCRUE','REPAY','FEE_PAY') AND o.business_date=NEW.business_date AND r.original_id=(NEW.payload->>'originalEventId')::uuid) THEN RAISE EXCEPTION 'Original contract event required'; END IF;
 RETURN NULL;
END; $$;

-- Standing renewal instructions
CREATE TABLE platform.term_standing_instruction (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 contract_id UUID NOT NULL,
 instruction_type VARCHAR(50) NOT NULL CHECK(instruction_type IN ('AUTOMATIC_RENEWAL_CAPITALIZE','AUTOMATIC_RENEWAL_PAYOUT','TRANSFER_TO_ACCOUNT_AT_MATURITY')),
 target_account_id UUID,
 renewal_period_months INTEGER,
 renewal_rate_percent VARCHAR(20),
 active BOOLEAN NOT NULL DEFAULT true,
 reason VARCHAR(500) NOT NULL,
 created_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX term_standing_instruction_contract ON platform.term_standing_instruction(tenant_id, contract_id);
CREATE TRIGGER term_standing_instruction_immutable BEFORE UPDATE OR DELETE ON platform.term_standing_instruction FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

-- FX rate feed staging
CREATE TABLE platform.fx_rate_feed (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 feed_source VARCHAR(100) NOT NULL,
 effective_date DATE NOT NULL,
 total_rates INTEGER NOT NULL DEFAULT 0,
 payload JSONB NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'STAGED' CHECK(status IN ('STAGED','APPROVED','REJECTED')),
 created_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX fx_rate_feed_tenant ON platform.fx_rate_feed(tenant_id, effective_date DESC);
CREATE TRIGGER fx_rate_feed_immutable BEFORE UPDATE OR DELETE ON platform.fx_rate_feed FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

-- FX valuation correction
CREATE TABLE platform.fx_valuation_correction (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 book_id UUID NOT NULL,
 original_valuation_id UUID NOT NULL,
 business_date DATE NOT NULL,
 reversal_journal_id UUID NOT NULL,
 reason VARCHAR(500) NOT NULL,
 corrected_by VARCHAR(255) NOT NULL,
 corrected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX fx_valuation_correction_book ON platform.fx_valuation_correction(tenant_id, book_id, business_date);
CREATE TRIGGER fx_valuation_correction_immutable BEFORE UPDATE OR DELETE ON platform.fx_valuation_correction FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

-- Full-bank monetary item translation run
CREATE TABLE platform.monetary_translation_run (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 business_date DATE NOT NULL,
 functional_currency VARCHAR(3) NOT NULL,
 total_monetary_items INTEGER NOT NULL DEFAULT 0,
 net_unrealized_gain_loss NUMERIC(30,6) NOT NULL DEFAULT 0,
 payload JSONB NOT NULL,
 created_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX monetary_translation_run_tenant ON platform.monetary_translation_run(tenant_id, business_date DESC);
CREATE TRIGGER monetary_translation_run_immutable BEFORE UPDATE OR DELETE ON platform.monetary_translation_run FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
