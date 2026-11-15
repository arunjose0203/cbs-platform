package com.cbs.complaints;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Complaints and disputes case management (Round 20): the register, handling steps and approved resolutions. */
public final class ComplaintModels {
    private ComplaintModels() { }
    static final String TEXT = "[^\\p{Cntrl}]+", REFERENCE = "[A-Za-z0-9][A-Za-z0-9._/-]{0,119}", AMOUNT = "[0-9]{1,18}(\\.[0-9]{1,6})?",
            CURRENCY = "[A-Z]{3}", CODE = "[A-Z][A-Z_]{1,39}";

    public enum Channel { BRANCH, PHONE, EMAIL, LETTER, MOBILE, SOCIAL_MEDIA, REGULATOR }
    public enum Category { SERVICE, FEES_AND_CHARGES, TRANSACTION_DISPUTE, MOBILE_MONEY, CASH, ACCOUNT, LOAN, FRAUD, DATA_PRIVACY, STAFF_CONDUCT, OTHER }
    public enum Status { OPEN, RESOLVED }
    public enum Outcome { UPHELD, PARTLY_UPHELD, NOT_UPHELD, WITHDRAWN }
    public enum Redress {
        NONE, APOLOGY, EXPLANATION, CORRECTION, REFUND, COMPENSATION;
        /** Redress that pays the customer money, so it carries an amount and must be linked to an approved posting. */
        public boolean monetary() { return this == REFUND || this == COMPENSATION; }
    }
    public enum Filter { ALL, OPEN, RESOLVED, OVERDUE, UNASSIGNED, ESCALATED, REDRESS_PENDING }

    /** A complaint as received. {@code details} is the customer's account; {@code summary} is a short label without personal data. */
    public record Register(@NotNull UUID customerId, UUID accountId, @NotNull Channel channel, @NotNull Category category,
            @NotNull LocalDate receivedOn,
            @NotBlank @Size(max=200) @Pattern(regexp=TEXT) String summary,
            @NotBlank @Size(max=2000) @Pattern(regexp=TEXT) String details,
            @Size(max=120) @Pattern(regexp=REFERENCE) String transactionReference,
            @Pattern(regexp=AMOUNT) String disputedAmount, @Pattern(regexp=CURRENCY) String disputedCurrency) { }

    /** A handling step on the version of the complaint the operator saw: acknowledge, note, escalate or reopen. */
    public record Step(@PositiveOrZero long caseVersion, @NotBlank @Size(max=1000) @Pattern(regexp=TEXT) String note) { }
    public record Assign(@PositiveOrZero long caseVersion, @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}\\s]+") String assignee,
            @NotBlank @Size(max=1000) @Pattern(regexp=TEXT) String note) { }
    /** Links a monetary redress to the approved request that paid it. */
    public record RedressPosting(@PositiveOrZero long caseVersion, @NotNull UUID requestId,
            @NotBlank @Size(max=1000) @Pattern(regexp=TEXT) String note) { }

    /** The proposed outcome of a complaint. Maker-checker: approval action COMPLAINT_RESOLUTION. */
    public record Resolution(@PositiveOrZero long expectedVersion, @NotNull UUID complaintId, @PositiveOrZero long caseVersion,
            @NotNull Outcome outcome, @NotNull Redress redress,
            @Pattern(regexp=AMOUNT) String redressAmount, @Pattern(regexp=CURRENCY) String redressCurrency,
            @NotBlank @Pattern(regexp=CODE) String rootCause,
            @NotBlank @Size(max=1000) @Pattern(regexp=TEXT) String response,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }

    public record ComplaintView(UUID id, String reference, UUID customerId, String customerNumber, UUID accountId, String accountNumber,
            Channel channel, Category category, String summary, String transactionReference, String disputedAmount, String disputedCurrency,
            LocalDate receivedOn, LocalDate acknowledgeDue, LocalDate acknowledgedOn, boolean acknowledgementOverdue,
            LocalDate resolveDue, boolean resolutionOverdue, Status status, int escalationLevel, String assignee,
            Outcome outcome, Redress redress, String redressAmount, String redressCurrency, String rootCause, LocalDate resolvedOn,
            UUID resolutionApprovalId, UUID redressRequestId, boolean redressPending, boolean resolutionPending, int reopenedCount,
            long version, String createdBy, Instant createdAt) { }
    public record EventView(String action, String actorSubject, String note, Map<String, Object> details, long complaintVersion, Instant occurredAt) { }
    /** One complaint with the customer's own account and the handling history, newest first. */
    public record Detail(long version, LocalDate today, ComplaintView complaint, String details, List<EventView> events) { }
    public record Page(long version, LocalDate today, long total, List<ComplaintView> items) { }

    /** Counts for complaints received in a period, for management and the regulator's complaints return. */
    public record Summary(LocalDate from, LocalDate to, LocalDate today, long received, long open, long resolved, long overdueOpen,
            long acknowledgedLate, long resolvedWithinDue, long resolvedLate, long reopened, long redressPending,
            Map<String, Long> byCategory, Map<String, Long> byChannel, Map<String, Long> byOutcome, Map<String, String> redressByCurrency) { }
}
