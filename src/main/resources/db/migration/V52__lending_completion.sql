-- Round 15: Lending Completion (Matrix rows F30-F32)
-- Bureau inquiry/reporting, moratoria, partial write-off, recovery, ECL staging, arrears snapshots, collateral docs

ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY','LEGAL_HOLD','NOTIFICATION_TEMPLATE','LOAN_MORATORIUM'));

ALTER TABLE platform.contract_delivery DROP CONSTRAINT contract_delivery_kind_check;
ALTER TABLE platform.contract_delivery ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF','FEE_ASSESS','FEE_PAY','FEE_WAIVE','PARTIAL_WRITE_OFF','RECOVERY'));

ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY','TD_OPEN_REVERSAL','LOAN_DISBURSE_REVERSAL','LOAN_REPAY_REVERSAL','TD_EARLY_CLOSE','TD_RENEW','LOAN_IMPAIR','LOAN_RESTRUCTURE','LOAN_WRITE_OFF','LOAN_FEE_ASSESS','LOAN_FEE_PAY','LOAN_FEE_WAIVE','LOAN_PARTIAL_WRITE_OFF','LOAN_RECOVERY','LOAN_FEE_REVERSAL'));

CREATE OR REPLACE FUNCTION platform.check_contract_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.status='APPROVED' AND a.action=CASE NEW.kind WHEN 'OPEN' THEN 'CONTRACT_OPEN' WHEN 'REVERSE' THEN 'CONTRACT_CORRECTION' WHEN 'EARLY_CLOSE' THEN 'TERM_SERVICING' WHEN 'RENEW' THEN 'TERM_SERVICING' WHEN 'RESTRUCTURE' THEN 'LOAN_SERVICING' WHEN 'IMPAIR' THEN 'LOAN_SERVICING' WHEN 'WRITE_OFF' THEN 'LOAN_SERVICING' WHEN 'PARTIAL_WRITE_OFF' THEN 'LOAN_SERVICING' WHEN 'RECOVERY' THEN 'LOAN_SERVICING' WHEN 'FEE_ASSESS' THEN 'LOAN_FEE' WHEN 'FEE_PAY' THEN 'LOAN_FEE' WHEN 'FEE_WAIVE' THEN 'LOAN_FEE' ELSE 'CONTRACT_EVENT' END) THEN RAISE EXCEPTION 'Contract event requires matching approval'; END IF;
 IF NEW.payload->'journal'<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Contract journal required'; END IF;
 IF NEW.kind='REVERSE' AND NOT EXISTS(SELECT 1 FROM platform.contract_reversal r JOIN platform.contract_delivery o ON o.id=r.original_id WHERE r.id=NEW.id AND r.contract_id=NEW.contract_id AND o.contract_id=NEW.contract_id AND o.tenant_id=NEW.tenant_id AND o.completed AND o.kind IN ('OPEN','ACCRUE','REPAY','FEE_PAY') AND o.business_date=NEW.business_date AND r.original_id=(NEW.payload->>'originalEventId')::uuid) THEN RAISE EXCEPTION 'Original contract event required'; END IF;
 RETURN NULL;
END; $$;

CREATE TABLE platform.credit_bureau_inquiry (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL,
 national_id VARCHAR(50) NOT NULL,
 provider VARCHAR(50) NOT NULL,
 score INTEGER NOT NULL,
 risk_grade VARCHAR(20) NOT NULL,
 open_accounts INTEGER NOT NULL DEFAULT 0,
 delinquent_accounts INTEGER NOT NULL DEFAULT 0,
 total_exposure NUMERIC(30,6) NOT NULL DEFAULT 0,
 delinquent_amount NUMERIC(30,6) NOT NULL DEFAULT 0,
 report_payload JSONB NOT NULL,
 inquired_by VARCHAR(255) NOT NULL,
 inquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES platform.customer_registry(tenant_id,id)
);
CREATE INDEX credit_bureau_inquiry_customer ON platform.credit_bureau_inquiry(tenant_id, customer_id, inquired_at DESC);
CREATE TRIGGER credit_bureau_inquiry_immutable BEFORE UPDATE OR DELETE ON platform.credit_bureau_inquiry FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

CREATE TABLE platform.loan_moratorium (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 contract_id UUID NOT NULL,
 moratorium_type VARCHAR(30) NOT NULL CHECK(moratorium_type IN ('INTEREST_ONLY','FULL_PAYMENT_HOLIDAY')),
 start_date DATE NOT NULL,
 end_date DATE NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_by VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES platform.contract(tenant_id,id),
 CHECK(end_date >= start_date)
);
CREATE INDEX loan_moratorium_contract ON platform.loan_moratorium(tenant_id, contract_id, end_date DESC);
CREATE TRIGGER loan_moratorium_immutable BEFORE UPDATE OR DELETE ON platform.loan_moratorium FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

CREATE TABLE platform.loan_arrears_snapshot (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 contract_id UUID NOT NULL,
 business_date DATE NOT NULL,
 last_event_id UUID NOT NULL,
 snapshot_report JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES platform.contract(tenant_id,id)
);
CREATE INDEX loan_arrears_snapshot_lookup ON platform.loan_arrears_snapshot(tenant_id, contract_id, business_date DESC);
CREATE TRIGGER loan_arrears_snapshot_immutable BEFORE UPDATE OR DELETE ON platform.loan_arrears_snapshot FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

CREATE TABLE platform.loan_collateral_document (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 application_id UUID NOT NULL,
 customer_document_id UUID NOT NULL REFERENCES platform.customer_document(id),
 security_reference VARCHAR(160) NOT NULL,
 verified_by VARCHAR(255) NOT NULL,
 verified_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,application_id) REFERENCES platform.credit_application(tenant_id,id),
 UNIQUE(tenant_id,application_id,customer_document_id)
);
CREATE TRIGGER loan_collateral_document_immutable BEFORE UPDATE OR DELETE ON platform.loan_collateral_document FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
