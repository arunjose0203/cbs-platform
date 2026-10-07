ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE'));
ALTER TABLE platform.branch_transaction_limit DROP CONSTRAINT branch_transaction_limit_operation_check;
ALTER TABLE platform.branch_transaction_limit ADD CHECK(operation IN ('TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE'));
CREATE TABLE platform.cash_adjustment (
 case_id UUID PRIMARY KEY REFERENCES platform.cash_exception(id),
 request_id UUID NOT NULL UNIQUE REFERENCES platform.approval_request(id)
);
CREATE TRIGGER cash_adjustment_immutable BEFORE UPDATE OR DELETE ON platform.cash_adjustment
 FOR EACH ROW EXECUTE FUNCTION platform.reject_cash_exception_change();
ALTER TABLE platform.cash_exception_resolution ADD COLUMN adjustment_request_id UUID UNIQUE REFERENCES platform.cash_delivery(request_id);
