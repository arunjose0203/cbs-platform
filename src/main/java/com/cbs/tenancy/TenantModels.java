package com.cbs.tenancy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TenantModels {
    private TenantModels() { }

    public enum Status { REQUESTED, PROVISIONING, ACTIVE, SUSPENDED, PROVISIONING_FAILED }
    public enum BankRole { BANK_OPERATOR, BANK_AUDITOR }
    public record Actor(String issuer, String subject, boolean platformAdmin, UUID correlationId) { }
    public record LegalEntityInput(
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]+") String legalName,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String functionalCurrency,
            @NotBlank @Size(max = 64) String businessTimezone) { }
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,31}") String bankCode,
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]+") String displayName,
            @NotBlank @Size(max = 35) String defaultLanguage,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String databaseReference,
            @NotNull @Valid LegalEntityInput legalEntity) { }
    public record ChangeRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) { }
    public record UpdateRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]+") String displayName,
            @NotBlank @Size(max = 35) String defaultLanguage,
            @NotBlank @Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) { }
    public record AddLegalEntityRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid LegalEntityInput legalEntity,
            @NotBlank @Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) { }
    public record MembershipRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]+") String subject,
            @NotNull BankRole role, @NotNull Boolean active,
            @NotBlank @Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) { }
    public record LegalEntity(UUID id, String legalName, String country,
            String functionalCurrency, String businessTimezone) { }
    public record Binding(String databaseReference, String schemaVersion, String failureCode,
            UUID attemptId, Instant leaseUntil) { }
    public record Tenant(UUID id, String bankCode, String displayName, String defaultLanguage,
            Status status, long version, Instant createdAt, Instant updatedAt,
            List<LegalEntity> legalEntities, Binding databaseBinding) { }
    public record Registration(Tenant tenant, boolean replayed) { }
    public record Page<T>(List<T> items, int limit, int offset) { }
    public record Membership(String issuer, String subject, BankRole role, boolean active) { }
    public record AuditEvent(UUID id, String actorIssuer, String actorSubject, String action,
            long tenantVersion, String previousStatus, String newStatus, String reason,
            String targetSubject, String targetRole, Map<String, Object> changeDetails, UUID correlationId, Instant occurredAt) { }
    public record BankContext(UUID tenantId, String bankCode, String displayName, String defaultLanguage,
            Status status, long version, List<LegalEntity> legalEntities, String schemaVersion) { }
}
