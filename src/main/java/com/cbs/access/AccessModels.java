package com.cbs.access;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AccessModels {
    private AccessModels() { }
    public record Staff(UUID id, String issuer, String subject, String displayName, String email,
            boolean active, long version, Instant revokedBefore, Instant createdAt) { }
    public record StaffInput(
            @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String subject,
            @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String displayName,
            @Email @Size(max=254) String email,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record StaffUpdate(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String displayName,
            @Email @Size(max=254) String email, @NotNull Boolean active,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Session(UUID id, Instant authenticatedAt, Instant expiresAt, Instant lastSeenAt, Instant revokedAt) { }
    public record Me(Staff staff, UUID sessionId, boolean platformAdmin, Instant sessionExpiresAt) { }
    public record IdentityAudit(UUID id, String actorSubject, UUID staffId, String action, String reason,
            Map<String,Object> details, Instant occurredAt) { }
    public enum BranchRole { TELLER, BRANCH_MANAGER, AUDITOR }
    public enum Permission { READ_BRANCH, CASH_TRANSACT, APPROVE_BRANCH }
    public record Branch(UUID id, UUID tenantId, UUID legalEntityId, String code, String displayName,
            boolean active, long version) { }
    public record BranchInput(@NotNull UUID legalEntityId,
            @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,31}") String code,
            @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String displayName,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record BranchUpdate(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String displayName,
            @NotNull Boolean active,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record BranchGrant(@NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID staffId,
            @NotNull BranchRole role, @NotNull Boolean active,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Grant(UUID staffId, String displayName, BranchRole role, boolean active) { }
    public record BranchAccess(UUID tenantId, UUID branchId, List<Permission> permissions) { }
}
