ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS'));
CREATE TABLE platform.lending_access(tenant_id UUID NOT NULL REFERENCES platform.tenant(id),staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),active BOOLEAN NOT NULL,payload JSONB NOT NULL,approval_id UUID NOT NULL REFERENCES platform.approval_request(id),PRIMARY KEY(tenant_id,staff_id));
CREATE TABLE platform.lending_access_event(sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,approval_id UUID UNIQUE NOT NULL REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),payload JSONB NOT NULL);
CREATE TRIGGER lending_access_event_immutable BEFORE UPDATE OR DELETE ON platform.lending_access_event FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TABLE platform.lending_approval_scope(request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),account_id UUID NOT NULL REFERENCES platform.account_registry(id),payload JSONB NOT NULL);
CREATE TRIGGER lending_scope_immutable BEFORE UPDATE OR DELETE ON platform.lending_approval_scope FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE TABLE platform.lending_approval_usage(request_id UUID PRIMARY KEY REFERENCES platform.approval_request(id),tenant_id UUID NOT NULL REFERENCES platform.tenant(id),staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),operation VARCHAR(40) NOT NULL,currency CHAR(3) NOT NULL,business_date DATE NOT NULL,amount NUMERIC(30,6) NOT NULL CHECK(amount>=0));
CREATE INDEX lending_daily_usage ON platform.lending_approval_usage(tenant_id,staff_id,operation,currency,business_date);
CREATE INDEX lending_access_history ON platform.lending_access_event(tenant_id,sequence DESC);
CREATE TRIGGER lending_usage_immutable BEFORE UPDATE OR DELETE ON platform.lending_approval_usage FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE FUNCTION platform.check_lending_access() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a WHERE a.id=NEW.approval_id AND a.action='LENDING_ACCESS' AND a.status='APPROVED' AND a.bank_id=NEW.tenant_id AND a.payload=NEW.payload AND a.payload->>'staffId'=NEW.staff_id::text AND a.reviewer_subject<>(SELECT subject FROM platform.staff_identity WHERE id=NEW.staff_id)) THEN RAISE EXCEPTION 'Lending grant requires independent approval'; END IF;
 IF TG_TABLE_NAME='lending_access' AND NOT EXISTS(SELECT 1 FROM platform.lending_access_event e WHERE e.approval_id=NEW.approval_id AND e.tenant_id=NEW.tenant_id AND e.staff_id=NEW.staff_id AND e.payload=NEW.payload) THEN RAISE EXCEPTION 'Lending grant requires immutable history'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER lending_grant_checked AFTER INSERT OR UPDATE ON platform.lending_access DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_lending_access();
CREATE CONSTRAINT TRIGGER lending_grant_history_checked AFTER INSERT ON platform.lending_access_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_lending_access();
CREATE FUNCTION platform.check_lending_usage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM platform.approval_request a JOIN platform.staff_identity si ON si.issuer=a.issuer AND si.subject=a.reviewer_subject JOIN platform.lending_approval_scope s ON s.request_id=a.id WHERE a.id=NEW.request_id AND a.status='APPROVED' AND a.bank_id=NEW.tenant_id AND si.id=NEW.staff_id AND s.payload->>'operation'=NEW.operation AND s.payload->>'currency'=NEW.currency AND (s.payload->>'amount')::numeric=NEW.amount AND (s.payload->>'businessDate')::date=NEW.business_date) THEN RAISE EXCEPTION 'Lending authority usage requires matching approved decision'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER lending_usage_checked AFTER INSERT ON platform.lending_approval_usage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_lending_usage();

-- Preserve the scope of existing requests from immutable approval previews. No grants
-- are inferred from platform administration. Old queued postings require current grants.
WITH legacy AS (
 SELECT a.*,a.proposed_values AS p FROM platform.approval_request a
 WHERE a.action IN ('CONTRACT_OPEN','CONTRACT_EVENT','CONTRACT_CORRECTION','LOAN_SERVICING','LOAN_FEE')
 AND a.proposed_values#>>'{contract,terms,type}'='LOAN'
), scopes AS (
 SELECT a.id,a.bank_id,(p#>>'{contract,account,id}')::uuid AS account_id,
 CASE WHEN action='CONTRACT_OPEN' THEN 'LOAN_DISBURSE'
      WHEN action='CONTRACT_CORRECTION' THEN 'LOAN_REVERSAL'
      ELSE 'LOAN_'||coalesce(payload->>'operation',payload->>'kind') END AS operation,
 p#>>'{contract,account,terms,currency}' AS currency,
 CASE WHEN action='CONTRACT_OPEN' THEN payload->>'principal'
      WHEN action='LOAN_FEE' OR (action='CONTRACT_EVENT' AND payload->>'kind'='REPAY') THEN payload->>'amount'
      WHEN action='CONTRACT_CORRECTION' THEN greatest(abs((p->>'customerMovement')::numeric),abs((p#>>'{after,interest}')::numeric-(p#>>'{before,interest}')::numeric))::text
      ELSE ((p#>>'{before,principal}')::numeric+(p#>>'{before,interest}')::numeric)::text END AS amount,
 coalesce(payload->>'businessDate',payload->>'startDate') AS business_date FROM legacy a
)
INSERT INTO platform.lending_approval_scope(request_id,tenant_id,account_id,payload)
SELECT id,bank_id,account_id,jsonb_build_object('accountId',account_id,'operation',operation,'currency',currency,'amount',amount,'businessDate',business_date) FROM scopes;
-- Credit decisions did not previously store an authority business day. Their migrated
-- scope uses the bank's cutover day for routing; historical credit decisions are not
-- charged against new daily authority. Pending decisions still undergo current checks.
INSERT INTO platform.lending_approval_scope(request_id,tenant_id,account_id,payload)
SELECT a.id,a.bank_id,c.account_id,jsonb_build_object('accountId',c.account_id,'operation',a.action,'currency',c.currency,'amount',c.payload->>'principal','businessDate',b.business_date)
FROM platform.approval_request a JOIN platform.credit_application c ON c.id=(a.payload->>'applicationId')::uuid AND c.tenant_id=a.bank_id
JOIN platform.bank_settings b ON b.tenant_id=a.bank_id WHERE a.action IN ('CREDIT_REVIEW','CREDIT_RELEASE');
INSERT INTO platform.lending_approval_usage(request_id,tenant_id,staff_id,operation,currency,business_date,amount)
SELECT a.id,a.bank_id,si.id,s.payload->>'operation',s.payload->>'currency',(s.payload->>'businessDate')::date,(s.payload->>'amount')::numeric
FROM platform.approval_request a JOIN platform.lending_approval_scope s ON s.request_id=a.id
JOIN platform.staff_identity si ON si.issuer=a.issuer AND si.subject=a.reviewer_subject
WHERE a.status='APPROVED' AND a.action NOT IN ('CREDIT_REVIEW','CREDIT_RELEASE');
ALTER TABLE platform.lending_access ADD CONSTRAINT lending_grant_active_matches CHECK(active=(payload->>'active')::boolean);
CREATE TRIGGER lending_grant_no_delete BEFORE DELETE ON platform.lending_access FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
