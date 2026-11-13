package com.cbs.privacy;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Legal holds, data-subject requests, the retention matrix and the customer view log (Round 18). */
public final class PrivacyModels {
    private PrivacyModels() { }
    static final String TEXT = "[^\\p{Cntrl}]+", OPTIONAL_TEXT = "[^\\p{Cntrl}]*", REFERENCE = "[A-Za-z0-9][A-Za-z0-9._/-]{0,119}";

    public enum RequestType { ACCESS, RECTIFICATION, ERASURE, OBJECTION, PORTABILITY }
    public enum RequestStatus { OPEN, COMPLETED, REJECTED }

    /** Place ({@code active=true}) or release a legal hold on a customer's records. Maker-checker. */
    public record Hold(@PositiveOrZero long expectedVersion, @NotNull UUID customerId, boolean active,
            @NotBlank @Size(max=120) @Pattern(regexp=REFERENCE) String reference,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }
    public record HoldView(UUID customerId, String customerNumber, String reference, String reason, UUID approvalId, Instant updatedAt) { }

    /** A request received from a data subject, recorded on the day it arrived. */
    public record SubjectRequest(@NotNull UUID customerId, @NotNull RequestType type, @NotNull LocalDate receivedOn,
            @NotBlank @Size(max=120) @Pattern(regexp=REFERENCE) String reference,
            @NotNull @Size(max=500) @Pattern(regexp=OPTIONAL_TEXT) String details) { }
    public record Close(@NotNull RequestStatus outcome, @NotBlank @Pattern(regexp="[A-Z][A-Z_]{1,39}") String code,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String note) { }
    public record SubjectRequestView(UUID id, UUID customerId, String customerNumber, RequestType type, LocalDate receivedOn,
            LocalDate dueOn, RequestStatus status, boolean overdue, String reference, String details, String outcomeCode,
            String closingNote, String createdBy, Instant createdAt, Instant reportExportedAt, String closedBy, Instant closedAt) { }

    /** Why an erasure request cannot be carried out now; empty when nothing blocks it. */
    public record ErasureAssessment(UUID customerId, LocalDate relationshipEnded, LocalDate retainUntil, List<String> blockers) { }

    /** One row of the retention and archival matrix. */
    public record RetentionRule(String category, String records, int years, String starts, String basis, String endAction) { }
    public record RetentionCandidate(UUID customerId, String customerNumber, LocalDate relationshipEnded, LocalDate retainUntil) { }

    /** One access to a customer's record, from the audit trail. */
    public record View(String actorSubject, String action, Instant at, UUID correlationId) { }
}
