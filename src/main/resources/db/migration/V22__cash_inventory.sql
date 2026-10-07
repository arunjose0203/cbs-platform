ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS'));
CREATE TABLE platform.cash_denomination (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),currency VARCHAR(3) NOT NULL,
 value NUMERIC(24,6) NOT NULL CHECK(value>0),type VARCHAR(4) NOT NULL CHECK(type IN ('NOTE','COIN')),
 PRIMARY KEY(tenant_id,currency,value)
);
ALTER TABLE platform.cash_location ADD COLUMN inventory_enabled BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE platform.cash_receipt_print (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL REFERENCES platform.tenant(id),request_id UUID NOT NULL REFERENCES platform.cash_delivery(request_id),
 issuer VARCHAR(512) NOT NULL,subject VARCHAR(255) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER cash_receipt_print_immutable BEFORE UPDATE OR DELETE ON platform.cash_receipt_print FOR EACH ROW EXECUTE FUNCTION platform.reject_cash_exception_change();
