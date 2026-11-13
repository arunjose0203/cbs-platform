-- Bank Migration V29: Round 16 FX & Term Deposit Completion
ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
-- Every kind from V28 is kept: dropping RESTRUCTURE, IMPAIR, WRITE_OFF and the loan fee kinds would refuse those
-- postings and fail this migration on any bank that already holds them (fixed before release, P-10 review).
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','RESTRUCTURE','IMPAIR','WRITE_OFF','FEE_ASSESS','FEE_PAY','FEE_WAIVE','PARTIAL_WRITE_OFF','RECOVERY','PARTIAL_CLOSE'));

-- Allow recording FX valuation reversals on the same open business date
CREATE TABLE IF NOT EXISTS bank.fx_valuation_reversal (
 id UUID PRIMARY KEY,
 original_valuation_id UUID NOT NULL REFERENCES bank.fx_valuation(id),
 book_id UUID NOT NULL,
 business_date DATE NOT NULL,
 reversal_journal_id UUID NOT NULL REFERENCES bank.journal(id),
 write_transaction XID8 NOT NULL DEFAULT pg_current_xact_id()
);
CREATE TRIGGER fx_valuation_reversal_immutable BEFORE UPDATE OR DELETE ON bank.fx_valuation_reversal FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change();
