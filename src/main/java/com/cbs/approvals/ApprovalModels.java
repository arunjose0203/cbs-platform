package com.cbs.approvals;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalModels {
    private ApprovalModels() { }
    public enum Action { LENDING_ACCESS,LOAN_FEE, LOAN_SERVICING,CREDIT_POLICY,CREDIT_REVIEW,CREDIT_RELEASE, BANK_SUSPEND, BANK_RESUME, STAFF_UPDATE, BANK_SETTINGS, CHART_ACCOUNT, LEDGER_ACCOUNT, JOURNAL_POST, DAY_CLOSE, DEPOSIT_PRODUCT, CUSTOMER_CHANGE, DEPOSIT_ACCOUNT, ACCOUNT_FUNDING, ACCOUNT_OPERATION, TRANSACTION_LIMIT, ACCOUNT_BRANCH, BRANCH_LIMIT, CASH_OPERATION, CASH_DENOMINATIONS, SERVICING_POLICY, SERVICING_RUN, PAYMENT_ORDER, PAYMENT_RECONCILE, STATEMENT_ACTION, PAYMENT_CASE, PAYMENT_ACCESS, FX_BOOK,FX_CLOSING_RATE,FX_VALUATION,FX_RATE, FX_RATE_WITHDRAW,FX_TRADE,FX_REVERSAL,TERM_SERVICING,CONTRACT_CORRECTION,CONTRACT_OPEN,CONTRACT_EVENT, COMPLIANCE_REVIEW, NOTIFICATION_PREFERENCE, CUSTOMER_PARTIES, ACCOUNT_MANDATE, PERIOD_CLOSE_POLICY, PERIOD_CLOSE, REPORT_DEFINITION, SCREENING_LIST, MONITORING_POLICY, COMPLIANCE_ALERT_DISMISS, BANK_DUTY, BANK_DUTY_POLICY, LEGAL_HOLD }
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED, STALE }
    public enum Scope { INBOX, MINE, HISTORY }
    public enum Decision { APPROVE, REJECT, CANCEL }
    public record DecisionInput(@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Request(UUID id, Action action, UUID targetId, UUID bankId, String targetLabel,
            long expectedVersion, String makerSubject, String reason, Map<String,Object> beforeValues,
            Map<String,Object> proposedValues, Status status, String reviewerSubject, String decisionReason,
            Instant createdAt, Instant decidedAt) { }
    public record Event(UUID id, String actorSubject, String action, String reason, UUID correlationId, Instant occurredAt) { }
    public record Details(Request request, List<Event> events) { }
}
