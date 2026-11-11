-- Per-account and per-contract lookups used by statements, closure checks and servicing.
-- See docs/production-readiness.md for the review that found them.
CREATE INDEX IF NOT EXISTS fx_entry_account ON bank.fx_entry (account_id);
CREATE INDEX IF NOT EXISTS contract_entry_account ON bank.contract_entry (account_id);
CREATE INDEX IF NOT EXISTS bank_contract_account ON bank.contract (account_id);
CREATE INDEX IF NOT EXISTS bank_account_customer ON bank.account (customer_id);
CREATE INDEX IF NOT EXISTS account_event_account ON bank.account_event (account_id);
CREATE INDEX IF NOT EXISTS customer_event_customer ON bank.customer_event (customer_id);
CREATE INDEX IF NOT EXISTS servicing_item_account ON bank.servicing_item (account_id);
CREATE INDEX IF NOT EXISTS loan_risk_event_contract ON bank.loan_risk_event (contract_id);
CREATE INDEX IF NOT EXISTS loan_fee_event_contract ON bank.loan_fee_event (contract_id);
