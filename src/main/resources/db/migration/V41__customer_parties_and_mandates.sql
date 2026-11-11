-- Organization customers, their related persons, and account mandates (joint holders,
-- signatories, guardians) with signing rules. All changes need independent approval.
ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE'));

ALTER TABLE platform.customer_registry ADD COLUMN customer_type VARCHAR(12) NOT NULL DEFAULT 'INDIVIDUAL' CHECK(customer_type IN ('INDIVIDUAL','ORGANIZATION'));

-- Directors, beneficial owners and representatives of an organization customer.
CREATE TABLE platform.customer_party (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 organization_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 person_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 role VARCHAR(24) NOT NULL CHECK(role IN ('DIRECTOR','BENEFICIAL_OWNER','REPRESENTATIVE')),
 ownership_percent NUMERIC(5,2) CHECK(ownership_percent IS NULL OR (ownership_percent>0 AND ownership_percent<=100)),
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 PRIMARY KEY(organization_id,person_id,role),
 CHECK(organization_id<>person_id),
 CHECK((role='BENEFICIAL_OWNER')=(ownership_percent IS NOT NULL))
);
CREATE INDEX customer_party_person ON platform.customer_party(person_id);

-- Who may operate an account and how many must sign.
CREATE TABLE platform.account_mandate (
 account_id UUID PRIMARY KEY REFERENCES platform.account_registry(id),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 signing_rule VARCHAR(10) NOT NULL CHECK(signing_rule IN ('ANY_ONE','ANY_TWO','ALL')),
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE platform.account_party (
 account_id UUID NOT NULL REFERENCES platform.account_mandate(account_id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 role VARCHAR(16) NOT NULL CHECK(role IN ('JOINT_HOLDER','SIGNATORY','GUARDIAN')),
 PRIMARY KEY(account_id,customer_id)
);
CREATE INDEX account_party_customer ON platform.account_party(customer_id);
