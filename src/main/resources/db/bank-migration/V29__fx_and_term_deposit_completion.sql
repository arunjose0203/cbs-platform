-- Bank Migration V29: Round 16 FX & Term Deposit Completion
ALTER TABLE bank.contract_event DROP CONSTRAINT contract_event_kind_check;
ALTER TABLE bank.contract_event ADD CHECK(kind IN ('OPEN','ACCRUE','REPAY','REDEEM','REVERSE','EARLY_CLOSE','RENEW','PARTIAL_WRITE_OFF','RECOVERY','PARTIAL_CLOSE'));

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
