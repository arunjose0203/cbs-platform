ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT'));
ALTER TABLE platform.account_registry ADD CONSTRAINT account_registry_bank_unique UNIQUE(tenant_id,id);
CREATE TABLE platform.account_branch (
 tenant_id UUID NOT NULL,account_id UUID PRIMARY KEY,branch_id UUID NOT NULL,
 FOREIGN KEY(tenant_id,account_id) REFERENCES platform.account_registry(tenant_id,id),
 FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
CREATE TABLE platform.branch_transaction_limit (
 tenant_id UUID NOT NULL,branch_id UUID NOT NULL,scope_id UUID NOT NULL,
 currency VARCHAR(3) NOT NULL,operation VARCHAR(32) NOT NULL CHECK(operation IN ('TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD')),
 per_transaction NUMERIC(24,6) NOT NULL CHECK(per_transaction>0),daily_total NUMERIC(24,6) NOT NULL CHECK(daily_total>=per_transaction),
 PRIMARY KEY(branch_id,scope_id,currency,operation),
 FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
CREATE TABLE platform.operation_scope (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL,branch_id UUID NOT NULL,
 staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),
 FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
CREATE TRIGGER operation_scope_immutable BEFORE UPDATE OR DELETE ON platform.operation_scope FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE INDEX operation_scope_branch_staff ON platform.operation_scope(branch_id,staff_id);
