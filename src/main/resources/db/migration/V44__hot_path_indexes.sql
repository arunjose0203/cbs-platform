-- Hot-path indexes found by the production-readiness review (docs/production-readiness.md).
-- Every statement is a plain CREATE INDEX: tables are still small before go-live. For a bank that is
-- already live with large volumes, build the same indexes CONCURRENTLY first; these statements then
-- become no-ops because of IF NOT EXISTS.

-- Daily limit consumption (TransactionLimits): an index range per bank, business day and kind
-- instead of a scan through the bank's whole history on every posting.
CREATE INDEX IF NOT EXISTS account_operation_daily ON platform.account_operation
    (tenant_id, (payload->'change'->>'businessDate'), (payload->'change'->>'kind'));
CREATE INDEX IF NOT EXISTS funding_delivery_daily ON platform.funding_delivery
    (tenant_id, (payload->'journal'->>'businessDate'));
CREATE INDEX IF NOT EXISTS cash_delivery_daily ON platform.cash_delivery
    (tenant_id, (payload->'change'->>'businessDate'), (payload->'change'->>'kind'));
CREATE INDEX IF NOT EXISTS contract_delivery_daily ON platform.contract_delivery (tenant_id, business_date, kind);
CREATE INDEX IF NOT EXISTS fx_trade_daily ON platform.fx_trade (tenant_id, business_date);

-- Per-account history, closure settlement checks and transfer destinations.
CREATE INDEX IF NOT EXISTS account_operation_account ON platform.account_operation (account_id, created_at);
CREATE INDEX IF NOT EXISTS account_operation_destination ON platform.account_operation (destination_id)
    WHERE destination_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS funding_delivery_account ON platform.funding_delivery (account_id, created_at);
CREATE INDEX IF NOT EXISTS contract_delivery_account ON platform.contract_delivery (account_id, created_at);
CREATE INDEX IF NOT EXISTS contract_delivery_contract ON platform.contract_delivery (contract_id, created_at);
CREATE INDEX IF NOT EXISTS payment_order_account ON platform.payment_order (account_id, created_at);
CREATE INDEX IF NOT EXISTS notification_account ON platform.notification (account_id) WHERE account_id IS NOT NULL;

-- Approval application looks registries up by the approving request.
CREATE INDEX IF NOT EXISTS account_registry_request ON platform.account_registry (request_id);
CREATE INDEX IF NOT EXISTS customer_registry_request ON platform.customer_registry (request_id);
CREATE INDEX IF NOT EXISTS ledger_registry_request ON platform.ledger_registry (request_id);

-- Per-bank delivery listings and reconciliation lookups.
CREATE INDEX IF NOT EXISTS customer_delivery_tenant ON platform.customer_delivery (tenant_id);
CREATE INDEX IF NOT EXISTS account_delivery_tenant ON platform.account_delivery (tenant_id);
CREATE INDEX IF NOT EXISTS ledger_delivery_tenant ON platform.ledger_delivery (tenant_id);
CREATE INDEX IF NOT EXISTS statement_line_decision ON platform.statement_line (decision_id) WHERE decision_id IS NOT NULL;
