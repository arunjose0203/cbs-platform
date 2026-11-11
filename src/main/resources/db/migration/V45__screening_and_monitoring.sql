-- Automated compliance: approved screening lists with name screening, and post-event transaction
-- monitoring. See docs/screening-and-transaction-monitoring.md.
ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS'));

-- A list version is uploaded as an inert DRAFT; only an approved activation makes it ACTIVE.
CREATE TABLE platform.screening_list (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 name VARCHAR(40) NOT NULL CHECK(name ~ '^[A-Z0-9][A-Z0-9_-]{0,39}$'),
 category VARCHAR(12) NOT NULL CHECK(category IN ('SANCTIONS','PEP','INTERNAL')),
 source VARCHAR(200) NOT NULL,
 threshold NUMERIC(3,2) NOT NULL CHECK(threshold BETWEEN 0.70 AND 1.00),
 sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 entry_count INTEGER NOT NULL CHECK(entry_count BETWEEN 1 AND 100000),
 status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','SUPERSEDED')),
 issuer VARCHAR(512) NOT NULL,
 uploaded_by VARCHAR(255) NOT NULL,
 approval_id UUID REFERENCES platform.approval_request(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 activated_at TIMESTAMPTZ,
 UNIQUE(tenant_id,name,sha256),
 CHECK((status='DRAFT')=(approval_id IS NULL))
);
CREATE UNIQUE INDEX screening_list_active ON platform.screening_list(tenant_id,name) WHERE status='ACTIVE';

CREATE TABLE platform.screening_entry (
 list_id UUID NOT NULL REFERENCES platform.screening_list(id),
 entry_ref VARCHAR(80) NOT NULL,
 entity_type VARCHAR(12) NOT NULL CHECK(entity_type IN ('INDIVIDUAL','ENTITY')),
 names JSONB NOT NULL CHECK(jsonb_typeof(names)='array' AND jsonb_array_length(names) BETWEEN 1 AND 20),
 birth_date DATE,
 country CHAR(2) CHECK(country ~ '^[A-Z]{2}$'),
 program VARCHAR(200),
 entry_hash CHAR(64) NOT NULL,
 PRIMARY KEY(list_id,entry_ref)
);

-- A re-screen of every customer after a list version becomes active, processed in resumable chunks.
CREATE TABLE platform.screening_run (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 list_id UUID NOT NULL REFERENCES platform.screening_list(id),
 status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DONE','CANCELLED')),
 cursor_id UUID,
 screened INTEGER NOT NULL DEFAULT 0,
 hits INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 finished_at TIMESTAMPTZ
);
CREATE INDEX screening_run_pending ON platform.screening_run(created_at) WHERE status='PENDING';

CREATE TABLE platform.screening_hit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 customer_version BIGINT NOT NULL,
 list_id UUID NOT NULL,
 entry_ref VARCHAR(80) NOT NULL,
 list_name VARCHAR(40) NOT NULL,
 entry_hash CHAR(64) NOT NULL,
 score NUMERIC(4,3) NOT NULL CHECK(score BETWEEN 0 AND 1),
 customer_name VARCHAR(300) NOT NULL,
 matched_name VARCHAR(300) NOT NULL,
 trigger VARCHAR(12) NOT NULL CHECK(trigger IN ('CUSTOMER','LIST')),
 status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DISMISSED','ESCALATED')),
 version BIGINT NOT NULL DEFAULT 0,
 case_id UUID REFERENCES platform.compliance_case(id),
 decided_by VARCHAR(255),
 approval_id UUID REFERENCES platform.approval_request(id),
 decision JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 decided_at TIMESTAMPTZ,
 FOREIGN KEY(list_id,entry_ref) REFERENCES platform.screening_entry(list_id,entry_ref),
 UNIQUE(customer_id,customer_version,list_id,entry_ref),
 CHECK((status='ESCALATED')=(case_id IS NOT NULL)),
 CHECK((status='DISMISSED')=(approval_id IS NOT NULL))
);
CREATE INDEX screening_hit_open ON platform.screening_hit(tenant_id,customer_id) WHERE status='OPEN';
CREATE INDEX screening_hit_queue ON platform.screening_hit(tenant_id,status,created_at);
CREATE INDEX screening_hit_dismissed ON platform.screening_hit(customer_id,list_name,entry_ref) WHERE status='DISMISSED';

-- Monitoring rules per bank and currency; changes need independent approval.
CREATE TABLE platform.monitoring_policy (
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 currency CHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 rules JSONB NOT NULL,
 approval_id UUID NOT NULL REFERENCES platform.approval_request(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,currency)
);

CREATE TABLE platform.monitoring_cursor (
 tenant_id UUID PRIMARY KEY REFERENCES platform.tenant(id),
 watermark TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- One row per posted customer movement seen by monitoring (a transfer gives an OUT and an IN row).
CREATE TABLE platform.monitoring_event (
 source_id UUID NOT NULL,
 account_id UUID NOT NULL REFERENCES platform.account_registry(id),
 direction VARCHAR(3) NOT NULL CHECK(direction IN ('IN','OUT')),
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 channel VARCHAR(10) NOT NULL CHECK(channel IN ('CASH','FUNDING','TRANSFER','WITHDRAWAL','MOBILE')),
 amount NUMERIC(24,6) NOT NULL CHECK(amount>0),
 currency CHAR(3) NOT NULL,
 business_date DATE NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL,
 seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(source_id,account_id,direction)
);
CREATE INDEX monitoring_event_account ON platform.monitoring_event(tenant_id,account_id,business_date);

CREATE TABLE platform.monitoring_alert (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 account_id UUID NOT NULL REFERENCES platform.account_registry(id),
 customer_id UUID NOT NULL REFERENCES platform.customer_registry(id),
 rule VARCHAR(16) NOT NULL CHECK(rule IN ('LARGE_CASH','STRUCTURING','VELOCITY','UNUSUAL_AMOUNT')),
 dedupe_key VARCHAR(200) NOT NULL,
 currency CHAR(3) NOT NULL,
 business_date DATE NOT NULL,
 amount NUMERIC(24,6) NOT NULL,
 details JSONB NOT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DISMISSED','ESCALATED')),
 version BIGINT NOT NULL DEFAULT 0,
 case_id UUID REFERENCES platform.compliance_case(id),
 decided_by VARCHAR(255),
 approval_id UUID REFERENCES platform.approval_request(id),
 decision JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 decided_at TIMESTAMPTZ,
 UNIQUE(tenant_id,dedupe_key),
 CHECK((status='ESCALATED')=(case_id IS NOT NULL)),
 CHECK((status='DISMISSED')=(approval_id IS NOT NULL))
);
CREATE INDEX monitoring_alert_queue ON platform.monitoring_alert(tenant_id,status,created_at);

-- Monitoring reads completed postings by completion time.
CREATE INDEX IF NOT EXISTS account_operation_completed ON platform.account_operation(tenant_id,completed_at) WHERE completed;
CREATE INDEX IF NOT EXISTS funding_delivery_completed ON platform.funding_delivery(tenant_id,completed_at) WHERE completed;
CREATE INDEX IF NOT EXISTS cash_delivery_completed ON platform.cash_delivery(tenant_id,completed_at) WHERE completed;
CREATE INDEX IF NOT EXISTS payment_order_created ON platform.payment_order(tenant_id,created_at);

-- Lists, entries and monitored movements are evidence: never rewritten.
CREATE FUNCTION platform.forbid_compliance_evidence_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- The only permitted change: a list version's status moving DRAFT/ACTIVE -> ACTIVE/SUPERSEDED.
  IF TG_TABLE_NAME='screening_list' AND TG_OP='UPDATE' THEN
    IF OLD.status IN ('DRAFT','ACTIVE') AND NEW.status IN ('ACTIVE','SUPERSEDED') AND NEW.sha256=OLD.sha256
       AND NEW.entry_count=OLD.entry_count AND NEW.threshold=OLD.threshold AND NEW.name=OLD.name
       AND NEW.tenant_id=OLD.tenant_id AND NEW.category=OLD.category AND NEW.source=OLD.source THEN
      RETURN NEW;
    END IF;
  END IF;
  RAISE EXCEPTION 'compliance evidence is immutable' USING ERRCODE='P0001';
END $$;
CREATE TRIGGER screening_list_immutable BEFORE UPDATE OR DELETE ON platform.screening_list
 FOR EACH ROW EXECUTE FUNCTION platform.forbid_compliance_evidence_change();
CREATE TRIGGER screening_entry_immutable BEFORE UPDATE OR DELETE ON platform.screening_entry
 FOR EACH ROW EXECUTE FUNCTION platform.forbid_compliance_evidence_change();
CREATE TRIGGER monitoring_event_immutable BEFORE UPDATE OR DELETE ON platform.monitoring_event
 FOR EACH ROW EXECUTE FUNCTION platform.forbid_compliance_evidence_change();
