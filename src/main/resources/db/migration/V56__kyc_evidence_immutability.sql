-- P-10: KYC evidence is immutable, like every other evidence table.
-- Periodic reviews are append-only. Documents may change in exactly two ways: archiving after retention
-- (false -> true, once) and the one-off re-encryption from the source-derived key (master-v1) to the
-- field-key-derived key (pd1-doc). Nothing may delete them.

CREATE TRIGGER customer_kyc_review_immutable BEFORE UPDATE OR DELETE ON platform.customer_kyc_review
FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();

CREATE FUNCTION platform.guard_customer_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Customer documents are append-only evidence';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.customer_id, NEW.document_type, NEW.file_name, NEW.content_type, NEW.file_size,
        NEW.sha256, NEW.retention_until, NEW.uploaded_by, NEW.uploaded_at)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.customer_id, OLD.document_type, OLD.file_name, OLD.content_type, OLD.file_size,
        OLD.sha256, OLD.retention_until, OLD.uploaded_by, OLD.uploaded_at) THEN
        RAISE EXCEPTION 'Customer documents are append-only evidence';
    END IF;
    IF NEW.archived IS DISTINCT FROM OLD.archived AND NOT (OLD.archived = false AND NEW.archived = true) THEN
        RAISE EXCEPTION 'An archived customer document cannot be restored';
    END IF;
    IF (NEW.ciphertext, NEW.iv, NEW.key_id) IS DISTINCT FROM (OLD.ciphertext, OLD.iv, OLD.key_id)
       AND NOT (OLD.key_id = 'master-v1' AND NEW.key_id = 'pd1-doc') THEN
        RAISE EXCEPTION 'Customer document content can only be re-encrypted from the legacy key';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER customer_document_guard BEFORE UPDATE OR DELETE ON platform.customer_document
FOR EACH ROW EXECUTE FUNCTION platform.guard_customer_document();

-- P-10 approval actions for KYC reviews and document archiving (every earlier action kept).
ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check CHECK(action IN
 ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','CONTRACT_OPEN','CONTRACT_EVENT','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE','CUSTOMER_PARTIES','ACCOUNT_MANDATE','PERIOD_CLOSE_POLICY','PERIOD_CLOSE','REPORT_DEFINITION','SCREENING_LIST','MONITORING_POLICY','COMPLIANCE_ALERT_DISMISS','BANK_DUTY','BANK_DUTY_POLICY','LEGAL_HOLD','NOTIFICATION_TEMPLATE','LOAN_MORATORIUM','FX_RATE_FEED','FX_VALUATION_CORRECTION','TERM_STANDING_INSTRUCTION','KYC_REVIEW','CUSTOMER_DOCUMENT_ARCHIVE'));
