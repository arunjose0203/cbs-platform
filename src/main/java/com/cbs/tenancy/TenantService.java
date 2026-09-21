package com.cbs.tenancy;

import static com.cbs.tenancy.TenantModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.ZoneId;
import org.springframework.transaction.support.TransactionTemplate;

public class TenantService {
    private final TenantRepository repository;
    private final TransactionTemplate transactions;
    private final BankDatabaseProvisioner provisioner;

    public TenantService(TenantRepository repository, TransactionTemplate transactions, BankDatabaseProvisioner provisioner) {
        this.repository = repository;
        this.transactions = transactions;
        this.provisioner = provisioner;
    }

    public Registration register(Actor actor, UUID key, RegisterRequest request) {
        requireAdmin(actor);
        validateLanguage(request.defaultLanguage());
        validateEntity(request.legalEntity());
        String hash = fingerprint(request);
        return transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            if (!repository.claimRegistration(actor, key, hash, id)) {
                return new Registration(repository.get(repository.replayRegistration(actor, key, hash), false), true);
            }
            provisioner.requireApproved(request.databaseReference());
            repository.insert(id, request);
            repository.audit(actor, id, "TENANT_REGISTERED", 0, null, Status.REQUESTED,
                    "Initial registration", null, null, Map.of("registration", request));
            return new Registration(repository.get(id, false), false);
        });
    }

    public Tenant get(Actor actor, UUID tenantId) {
        requireAdmin(actor);
        return repository.get(tenantId, false);
    }

    public Page<Tenant> list(Actor actor, int limit, int offset) {
        requireAdmin(actor);
        validatePage(limit, offset);
        return new Page<>(repository.list(limit, offset), limit, offset);
    }

    public Tenant update(Actor actor, UUID tenantId, UpdateRequest request) {
        requireAdmin(actor);
        validateLanguage(request.defaultLanguage());
        return transactions.execute(status -> {
            Tenant current = editable(tenantId, request.expectedVersion());
            repository.change(current, current.status(), request.displayName(), request.defaultLanguage());
            repository.audit(actor, tenantId, "TENANT_UPDATED", current.version() + 1, current.status(), current.status(),
                    request.reason(), null, null, Map.of("before", Map.of("displayName", current.displayName(),
                    "defaultLanguage", current.defaultLanguage()), "after", Map.of("displayName", request.displayName(),
                    "defaultLanguage", request.defaultLanguage())));
            return repository.get(tenantId, false);
        });
    }

    public Tenant addLegalEntity(Actor actor, UUID tenantId, AddLegalEntityRequest request) {
        requireAdmin(actor);
        validateEntity(request.legalEntity());
        return transactions.execute(status -> {
            Tenant current = editable(tenantId, request.expectedVersion());
            repository.addLegalEntity(tenantId, request.legalEntity());
            repository.change(current, current.status(), current.displayName(), current.defaultLanguage());
            repository.audit(actor, tenantId, "LEGAL_ENTITY_ADDED", current.version() + 1, current.status(), current.status(),
                    request.reason(), null, null, Map.of("legalEntity", request.legalEntity()));
            return repository.get(tenantId, false);
        });
    }

    public Tenant setMembership(Actor actor, UUID tenantId, MembershipRequest request) {
        requireAdmin(actor);
        return transactions.execute(status -> {
            Tenant current = editable(tenantId, request.expectedVersion());
            repository.setMembership(tenantId, actor, request);
            repository.change(current, current.status(), current.displayName(), current.defaultLanguage());
            audit(actor, current, current.status(), request.active() ? "MEMBERSHIP_GRANTED" : "MEMBERSHIP_REVOKED",
                    request.reason(), request.subject(), request.role().name());
            return repository.get(tenantId, false);
        });
    }

    public Page<Membership> memberships(Actor actor, UUID tenantId, int limit, int offset) {
        requireAdmin(actor);
        validatePage(limit, offset);
        repository.get(tenantId, false);
        return new Page<>(repository.memberships(tenantId, limit, offset), limit, offset);
    }

    public Page<AuditEvent> auditEvents(Actor actor, UUID tenantId, int limit, int offset) {
        requireAdmin(actor);
        validatePage(limit, offset);
        repository.get(tenantId, false);
        return new Page<>(repository.auditEvents(tenantId, limit, offset), limit, offset);
    }

    public Tenant suspend(Actor actor, UUID tenantId, ChangeRequest request) {
        return transition(actor, tenantId, request, Status.ACTIVE, Status.SUSPENDED, "TENANT_SUSPENDED");
    }

    public Tenant resume(Actor actor, UUID tenantId, ChangeRequest request) {
        return transition(actor, tenantId, request, Status.SUSPENDED, Status.ACTIVE, "TENANT_RESUMED");
    }

    private Tenant transition(Actor actor, UUID tenantId, ChangeRequest request, Status from, Status to, String action) {
        requireAdmin(actor);
        return transactions.execute(status -> {
            Tenant current = editable(tenantId, request.expectedVersion());
            if (current.status() != from) { throw conflict("INVALID_TRANSITION", "The tenant is not in the required state"); }
            repository.change(current, to, current.displayName(), current.defaultLanguage());
            audit(actor, current, to, action, request.reason(), null, null);
            return repository.get(tenantId, false);
        });
    }

    public Tenant provision(Actor actor, UUID tenantId, ChangeRequest request) {
        requireAdmin(actor);
        UUID attemptId = UUID.randomUUID();
        Tenant claimed = transactions.execute(status -> {
            Tenant current = repository.get(tenantId, true);
            checkVersion(current, request.expectedVersion());
            boolean expiredAttempt = current.status() == Status.PROVISIONING && repository.leaseExpired(tenantId);
            if (current.status() != Status.REQUESTED && current.status() != Status.PROVISIONING_FAILED && !expiredAttempt) {
                throw conflict("INVALID_TRANSITION", "Provisioning requires a requested, failed, or expired provisioning state");
            }
            provisioner.requireApproved(current.databaseBinding().databaseReference());
            repository.change(current, Status.PROVISIONING, current.displayName(), current.defaultLanguage());
            repository.startAttempt(tenantId, attemptId);
            audit(actor, current, Status.PROVISIONING, expiredAttempt ? "PROVISIONING_RECOVERED" : "PROVISIONING_STARTED",
                    request.reason(), null, null);
            return repository.get(tenantId, false);
        });
        // Cross-database work is intentionally outside the control-plane transaction.
        String schemaVersion = null;
        String failureCode = null;
        try {
            schemaVersion = provisioner.provision(tenantId, claimed.databaseBinding().databaseReference());
        } catch (TenantException exception) {
            failureCode = exception.code();
        }
        final String completedSchema = schemaVersion;
        final String completedFailure = failureCode;
        return transactions.execute(status -> {
            Tenant current = repository.get(tenantId, true);
            if (current.status() != Status.PROVISIONING || !attemptId.equals(current.databaseBinding().attemptId())) {
                throw conflict("PROVISIONING_SUPERSEDED", "A newer provisioning attempt owns this tenant");
            }
            Status next = completedFailure == null ? Status.ACTIVE : Status.PROVISIONING_FAILED;
            repository.change(current, next, current.displayName(), current.defaultLanguage());
            repository.finishAttempt(tenantId, completedSchema, completedFailure);
            audit(actor, current, next, completedFailure == null ? "PROVISIONING_SUCCEEDED" : "PROVISIONING_FAILED",
                    completedFailure == null ? "Database identity and migrations verified" : completedFailure, null, null);
            return repository.get(tenantId, false);
        });
    }

    public BankContext bankContext(Actor actor, UUID tenantId, boolean operational) {
        // Check membership before loading the tenant, avoiding existence disclosure.
        BankRole role = repository.activeRole(tenantId, actor);
        Tenant tenant = repository.get(tenantId, false);
        if (operational) {
            if (role != BankRole.BANK_OPERATOR) { throw new TenantException(403, "BANK_OPERATION_DENIED", "Bank operator membership is required"); }
            if (tenant.status() != Status.ACTIVE) { throw conflict("TENANT_NOT_ACTIVE", "The bank is not active for operations"); }
            provisioner.verifyRoute(tenantId, tenant.databaseBinding().databaseReference());
        }
        return new BankContext(tenant.id(), tenant.bankCode(), tenant.displayName(), tenant.defaultLanguage(),
                tenant.status(), tenant.version(), tenant.legalEntities(), tenant.databaseBinding().schemaVersion());
    }

    private Tenant editable(UUID id, long version) {
        Tenant tenant = repository.get(id, true);
        checkVersion(tenant, version);
        if (tenant.status() == Status.PROVISIONING) { throw conflict("PROVISIONING_IN_PROGRESS", "Wait for provisioning to finish before editing"); }
        return tenant;
    }

    private void audit(Actor actor, Tenant before, Status after, String action, String reason, String target, String role) {
        repository.audit(actor, before.id(), action, before.version() + 1, before.status(), after, reason, target, role);
    }

    private static void checkVersion(Tenant tenant, long version) {
        if (tenant.version() != version) { throw conflict("STALE_VERSION", "Read the current tenant version and retry"); }
    }

    private static void requireAdmin(Actor actor) {
        if (!actor.platformAdmin()) { throw new TenantException(403, "ACCESS_DENIED", "Platform administrator access is required"); }
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1000000) {
            throw new TenantException(400, "INVALID_PAGINATION", "Limit must be 1–100 and offset 0–1000000");
        }
    }

    private static void validateLanguage(String language) {
        try {
            Locale locale = new Locale.Builder().setLanguageTag(language).build();
            if (locale.getLanguage().isBlank() || "und".equals(locale.toLanguageTag())) { throw new IllegalArgumentException(); }
        } catch (RuntimeException exception) {
            throw new TenantException(400, "INVALID_LANGUAGE", "A valid language tag is required");
        }
    }

    private static void validateEntity(LegalEntityInput entity) {
        try {
            if (!Arrays.asList(Locale.getISOCountries()).contains(entity.country())) { throw new IllegalArgumentException(); }
            Currency.getInstance(entity.functionalCurrency());
            if (!ZoneId.getAvailableZoneIds().contains(entity.businessTimezone())) { throw new IllegalArgumentException(); }
        } catch (RuntimeException exception) {
            throw new TenantException(400, "INVALID_LEGAL_ENTITY", "Country, currency or business timezone is invalid");
        }
    }

    private static String fingerprint(RegisterRequest request) {
        String canonical = String.join("\u001f", request.bankCode(), request.displayName(), request.defaultLanguage(),
                request.databaseReference(), request.legalEntity().legalName(), request.legalEntity().country(),
                request.legalEntity().functionalCurrency(), request.legalEntity().businessTimezone());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static TenantException conflict(String code, String detail) { return new TenantException(409, code, detail); }
}
