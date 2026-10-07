ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN'));
CREATE TABLE platform.servicing_policy (
 id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
 product_id UUID NOT NULL,product_revision INTEGER NOT NULL,legal_entity_id UUID NOT NULL,effective_from DATE NOT NULL,
 currency VARCHAR(3) NOT NULL,payload JSONB NOT NULL,revision BIGINT GENERATED ALWAYS AS IDENTITY
);
CREATE TRIGGER servicing_policy_immutable BEFORE UPDATE OR DELETE ON platform.servicing_policy FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TABLE platform.servicing_run (
 request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),business_date DATE NOT NULL,
 payload JSONB NOT NULL,maker_subject VARCHAR(255) NOT NULL,reviewer_issuer VARCHAR(512) NOT NULL,reviewer_subject VARCHAR(255) NOT NULL,correlation_id UUID NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false,attempts INTEGER NOT NULL DEFAULT 0,failure_code VARCHAR(80),last_attempt_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,business_date)
);
CREATE TABLE platform.servicing_posting (
 request_id UUID PRIMARY KEY REFERENCES platform.journal_delivery(request_id),run_id UUID NOT NULL REFERENCES platform.servicing_run(request_id)
);

-- A servicing approval authorizes several account journals; retain an explicit approval FK.
ALTER TABLE platform.journal_delivery ADD COLUMN approval_id UUID REFERENCES platform.approval_request(id);
UPDATE platform.journal_delivery SET approval_id=request_id;
ALTER TABLE platform.journal_delivery ALTER COLUMN approval_id SET NOT NULL;
ALTER TABLE platform.journal_delivery DROP CONSTRAINT journal_delivery_request_id_fkey;
CREATE FUNCTION platform.default_journal_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.approval_id:=COALESCE(NEW.approval_id,NEW.request_id); RETURN NEW; END $$;
CREATE TRIGGER journal_approval_default BEFORE INSERT ON platform.journal_delivery FOR EACH ROW EXECUTE FUNCTION platform.default_journal_approval();
CREATE FUNCTION platform.check_journal_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.request_id<>NEW.approval_id AND NOT EXISTS (
 SELECT 1 FROM platform.servicing_posting p JOIN platform.servicing_run r ON r.request_id=p.run_id
 WHERE p.request_id=NEW.request_id AND r.request_id=NEW.approval_id AND r.tenant_id=NEW.tenant_id
 ) THEN RAISE EXCEPTION 'Journal must belong to its approved servicing batch'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_approval_check AFTER INSERT OR UPDATE ON platform.journal_delivery
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_journal_approval();
