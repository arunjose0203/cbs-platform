ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION'));

CREATE TABLE platform.fx_book (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id), tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 legal_entity_id UUID NOT NULL, foreign_currency VARCHAR(3) NOT NULL, payload JSONB NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,legal_entity_id,foreign_currency)
);
CREATE TABLE platform.fx_book_ledger (
 ledger_id UUID PRIMARY KEY REFERENCES platform.ledger_registry(id), book_id UUID NOT NULL REFERENCES platform.fx_book(id)
);
CREATE TABLE platform.fx_closing_rate (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL,book_id UUID NOT NULL,
 business_date DATE NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,book_id) REFERENCES platform.fx_book(tenant_id,id)
);
CREATE TABLE platform.fx_valuation (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL,book_id UUID NOT NULL,
 business_date DATE NOT NULL,payload JSONB NOT NULL,maker_subject VARCHAR(255) NOT NULL,reviewer_issuer VARCHAR(512) NOT NULL,
 reviewer_subject VARCHAR(255) NOT NULL,correlation_id UUID NOT NULL,completed BOOLEAN NOT NULL DEFAULT false,
 attempts INTEGER NOT NULL DEFAULT 0,failure_code VARCHAR(80),last_attempt_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(book_id,business_date),FOREIGN KEY(tenant_id,book_id) REFERENCES platform.fx_book(tenant_id,id),CHECK(maker_subject<>reviewer_subject)
);
CREATE INDEX fx_valuation_pending ON platform.fx_valuation(created_at) WHERE NOT completed;
CREATE TRIGGER fx_book_immutable BEFORE UPDATE OR DELETE ON platform.fx_book FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER fx_book_ledger_immutable BEFORE UPDATE OR DELETE ON platform.fx_book_ledger FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER fx_closing_rate_immutable BEFORE UPDATE OR DELETE ON platform.fx_closing_rate FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TRIGGER fx_valuation_immutable BEFORE UPDATE OR DELETE ON platform.fx_valuation FOR EACH ROW EXECUTE FUNCTION platform.protect_fx_trade();
CREATE FUNCTION platform.check_fx_valuation_approval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_action TEXT;
BEGIN
 expected_action:=CASE TG_TABLE_NAME WHEN 'fx_book' THEN 'FX_BOOK' WHEN 'fx_closing_rate' THEN 'FX_CLOSING_RATE' ELSE 'FX_VALUATION' END;
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.id AND a.bank_id=NEW.tenant_id AND a.action=expected_action AND a.status='APPROVED') THEN RAISE EXCEPTION 'FX valuation requires matching approval'; END IF;
 IF TG_TABLE_NAME='fx_valuation' AND NEW.payload->'journal'<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.request_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.payload=NEW.payload->'journal') THEN RAISE EXCEPTION 'Valuation journal missing'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fx_book_approved AFTER INSERT ON platform.fx_book DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_fx_valuation_approval();
CREATE CONSTRAINT TRIGGER fx_closing_rate_approved AFTER INSERT ON platform.fx_closing_rate DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_fx_valuation_approval();
CREATE CONSTRAINT TRIGGER fx_valuation_approved AFTER INSERT ON platform.fx_valuation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_fx_valuation_approval();
