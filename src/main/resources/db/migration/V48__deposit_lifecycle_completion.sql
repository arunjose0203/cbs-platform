-- Round 12: Deposit lifecycle completion. See docs/deposit-lifecycle-completion.md.

-- Account dormancy: allow DORMANT in account registry state.
ALTER TABLE platform.account_registry DROP CONSTRAINT account_registry_state_check;
ALTER TABLE platform.account_registry ADD CONSTRAINT account_registry_state_check CHECK(state IN ('AWAITING_FUNDING','ACTIVE','FROZEN','DORMANT','CLOSED'));

-- Transaction limits: allow CLOSURE_SETTLEMENT_REVERSAL.
ALTER TABLE platform.transaction_limit DROP CONSTRAINT transaction_limit_operation_check;
ALTER TABLE platform.transaction_limit ADD CHECK(operation IN ('FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY','TD_OPEN_REVERSAL','LOAN_DISBURSE_REVERSAL','LOAN_REPAY_REVERSAL','TD_EARLY_CLOSE','TD_RENEW','LOAN_IMPAIR','LOAN_RESTRUCTURE','LOAN_WRITE_OFF','LOAN_FEE_ASSESS','LOAN_FEE_PAY','LOAN_FEE_WAIVE','CLOSURE_SETTLEMENT','CLOSURE_SETTLEMENT_REVERSAL'));

-- Encrypted KYC document storage with retention period.
CREATE TABLE platform.customer_document (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 document_type VARCHAR(40) NOT NULL,
 file_name VARCHAR(255) NOT NULL,
 content_type VARCHAR(100) NOT NULL,
 file_size BIGINT NOT NULL CHECK(file_size > 0),
 sha256 VARCHAR(64) NOT NULL,
 ciphertext BYTEA NOT NULL,
 iv BYTEA NOT NULL,
 key_id VARCHAR(64) NOT NULL,
 retention_until DATE NOT NULL,
 uploaded_by VARCHAR(255) NOT NULL,
 uploaded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 archived BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX customer_document_customer ON platform.customer_document(tenant_id, customer_id);

-- Periodic KYC reviews driven by risk rating.
CREATE TABLE platform.customer_kyc_review (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 risk_rating VARCHAR(16) NOT NULL CHECK(risk_rating IN ('LOW','MEDIUM','HIGH')),
 reviewed_at DATE NOT NULL,
 next_review_due DATE NOT NULL,
 reviewer_subject VARCHAR(255) NOT NULL,
 review_reference VARCHAR(160) NOT NULL,
 notes TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX customer_kyc_review_due ON platform.customer_kyc_review(tenant_id, next_review_due);
CREATE INDEX customer_kyc_review_customer ON platform.customer_kyc_review(tenant_id, customer_id, reviewed_at DESC);
