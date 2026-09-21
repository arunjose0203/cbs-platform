package com.cbs.tenancy;

import static com.cbs.tenancy.TenantModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "CBS_INTEGRATION_TESTS", matches = "true")
@SpringBootTest(properties = {
        "cbs.security.enabled=true", "cbs.security.issuer=https://identity.cbs.test",
        "cbs.security.jwk-set-uri=https://identity.cbs.test/jwks", "cbs.security.audience=cbs-api"
})
@AutoConfigureMockMvc
@Import(TestIdentity.Configuration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantAdministrationIntegrationTest {
    private static final Actor ADMIN = new Actor(TestIdentity.ISSUER, "administrator", true, UUID.randomUUID());
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TenantService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TenantRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.cbs.access.AccessService access;
    @Autowired com.cbs.access.BranchService branchService;
    @BeforeAll void knownIdentities() {
        for(String subject:List.of("administrator","operator","operator-a","operator-b")) { access.bootstrap(TestIdentity.ISSUER,subject); }
    }

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) { TestDatabases.configure(registry); }

    @AfterAll void closeFixtures() throws SQLException {
        ((HikariDataSource) dataSource).close();
        TestDatabases.close();
    }

    @Test void registrationIsAuditedAndIdempotentWithPayloadConflictProtection() throws Exception {
        RegisterRequest request = request("registration-one");
        UUID key = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String body = mvc.perform(admin(post("/api/v1/admin/tenants"))
                        .header("Idempotency-Key", key).header("X-Correlation-ID", correlation)
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        Tenant tenant = json.readValue(body, Tenant.class);
        mvc.perform(admin(post("/api/v1/admin/tenants")).header("Idempotency-Key", key)
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(tenant.id().toString()));
        RegisterRequest changed = new RegisterRequest(request.bankCode(), "Changed bank", request.defaultLanguage(),
                request.databaseReference(), request.legalEntity());
        mvc.perform(admin(post("/api/v1/admin/tenants")).header("Idempotency-Key", key)
                        .contentType("application/json").content(json.writeValueAsString(changed)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        List<AuditEvent> events = service.auditEvents(ADMIN, tenant.id(), 100, 0).items();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().actorSubject()).isEqualTo("administrator");
        assertThat(events.getFirst().correlationId()).isEqualTo(correlation);
        assertThat(events.getFirst().changeDetails()).containsKey("registration");
    }

    @Test void duplicateCodesAndBindingsRollBackTheWholeRegistration() {
        RegisterRequest first = request("code-first");
        service.register(ADMIN, UUID.randomUUID(), first);
        RegisterRequest sameCode = new RegisterRequest(first.bankCode(), "Another name", "en", "code-second", first.legalEntity());
        assertCode(() -> service.register(ADMIN, UUID.randomUUID(), sameCode), "BANK_CODE_IN_USE");
        RegisterRequest sameBinding = new RegisterRequest("OTHER_CODE", "Another bank", "en", "code-first", first.legalEntity());
        assertCode(() -> service.register(ADMIN, UUID.randomUUID(), sameBinding), "DATABASE_REFERENCE_IN_USE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.tenant WHERE bank_code='OTHER_CODE'", Integer.class)).isZero();
    }

    @Test void simultaneousRetriesCreateExactlyOneTenantAndAuditRecord() throws Exception {
        RegisterRequest request = request("race-one");
        UUID key = UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Registration> task = () -> { start.await(); return service.register(ADMIN, key, request); };
            var results = executor.invokeAll(List.of(task, task));
            Registration first = results.getFirst().get();
            Registration second = results.getLast().get();
            assertThat(first.tenant().id()).isEqualTo(second.tenant().id());
            assertThat(first.replayed()).isNotEqualTo(second.replayed());
            assertThat(service.auditEvents(ADMIN, first.tenant().id(), 100, 0).items()).hasSize(1);
        }
    }

    @Test void concurrentEditsRejectStaleVersionWithoutExtraAudit() throws Exception {
        Tenant tenant = register("race-state");
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                start.await();
                try {
                    service.update(ADMIN, tenant.id(), new UpdateRequest(0L, "Updated", "hi-IN", "Correct display name"));
                    return "UPDATED";
                } catch (TenantException exception) { return exception.code(); }
            };
            var results = executor.invokeAll(List.of(task, task));
            assertThat(List.of(results.getFirst().get(), results.getLast().get())).containsExactlyInAnyOrder("UPDATED", "STALE_VERSION");
        }
        assertThat(service.get(ADMIN, tenant.id()).version()).isEqualTo(1);
        assertThat(service.auditEvents(ADMIN, tenant.id(), 100, 0).items()).hasSize(2);
    }

    @Test void authenticatedAdministrationAndRequestValidationAreEnforced() throws Exception {
        mvc.perform(post("/api/v1/admin/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + TestIdentity.token("operator", false)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(admin(post("/api/v1/admin/tenants")).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(admin(get("/api/v1/admin/tenants?limit=101")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        String invalid = json.writeValueAsString(request("invalid-first")).replace("Asia/Kolkata", "Invalid/Timezone");
        mvc.perform(admin(post("/api/v1/admin/tenants")).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LEGAL_ENTITY"));
        mvc.perform(admin(post("/api/v1/admin/tenants")).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(json.writeValueAsString(request("unapproved"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DATABASE_REFERENCE_NOT_APPROVED"));
    }

    @Test void legalEntitiesAndAuditChangesArePreserved() throws Exception {
        Tenant tenant = register("entity-one");
        LegalEntityInput entity = new LegalEntityInput("Example Europe", "DE", "EUR", "Europe/Berlin");
        Tenant updated = invokeTenant(post("/api/v1/admin/tenants/" + tenant.id() + "/legal-entities"),
                new AddLegalEntityRequest(tenant.version(), entity, "Add legal entity"));
        assertThat(updated.legalEntities()).hasSize(2);
        updated = invokeTenant(patch("/api/v1/admin/tenants/" + tenant.id()),
                new UpdateRequest(updated.version(), "बैंक", "hi-IN", "Localize display"));
        assertThat(updated.displayName()).isEqualTo("बैंक");
        assertThat(updated.defaultLanguage()).isEqualTo("hi-IN");
        mvc.perform(admin(get("/api/v1/admin/tenants/" + tenant.id() + "/audit")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[2].changeDetails.after.displayName").value("बैंक"));
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.tenant_audit SET reason='rewrite' WHERE tenant_id=?", tenant.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM platform.tenant_audit WHERE tenant_id=?", tenant.id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test void bankLifecycleIsIsolatedAndSuspensionAndRevocationTakeEffect() throws Exception {
        Tenant a = register("bank-a");
        Tenant b = register("bank-b");
        a = invokeTenant(put("/api/v1/admin/tenants/" + a.id() + "/memberships"),
                new MembershipRequest(a.version(), "operator-a", BankRole.BANK_OPERATOR, true, "Assign bank operator"));
        b = invokeTenant(put("/api/v1/admin/tenants/" + b.id() + "/memberships"),
                new MembershipRequest(b.version(), "operator-b", BankRole.BANK_OPERATOR, true, "Assign bank operator"));
        bankGet(a.id(), "operator-a", true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_NOT_ACTIVE"));
        a = provision(a);
        b = provision(b);
        assertThat(a.status()).isEqualTo(Status.ACTIVE);
        assertThat(b.status()).isEqualTo(Status.ACTIVE);
        assertThat(a.databaseBinding().schemaVersion()).isEqualTo("1");
        bankGet(a.id(), "operator-a", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(a.id().toString())).andExpect(jsonPath("$.databaseBinding").doesNotExist());
        mvc.perform(get("/api/v1/banks/" + b.id() + "/operational-context")
                        .header("Authorization", "Bearer " + TestIdentity.token("operator-a", false))
                        .header("X-Tenant-ID", b.id()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BANK_ACCESS_DENIED"));
        bankGet(UUID.randomUUID(), "operator-a", false).andExpect(status().isForbidden());
        mvc.perform(admin(get("/api/v1/banks/" + a.id() + "/operational-context"))).andExpect(status().isForbidden());
        assertThatThrownBy(TestDatabases::crossBankConnection).isInstanceOf(SQLException.class);

        Tenant alias = provision(register("bank-b-alias"));
        assertThat(alias.status()).isEqualTo(Status.PROVISIONING_FAILED);
        assertThat(alias.databaseBinding().failureCode()).isEqualTo("DATABASE_OWNERSHIP_MISMATCH");
        bankGet(b.id(), "operator-b", true).andExpect(status().isOk());

        a = invokeTenant(post("/api/v1/admin/tenants/" + a.id() + "/suspend"), new ChangeRequest(a.version(), "Operational hold"));
        bankGet(a.id(), "operator-a", true).andExpect(status().isConflict());
        bankGet(a.id(), "operator-a", false).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        bankGet(b.id(), "operator-b", true).andExpect(status().isOk());
        a = invokeTenant(post("/api/v1/admin/tenants/" + a.id() + "/resume"), new ChangeRequest(a.version(), "Hold resolved"));
        bankGet(a.id(), "operator-a", true).andExpect(status().isOk());
        a = invokeTenant(put("/api/v1/admin/tenants/" + a.id() + "/memberships"),
                new MembershipRequest(a.version(), "operator-a", BankRole.BANK_AUDITOR, true, "Change responsibilities"));
        bankGet(a.id(), "operator-a", true).andExpect(status().isForbidden());
        bankGet(a.id(), "operator-a", false).andExpect(status().isOk());
        invokeTenant(put("/api/v1/admin/tenants/" + a.id() + "/memberships"),
                new MembershipRequest(a.version(), "operator-a", BankRole.BANK_AUDITOR, false, "Revoke access"));
        bankGet(a.id(), "operator-a", false).andExpect(status().isForbidden());
    }

    @Test void failedMigrationsCannotActivateTenantAndCanBeRetried() throws Exception {
        try (var connection = TestDatabases.bank("failure"); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA bank");
            statement.execute("CREATE TABLE bank.installation(conflicting_column text)");
        }
        Tenant tenant = provision(register("migration-failure"));
        assertThat(tenant.status()).isEqualTo(Status.PROVISIONING_FAILED);
        assertThat(tenant.databaseBinding().failureCode()).isEqualTo("DATABASE_PROVISIONING_FAILED");
        assertThat(tenant.databaseBinding().schemaVersion()).isNull();
        try (var connection = TestDatabases.bank("failure"); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE bank.installation");
        }
        tenant = provision(tenant);
        assertThat(tenant.status()).isEqualTo(Status.ACTIVE);
        assertThat(tenant.databaseBinding().failureCode()).isNull();
        assertThat(service.auditEvents(ADMIN, tenant.id(), 100, 0).items().stream().map(AuditEvent::action))
                .contains("PROVISIONING_FAILED", "PROVISIONING_SUCCEEDED");
    }

    @Test void controlPlaneCannotBeProvisionedAsABank() {
        Tenant tenant = provision(register("control-target"));
        assertThat(tenant.status()).isEqualTo(Status.PROVISIONING_FAILED);
        assertThat(tenant.databaseBinding().failureCode()).isEqualTo("CONTROL_PLANE_TARGET_FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname='cbs_identity'", Integer.class)).isZero();
    }

    @Test void provisioningRecoveryRequiresExpiredLeaseAndCurrentVersion() {
        Tenant tenant = register("recovery-one");
        jdbc.update("UPDATE platform.tenant SET status='PROVISIONING',version=1 WHERE id=?", tenant.id());
        jdbc.update("UPDATE platform.database_binding SET attempt_id=?,lease_until=now()+interval '10 minutes' WHERE tenant_id=?",
                UUID.randomUUID(), tenant.id());
        assertCode(() -> service.provision(ADMIN, tenant.id(), new ChangeRequest(0L, "Recover")), "STALE_VERSION");
        assertCode(() -> service.provision(ADMIN, tenant.id(), new ChangeRequest(1L, "Recover")), "INVALID_TRANSITION");
        jdbc.update("UPDATE platform.database_binding SET lease_until=now()-interval '1 minute' WHERE tenant_id=?", tenant.id());
        Tenant recovered = service.provision(ADMIN, tenant.id(), new ChangeRequest(1L, "Recover abandoned operation"));
        assertThat(recovered.status()).isEqualTo(Status.ACTIVE);
        assertThat(service.auditEvents(ADMIN, tenant.id(), 100, 0).items().stream().map(AuditEvent::action)).contains("PROVISIONING_RECOVERED");
    }

    @Test void anOldProvisioningWorkerCannotOverwriteANewerAttempt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BankDatabaseProvisioner delayed = new BankDatabaseProvisioner(new BankDatabaseProvisioner.Settings(Map.of())) {
            @Override public void requireApproved(String reference) { }
            @Override public String provision(UUID id, String reference) {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Timed out waiting for newer attempt"); }
                    } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                }
                return "1";
            }
        };
        TenantService controlled = new TenantService(repository, new TransactionTemplate(transactionManager), delayed);
        Tenant tenant = controlled.register(ADMIN, UUID.randomUUID(), request("fenced-one")).tenant();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> {
                try { controlled.provision(ADMIN, tenant.id(), new ChangeRequest(0L, "Original worker")); return "UNEXPECTED_SUCCESS"; }
                catch (TenantException exception) { return exception.code(); }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE platform.database_binding SET lease_until=now()-interval '1 minute' WHERE tenant_id=?", tenant.id());
                Tenant latest = controlled.provision(ADMIN, tenant.id(), new ChangeRequest(1L, "Replacement worker"));
                assertThat(latest.status()).isEqualTo(Status.ACTIVE);
                assertThat(latest.version()).isEqualTo(3);
            } finally { release.countDown(); }
            assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo("PROVISIONING_SUPERSEDED");
            assertThat(service.get(ADMIN, tenant.id()).version()).isEqualTo(3);
            assertThat(service.auditEvents(ADMIN, tenant.id(), 100, 0).items()).hasSize(4);
        }
    }

    @Test void auditFailureRollsBackAdministrativeChanges() {
        Tenant tenant = register("audit-only");
        Actor invalidAuditActor = new Actor(TestIdentity.ISSUER, "x".repeat(201), true, UUID.randomUUID());
        assertThatThrownBy(() -> service.update(invalidAuditActor, tenant.id(),
                new UpdateRequest(0L, "Must not persist", "en", "Simulate audit-store failure")))
                .isInstanceOf(DataAccessException.class);
        Tenant unchanged = service.get(ADMIN, tenant.id());
        assertThat(unchanged.version()).isZero();
        assertThat(unchanged.displayName()).isEqualTo("Example bank");
        assertThat(service.auditEvents(ADMIN, tenant.id(), 100, 0).items()).hasSize(1);
    }

    @Test void administratorMfaAndStaffRegistrationAreRequired() throws Exception {
        String noMfa=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,"administrator",true,
                Instant.now().plusSeconds(300),Instant.now(),Instant.now(),UUID.randomUUID().toString(),false);
        mvc.perform(get("/api/v1/admin/staff").header("Authorization","Bearer "+noMfa))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(get("/api/v1/access/me").header("Authorization","Bearer "+TestIdentity.token("unknown-staff",false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STAFF_NOT_REGISTERED"));
        mvc.perform(get("/api/v1/access/me")).andExpect(status().isUnauthorized());
    }

    @Test void staffLifecycleBlocksExistingTokensAndBootstrapCannotReenableThem() throws Exception {
        var input=new com.cbs.access.AccessModels.StaffInput("lifecycle-user","Lifecycle user","staff@example.test","Onboard employee");
        String response=mvc.perform(admin(post("/api/v1/admin/staff")).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var staff=json.readValue(response,com.cbs.access.AccessModels.Staff.class);
        String token=TestIdentity.token(staff.subject(),false);
        accessMe(token).andExpect(status().isOk()).andExpect(jsonPath("$.staff.id").value(staff.id().toString()));
        var disabled=new com.cbs.access.AccessModels.StaffUpdate(staff.version(),staff.displayName(),staff.email(),false,"Employee leaves");
        mvc.perform(admin(patch("/api/v1/admin/staff/"+staff.id())).contentType("application/json").content(json.writeValueAsString(disabled)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        accessMe(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STAFF_DISABLED"));
        access.bootstrap(TestIdentity.ISSUER,staff.subject());
        assertThat(access.staff(staff.id(),false).active()).isFalse();
        access.update(ADMIN,staff.id(),new com.cbs.access.AccessModels.StaffUpdate(1L,staff.displayName(),staff.email(),true,"Rehire approved"));
        accessMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        Instant fresh=Instant.now().plusSeconds(2);
        String newToken=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,staff.subject(),false,
                fresh.plusSeconds(300),fresh,fresh,UUID.randomUUID().toString(),false);
        accessMe(newToken).andExpect(status().isOk());
        assertCode(()->access.update(ADMIN,staff.id(),disabled),"STALE_VERSION");
    }

    @Test void endingOwnSessionCannotRevokeAnotherUsersSession() throws Exception {
        access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("session-user","Session user",null,"Onboard"));
        access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("other-session-user","Other user",null,"Onboard"));
        String token=TestIdentity.token("session-user",false),other=TestIdentity.token("other-session-user",false);
        var own=json.readValue(accessMe(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),com.cbs.access.AccessModels.Me.class);
        var someoneElse=json.readValue(accessMe(other).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),com.cbs.access.AccessModels.Me.class);
        mvc.perform(delete("/api/v1/access/sessions/"+someoneElse.sessionId()).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/access/sessions/"+own.sessionId()).header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
        accessMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        accessMe(other).andExpect(status().isOk());
    }

    @Test void revokeAllRequiresFreshAuthenticationEvenWithANewlyIssuedToken() throws Exception {
        var staff=access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("revoke-all-user","Revoked user",null,"Onboard"));
        Instant oldAuth=Instant.now().minusSeconds(5);
        String old=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,staff.subject(),false,
                Instant.now().plusSeconds(300),Instant.now(),oldAuth,"session-to-revoke",false);
        accessMe(old).andExpect(status().isOk());
        mvc.perform(admin(post("/api/v1/admin/staff/"+staff.id()+"/revoke-sessions")).contentType("application/json")
                        .content(json.writeValueAsString(new ChangeRequest(0L,"Review access"))))
                .andExpect(status().isOk());
        accessMe(old).andExpect(status().isUnauthorized());
        String refreshed=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,staff.subject(),false,
                Instant.now().plusSeconds(300),Instant.now().plusSeconds(2),oldAuth,"different-sid",false);
        accessMe(refreshed).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test void idleAndAbsoluteSessionLimitsCannotBeExtendedByRetries() throws Exception {
        access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("idle-user","Idle user",null,"Onboard"));
        String token=TestIdentity.token("idle-user",false);
        var me=json.readValue(accessMe(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),com.cbs.access.AccessModels.Me.class);
        jdbc.update("UPDATE platform.access_session SET last_seen_at=now()-interval '16 minutes' WHERE id=?",me.sessionId());
        accessMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        accessMe(token).andExpect(status().isUnauthorized());
        String tooOld=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,"idle-user",false,
                Instant.now().plusSeconds(300),Instant.now(),Instant.now().minusSeconds(9*3600),"old-session",false);
        accessMe(tooOld).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        String missingSid=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,"idle-user",false,
                Instant.now().plusSeconds(300),Instant.now(),Instant.now(),null,false);
        accessMe(missingSid).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SESSION_CLAIMS"));
    }

    @Test void branchPermissionsAreScopedToBankBranchAndCurrentMembership() throws Exception {
        var staff=access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("branch-user","Branch teller",null,"Onboard"));
        Tenant a=register("branch-bank-a"),b=register("branch-bank-b");
        jdbc.update("UPDATE platform.tenant SET status='ACTIVE' WHERE id IN (?,?)",a.id(),b.id());
        var branchInput=new com.cbs.access.AccessModels.BranchInput(a.legalEntities().getFirst().id(),"MAIN","Main branch","Open branch");
        String body=mvc.perform(admin(post("/api/v1/admin/tenants/"+a.id()+"/branches")).contentType("application/json").content(json.writeValueAsString(branchInput)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var first=json.readValue(body,com.cbs.access.AccessModels.Branch.class);
        var second=branchService.create(ADMIN,b.id(),new com.cbs.access.AccessModels.BranchInput(b.legalEntities().getFirst().id(),"MAIN","Other bank branch","Open"));
        assertCode(()->branchService.create(ADMIN,a.id(),new com.cbs.access.AccessModels.BranchInput(b.legalEntities().getFirst().id(),"WRONG","Wrong entity","Attempt")),"LEGAL_ENTITY_NOT_IN_BANK");
        for(Tenant t:List.of(a,b)) {
            Tenant current=service.get(ADMIN,t.id());
            service.setMembership(ADMIN,t.id(),new MembershipRequest(current.version(),staff.subject(),BankRole.BANK_OPERATOR,true,"Bank operator"));
        }
        var grant=new com.cbs.access.AccessModels.BranchGrant(first.version(),staff.id(),com.cbs.access.AccessModels.BranchRole.TELLER,true,"Assign teller");
        mvc.perform(admin(put("/api/v1/admin/tenants/"+a.id()+"/branches/"+first.id()+"/memberships")).contentType("application/json").content(json.writeValueAsString(grant)))
                .andExpect(status().isOk());
        String token=TestIdentity.token(staff.subject(),false);
        branchAccess(token,a.id(),first.id(),"CASH_TRANSACT").andExpect(status().isOk());
        branchAccess(token,a.id(),first.id(),"APPROVE_BRANCH").andExpect(status().isForbidden());
        branchAccess(token,b.id(),second.id(),"READ_BRANCH").andExpect(status().isForbidden());
        branchAccess(token,a.id(),second.id(),"READ_BRANCH").andExpect(status().isForbidden());
        Tenant current=service.get(ADMIN,a.id());
        service.setMembership(ADMIN,a.id(),new MembershipRequest(current.version(),staff.subject(),BankRole.BANK_AUDITOR,true,"Restrict to audit"));
        branchAccess(token,a.id(),first.id(),"CASH_TRANSACT").andExpect(status().isForbidden());
        branchAccess(token,a.id(),first.id(),"READ_BRANCH").andExpect(status().isOk());
        var changed=branchService.update(ADMIN,a.id(),first.id(),new com.cbs.access.AccessModels.BranchUpdate(1L,"Main branch",false,"Close branch"));
        branchAccess(token,a.id(),first.id(),"READ_BRANCH").andExpect(status().isConflict());
        branchService.grant(ADMIN,a.id(),first.id(),new com.cbs.access.AccessModels.BranchGrant(changed.version(),staff.id(),com.cbs.access.AccessModels.BranchRole.AUDITOR,false,"Revoke"));
        branchAccess(token,a.id(),first.id(),"READ_BRANCH").andExpect(status().isForbidden());
    }

    @Test void identityAuditIsAppendOnlyAndSelfDeactivationIsRejected() {
        var staff=access.requireAssignable(TestIdentity.ISSUER,"administrator");
        assertCode(()->access.update(ADMIN,staff.id(),new com.cbs.access.AccessModels.StaffUpdate(staff.version(),staff.displayName(),staff.email(),false,"Self disable")),"SELF_DEACTIVATION_FORBIDDEN");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.identity_audit SET reason='rewrite' WHERE staff_id=?",staff.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.identity_audit WHERE staff_id=?",staff.id())).isInstanceOf(DataAccessException.class);
    }

    @Test void portalIsPublicButContainsNoIdentitySecrets() throws Exception {
        String html=mvc.perform(get("/admin/index.html")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("CBS · Administration");
        mvc.perform(get("/admin/app.js")).andExpect(status().isOk());
        mvc.perform(get("/portal/config")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.jwkSetUri").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions accessMe(String token) throws Exception {
        return mvc.perform(get("/api/v1/access/me").header("Authorization","Bearer "+token));
    }
    private org.springframework.test.web.servlet.ResultActions branchAccess(String token,UUID bank,UUID branch,String permission) throws Exception {
        return mvc.perform(get("/api/v1/banks/"+bank+"/branches/"+branch+"/access?permission="+permission).header("Authorization","Bearer "+token));
    }

    private Tenant register(String reference) { return service.register(ADMIN, UUID.randomUUID(), request(reference)).tenant(); }
    private Tenant provision(Tenant tenant) { return service.provision(ADMIN, tenant.id(), new ChangeRequest(tenant.version(), "Provision bank database")); }
    private static RegisterRequest request(String reference) {
        String code = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        return new RegisterRequest(code, "Example bank", "en-IN", reference,
                new LegalEntityInput("Example Bank Limited", "IN", "INR", "Asia/Kolkata"));
    }
    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestIdentity.token("administrator", true));
    }
    private Tenant invokeTenant(MockHttpServletRequestBuilder request, Object body) throws Exception {
        String response = mvc.perform(admin(request).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readValue(response, Tenant.class);
    }
    private org.springframework.test.web.servlet.ResultActions bankGet(UUID id, String subject, boolean operational) throws Exception {
        return mvc.perform(get("/api/v1/banks/" + id + (operational ? "/operational-context" : ""))
                .header("Authorization", "Bearer " + TestIdentity.token(subject, false)));
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(TenantException.class, exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
