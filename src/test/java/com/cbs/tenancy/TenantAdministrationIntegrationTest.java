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
        "cbs.ledger.delivery-enabled=false", "cbs.security.enabled=true", "cbs.security.issuer=https://identity.cbs.test",
        "cbs.security.jwk-set-uri=https://identity.cbs.test/jwks", "cbs.security.audience=cbs-api",
        "cbs.servicing.chunk-size=2", "cbs.notifications.sender=test", "cbs.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestIdentity.Configuration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantAdministrationIntegrationTest {
    private static final Actor ADMIN = new Actor(TestIdentity.ISSUER, "administrator", true, UUID.randomUUID());
    private static final Actor CHECKER = new Actor(TestIdentity.ISSUER, "checker", true, UUID.randomUUID());
    @Autowired com.cbs.payments.PaymentService payments;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.cbs.payments.MpesaGateway mpesa;
    @Autowired com.cbs.compliance.ComplianceService compliance;
    @Autowired com.cbs.compliance.ScreeningService screening;
    @Autowired com.cbs.compliance.MonitoringService monitoring;
    @Autowired com.cbs.approvals.ApprovalService approvals;
    @Autowired com.cbs.notifications.NotificationService notifications;
    @Autowired com.cbs.mandates.MandateService mandates;
    @Autowired com.cbs.accounting.PeriodCloseService periodCloses;
    @Autowired com.cbs.reporting.FinancialStatementService financialStatements;
    @Autowired com.cbs.reporting.ReportDefinitionService reportDefinitions;
    @Autowired com.cbs.reporting.ManagementDashboardService managementDashboard;
    @Autowired com.cbs.reference.ReferenceDataService reference;
    @Autowired com.cbs.accounting.ChartService chart;
    @Autowired com.cbs.products.ProductService products;
    @Autowired com.cbs.customers.CustomerService customers;
    @Autowired com.cbs.accounts.AccountService accounts;
    @Autowired com.cbs.accounts.FundingService funding;
    @Autowired com.cbs.accounts.OperationService operations;
    @Autowired com.cbs.accounts.StatementService statements;
    @Autowired com.cbs.accounting.LedgerService ledger;
    @Autowired com.cbs.accounting.JournalService journals;
    @Autowired com.cbs.accounting.DayCloseService days;
    @Autowired com.cbs.accounting.BankJournalStore bankJournals;
    @Autowired com.cbs.accounting.AccountingReportService reports;
    @Autowired BankDatabaseProvisioner bankDatabases;
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
        for(String subject:List.of("administrator","checker","operator","operator-a","operator-b")) { access.bootstrap(TestIdentity.ISSUER,subject); }
    }

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) { TestDatabases.configure(registry); }

    @AfterAll void closeFixtures() throws SQLException {
        ((HikariDataSource) dataSource).close();
        TestDatabases.close();
    }

    private static final String SANCTIONS_CSV = "entry_ref,entity_type,name,aliases,birth_date,country,program\n"
            + "UN-1,INDIVIDUAL,Viktor Ivanovich Petrov,\"V. Petrov; Victor Petrov\",1970-03-01,RU,Fictional asset freeze\n"
            + "UN-2,ENTITY,\"Blue Ocean Trading Co., Ltd\",,,PA,Fictional\n";

    private Map<String,Object> uploadList(UUID bank,String name,String csv,int expected) throws Exception {
        var body=json.writeValueAsString(Map.of("name",name,"category","SANCTIONS","source","Fictional consolidated list","threshold","0.88","csv",csv));
        var out=mvc.perform(admin(post("/api/v1/admin/tenants/"+bank+"/compliance/screening/lists")).contentType("application/json").content(body))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readValue(out,Map.class);
    }
    private long customerVersion(UUID customer){return jdbc.queryForObject("SELECT version FROM platform.customer_registry WHERE id=?",Long.class,customer);}
    private List<Map<String,Object>> hitsFor(UUID customer){return jdbc.queryForList("SELECT * FROM platform.screening_hit WHERE customer_id=? ORDER BY created_at",customer);}
    private void activate(UUID bank,Map<String,Object> list) throws Exception {
        var r=approvals.screeningList(ADMIN,UUID.randomUUID(),bank,new com.cbs.compliance.AutomationModels.Activation(service.get(ADMIN,bank).version(),UUID.fromString((String)list.get("id")),(String)list.get("sha256"),"Adopt fictional list version"));
        assertCode(()->approvals.decide(ADMIN,r.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(r.id());
    }

    @Test void screeningListsRestrictMatchesUntilDecidedAndMonitoringRaisesAlertsWithoutTippingOff() throws Exception {
        var f=fundingFixture("compliance-screening");var bank=f.f().bank();UUID b=bank.id();String cur=f.f().product().currency();
        // Drafts are validated line by line and are inert until approved.
        var bad=uploadList(b,"UN_SANCTIONS","entry_ref,entity_type,name,aliases,birth_date,country,program\nX-1,PERSON,Someone,,,,\n",422);
        assertThat(bad.toString()).contains("SCREENING_LIST_INVALID").contains("Line 2");
        var draft=uploadList(b,"UN_SANCTIONS",SANCTIONS_CSV,201);
        assertThat(draft.get("status")).isEqualTo("DRAFT");assertThat(draft.get("entry_count")).isEqualTo(2);
        assertThat(uploadList(b,"UN_SANCTIONS",SANCTIONS_CSV,201).get("id")).isEqualTo(draft.get("id"));
        UUID early=onboard(bank,person("Victor Petrov","SCRN001",java.time.LocalDate.of(1970,5,5)));
        assertThat(hitsFor(early)).isEmpty();
        // A tampered checksum cannot be activated.
        assertCode(()->approvals.screeningList(ADMIN,UUID.randomUUID(),b,new com.cbs.compliance.AutomationModels.Activation(service.get(ADMIN,b).version(),UUID.fromString((String)draft.get("id")),"0".repeat(64),"Wrong file")),"SCREENING_LIST_CHANGED");
        activate(b,draft);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.screening_list WHERE id=?::uuid",String.class,draft.get("id"))).isEqualTo("ACTIVE");
        // Activation queues a re-screen of every existing customer, processed in resumable chunks.
        assertThat(screening.processRuns(10)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.screening_run WHERE list_id=?::uuid",String.class,draft.get("id"))).isEqualTo("DONE");
        assertThat(hitsFor(early)).singleElement().satisfies(h->{assertThat(h.get("trigger")).isEqualTo("LIST");assertThat(h.get("entry_ref")).isEqualTo("UN-1");assertThat(h.get("status")).isEqualTo("OPEN");});
        assertCode(()->com.cbs.compliance.ComplianceService.requireClear(jdbc,b,early,customerVersion(early)),"CUSTOMER_COMPLIANCE_RESTRICTED");
        assertThat(com.cbs.compliance.ComplianceService.restricted(jdbc,b,Map.of(early,customerVersion(early),f.f().customer(),customerVersion(f.f().customer())))).containsExactly(early);
        // New customers are screened when their approved profile is applied; a different birth year rules a namesake out.
        UUID second=onboard(bank,person("PETROV, Viktor","SCRN002",java.time.LocalDate.of(1970,3,1)));
        UUID namesake=onboard(bank,person("Viktor Petrov","SCRN003",java.time.LocalDate.of(1995,1,1)));
        assertThat(hitsFor(second)).singleElement().satisfies(h->{assertThat(h.get("trigger")).isEqualTo("CUSTOMER");assertThat(((java.math.BigDecimal)h.get("score")).doubleValue()).isEqualTo(1.0);});
        assertThat(hitsFor(namesake)).isEmpty();
        // An organization is restricted while one of its directors has an open match.
        UUID harbour=onboard(bank,person("Harbour Logistics","SCRN004",java.time.LocalDate.of(2001,1,1)));
        UUID anyApproval=jdbc.queryForObject("SELECT id FROM platform.approval_request WHERE bank_id=? LIMIT 1",UUID.class,b);
        jdbc.update("INSERT INTO platform.customer_party(tenant_id,organization_id,person_id,role,approval_id) VALUES (?,?,?,'DIRECTOR',?)",b,harbour,second,anyApproval);
        assertCode(()->com.cbs.compliance.ComplianceService.requireClear(jdbc,b,harbour,customerVersion(harbour)),"CUSTOMER_COMPLIANCE_RESTRICTED");
        // The queue, the evidence view and escalation into a restricting case.
        mvc.perform(admin(get("/api/v1/admin/tenants/"+b+"/compliance/screening/hits?status=OPEN"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        UUID secondHit=(UUID)hitsFor(second).getFirst().get("id");
        mvc.perform(admin(get("/api/v1/admin/tenants/"+b+"/compliance/screening/hits/"+secondHit))).andExpect(status().isOk())
                .andExpect(jsonPath("$.hit.entry_names[0]").value("Viktor Ivanovich Petrov")).andExpect(jsonPath("$.hit.entry_program").value("Fictional asset freeze"));
        var escalation=json.writeValueAsString(Map.of("expectedVersion",service.get(ADMIN,b).version(),"itemVersion",0,"evidenceReference","SCREEN/ESC-1","reason","Birth date and name match the listed person"));
        var escalated=json.readValue(mvc.perform(admin(post("/api/v1/admin/tenants/"+b+"/compliance/screening/hits/"+secondHit+"/escalate")).contentType("application/json").content(escalation))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),Map.class);
        UUID screeningCase=UUID.fromString((String)escalated.get("caseId"));
        assertThat(jdbc.queryForObject("SELECT category FROM platform.compliance_case WHERE id=?",String.class,screeningCase)).isEqualTo("SCREENING_ALERT");
        mvc.perform(admin(post("/api/v1/admin/tenants/"+b+"/compliance/screening/hits/"+secondHit+"/escalate")).contentType("application/json").content(escalation)).andExpect(status().isConflict());
        assertCode(()->com.cbs.compliance.ComplianceService.requireClear(jdbc,b,second,customerVersion(second)),"CUSTOMER_COMPLIANCE_RESTRICTED");
        // Dismissing a false positive needs independent approval and then lifts the restriction.
        UUID earlyHit=(UUID)hitsFor(early).getFirst().get("id");
        var dismiss=json.writeValueAsString(Map.of("expectedVersion",service.get(ADMIN,b).version(),"type","SCREENING_HIT","itemId",earlyHit,"itemVersion",0,"evidenceReference","SCREEN/DOB-CHECK","reason","Passport shows a different person"));
        var request=json.readValue(mvc.perform(admin(post("/api/v1/admin/tenants/"+b+"/compliance/dismissals")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(dismiss))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(),com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(hitsFor(early).getFirst().get("status")).isEqualTo("OPEN");
        approveHttp(request.id());
        assertThat(hitsFor(early).getFirst().get("status")).isEqualTo("DISMISSED");
        com.cbs.compliance.ComplianceService.requireClear(jdbc,b,early,customerVersion(early));
        // A new list version re-screens everyone; the dismissal carries over while name and entry are unchanged.
        var v2=uploadList(b,"UN_SANCTIONS",SANCTIONS_CSV+"UN-3,INDIVIDUAL,Amina Kiprotich,,,KE,Fictional\n",201);
        activate(b,v2);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.screening_list WHERE id=?::uuid",String.class,draft.get("id"))).isEqualTo("SUPERSEDED");
        screening.processRuns(10);
        assertThat(hitsFor(early)).hasSize(1);
        assertThat(hitsFor(namesake)).isEmpty();
        // Lists and their entries are immutable evidence.
        assertThatThrownBy(()->jdbc.update("UPDATE platform.screening_entry SET names='[\"x\"]' WHERE list_id=?::uuid",draft.get("id"))).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.screening_list WHERE id=?::uuid",draft.get("id"))).hasMessageContaining("immutable");

        // ---- Transaction monitoring on real postings ----
        assertCode(()->approvals.monitoringPolicy(ADMIN,UUID.randomUUID(),b,new com.cbs.compliance.AutomationModels.Policy(service.get(ADMIN,b).version(),cur,null,"1000",null,null,null,null,null,null,"Half a rule")),"MONITORING_POLICY_INVALID");
        var policy=approvals.monitoringPolicy(ADMIN,UUID.randomUUID(),b,new com.cbs.compliance.AutomationModels.Policy(service.get(ADMIN,b).version(),cur,"1000","1000",2,3,3,new java.math.BigDecimal("5"),30,3,"Fictional monitoring thresholds"));
        approveHttp(policy.id());
        java.time.LocalDate today=reference.get(ADMIN,b).businessDate();
        // Simulated earlier history for the unusual-amount baseline (average 100 over three earlier days).
        for(int d=3;d<=5;d++)jdbc.update("INSERT INTO platform.monitoring_event(source_id,account_id,direction,tenant_id,channel,amount,currency,business_date,recorded_at) VALUES (?,?,'IN',?,'FUNDING',100,?,?,clock_timestamp()-make_interval(days=>?))",UUID.randomUUID(),f.account(),b,cur,today.minusDays(d),d);
        UUID bigFunding=fund(f,"5000");
        for(int i=0;i<3;i++)postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"10",null,null));
        UUID other=accountFor(f,f.f().customer(),"MON_002","2000");
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.TRANSFER,"25",other,null));
        // Cash through a real branch till: three deposits just under the threshold, then one above it.
        var branch=branchService.create(ADMIN,b,new com.cbs.access.AccessModels.BranchInput(bank.legalEntities().getFirst().id(),"MON","Monitoring branch","Open branch"));
        var teller=branchStaff(b,branch.id(),"mon-teller",com.cbs.access.AccessModels.BranchRole.TELLER);
        var checker=branchStaff(b,branch.id(),"mon-checker",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var owner=new Actor(TestIdentity.ISSUER,teller.subject(),false,UUID.randomUUID());var reviewer=new Actor(TestIdentity.ISSUER,checker.subject(),false,UUID.randomUUID());
        approveHttp(approvals.assignment(ADMIN,UUID.randomUUID(),b,new com.cbs.access.BranchOperations.Assignment(service.get(ADMIN,b).version(),f.account(),branch.id(),"Assign customer")).id());
        var till=cashLocation(bank,branch.id(),f.source(),"MON_TILL",com.cbs.cash.CashModels.Type.TILL);
        approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),b,new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,b).version(),cur,"CASH_DEPOSIT","5000","10000","Fictional cash limits")).id());
        for(UUID staff:java.util.Arrays.asList(null,teller.id()))approveHttp(approvals.branchLimit(ADMIN,UUID.randomUUID(),b,new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,b).version(),branch.id(),staff,cur,"CASH_DEPOSIT","5000","10000","Fictional scoped cash limits")).id());
        postCash(owner,reviewer,bank,cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.OPEN,till,null,null,null,List.of()));
        for(String amount:List.of("400","350","300"))postCash(owner,reviewer,bank,customerCashChange(bank,branch.id(),till,com.cbs.cash.CashModels.Kind.DEPOSIT,f.account(),f.liability().id(),amount));
        UUID largeCash=postCash(owner,reviewer,bank,customerCashChange(bank,branch.id(),till,com.cbs.cash.CashModels.Kind.DEPOSIT,f.account(),f.liability().id(),"1200"));
        // Open monitoring alerts never block the customer.
        int raised=monitoring.scan();
        assertThat(raised).isGreaterThanOrEqualTo(4);
        var rules=jdbc.queryForList("SELECT rule FROM platform.monitoring_alert WHERE tenant_id=? AND account_id=?",String.class,b,f.account());
        assertThat(rules).contains("LARGE_CASH","STRUCTURING","VELOCITY","UNUSUAL_AMOUNT");
        assertThat(jdbc.queryForObject("SELECT details->>'sourceId' FROM platform.monitoring_alert WHERE tenant_id=? AND rule='LARGE_CASH'",String.class,b)).isEqualTo(largeCash.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_alert WHERE tenant_id=? AND rule='UNUSUAL_AMOUNT' AND details->>'sourceId'=?",Long.class,b,bigFunding.toString())).isOne();
        assertThat(jdbc.queryForObject("SELECT (details->>'deposits')::int FROM platform.monitoring_alert WHERE tenant_id=? AND rule='STRUCTURING'",Integer.class,b)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_event WHERE tenant_id=? AND account_id=? AND channel='TRANSFER' AND direction='IN'",Long.class,b,other)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_event WHERE tenant_id=? AND channel='CASH'",Long.class,b)).isEqualTo(4);
        com.cbs.compliance.ComplianceService.requireClear(jdbc,b,f.f().customer(),customerVersion(f.f().customer()));
        // A rescan re-reads the overlap window but neither duplicates history nor alerts.
        long events=jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_event WHERE tenant_id=?",Long.class,b);
        assertThat(monitoring.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_event WHERE tenant_id=?",Long.class,b)).isEqualTo(events);
        // Review: one alert is dismissed with approval, another escalated into a transaction review case.
        UUID velocity=jdbc.queryForObject("SELECT id FROM platform.monitoring_alert WHERE tenant_id=? AND rule='VELOCITY'",UUID.class,b);
        UUID large=jdbc.queryForObject("SELECT id FROM platform.monitoring_alert WHERE tenant_id=? AND rule='LARGE_CASH'",UUID.class,b);
        mvc.perform(admin(get("/api/v1/admin/tenants/"+b+"/compliance/monitoring/alerts/"+large))).andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.rule").value("LARGE_CASH")).andExpect(jsonPath("$.movements.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
        var dismissal=approvals.complianceDismiss(ADMIN,UUID.randomUUID(),b,new com.cbs.compliance.AutomationModels.Dismiss(service.get(ADMIN,b).version(),com.cbs.compliance.AutomationModels.ItemType.MONITORING_ALERT,velocity,0,"MON/PAYROLL","Known payroll pattern"));
        approveHttp(dismissal.id());
        assertThat(jdbc.queryForObject("SELECT status FROM platform.monitoring_alert WHERE id=?",String.class,velocity)).isEqualTo("DISMISSED");
        var esc=json.writeValueAsString(Map.of("expectedVersion",service.get(ADMIN,b).version(),"itemVersion",0,"evidenceReference","MON/SOURCE-OF-FUNDS","reason","Source of cash not evidenced"));
        mvc.perform(admin(post("/api/v1/admin/tenants/"+b+"/compliance/monitoring/alerts/"+large+"/escalate")).contentType("application/json").content(esc)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT c.category FROM platform.monitoring_alert a JOIN platform.compliance_case c ON c.id=a.case_id WHERE a.id=?",String.class,large)).isEqualTo("TRANSACTION_REVIEW");
        assertCode(()->com.cbs.compliance.ComplianceService.requireClear(jdbc,b,f.f().customer(),customerVersion(f.f().customer())),"CUSTOMER_COMPLIANCE_RESTRICTED");
        mvc.perform(admin(get("/api/v1/admin/tenants/"+b+"/compliance/monitoring/alerts?status=ALL"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(rules.size()));
        mvc.perform(admin(get("/api/v1/admin/tenants/"+b+"/compliance/monitoring/policies"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].summary.structuring").exists());
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.monitoring_event WHERE tenant_id=?",b)).hasMessageContaining("immutable");
    }

    @Autowired org.springframework.context.ApplicationContext context;

    // Access review as a test: every mapped route rejects anonymous and forged callers, the admin namespace
    // rejects authenticated non-administrators, and the public allowlist cannot silently grow.
    @Test void everyRouteRequiresAuthenticationExceptTheDeclaredPublicOnes() throws Exception {
        var mapping = context.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var declaredPublic = java.util.Set.of("GET /", "GET /admin", "GET /admin/", "GET /portal/config",
                "POST /api/v1/payments/mpesa/callback/{id}/{token}");
        var seenPublic = new java.util.TreeSet<String>();
        var problems = new java.util.ArrayList<String>();
        String operator = TestIdentity.token("operator", false);
        int checked = 0, adminRoutes = 0;
        for (var info : mapping.getHandlerMethods().keySet()) {
            var methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? java.util.Set.of(org.springframework.web.bind.annotation.RequestMethod.GET)
                    : info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                for (var method : methods) {
                    String route = method + " " + pattern;
                    if (declaredPublic.contains(route)) { seenPublic.add(route); continue; }
                    String path = pattern.replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000001");
                    var verb = org.springframework.http.HttpMethod.valueOf(method.name());
                    int anonymous = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(verb, java.net.URI.create(path))).andReturn().getResponse().getStatus();
                    int forged = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(verb, java.net.URI.create(path))
                            .header("Authorization", "Bearer not-a-real-token")).andReturn().getResponse().getStatus();
                    if (anonymous != 401 || forged != 401) { problems.add(route + " anonymous=" + anonymous + " forged=" + forged); }
                    if (pattern.startsWith("/api/v1/admin/")) {
                        adminRoutes++;
                        int staff = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(verb, java.net.URI.create(path))
                                .header("Authorization", "Bearer " + operator)).andReturn().getResponse().getStatus();
                        if (staff != 403) { problems.add(route + " non-administrator=" + staff); }
                    }
                    checked++;
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(seenPublic).containsExactlyInAnyOrderElementsOf(declaredPublic);
        assertThat(checked).isGreaterThanOrEqualTo(250);
        assertThat(adminRoutes).isGreaterThanOrEqualTo(150);
        int callback = mvc.perform(post("/api/v1/payments/mpesa/callback/" + UUID.randomUUID() + "/guess")
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus();
        assertThat(callback).isBetween(400, 499);
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
                    service.update(ADMIN, tenant.id(), new UpdateRequest(0L, "Updated", "en-IN", "Correct display name"));
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
        var settings=reference.get(ADMIN,tenant.id());
        var change=new com.cbs.reference.ReferenceModels.Change(settings.version(),List.of(
                new com.cbs.reference.ReferenceModels.CurrencyRule("INR","HALF_EVEN",true),
                new com.cbs.reference.ReferenceModels.CurrencyRule("EUR","HALF_EVEN",true)),List.of("en-IN","hi-IN"),"hi-IN",
                settings.businessTimezone(),settings.businessDate(),settings.weekendDays(),settings.holidays(),"Prepare European entity and language");
        var pending=approvals.settings(ADMIN,UUID.randomUUID(),tenant.id(),change);
        approveHttp(pending.id());
        tenant=service.get(ADMIN,tenant.id());
        LegalEntityInput entity = new LegalEntityInput("Example Europe", "DE", "EUR", "Europe/Berlin");
        Tenant updated = invokeTenant(post("/api/v1/admin/tenants/" + tenant.id() + "/legal-entities"),
                new AddLegalEntityRequest(tenant.version(), entity, "Add legal entity"));
        assertThat(updated.legalEntities()).hasSize(2);
        updated = invokeTenant(patch("/api/v1/admin/tenants/" + tenant.id()),
                new UpdateRequest(updated.version(), "बैंक", "hi-IN", "Localize display"));
        assertThat(updated.displayName()).isEqualTo("बैंक");
        assertThat(updated.defaultLanguage()).isEqualTo("hi-IN");
        mvc.perform(admin(get("/api/v1/admin/tenants/" + tenant.id() + "/audit"))).andExpect(status().isOk());
        assertThat(service.auditEvents(ADMIN,tenant.id(),100,0).items()).anySatisfy(event->{
            assertThat(event.action()).isEqualTo("TENANT_UPDATED");assertThat(event.changeDetails().get("after")).asString().contains("बैंक");
        });
        UUID tenantId=tenant.id();
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.tenant_audit SET reason='rewrite' WHERE tenant_id=?", tenantId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM platform.tenant_audit WHERE tenant_id=?", tenantId))
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
        assertThat(a.databaseBinding().schemaVersion()).isEqualTo("26");
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
                new UpdateRequest(0L, "Must not persist", tenant.defaultLanguage(), "Simulate audit-store failure")))
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
        String pending=mvc.perform(admin(patch("/api/v1/admin/staff/"+staff.id())).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(disabled)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString();
        accessMe(token).andExpect(status().isOk());
        approveHttp(json.readValue(pending,com.cbs.approvals.ApprovalModels.Request.class).id());
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

    @Test void approvalSubmissionIsRetrySafeAndCannotBeSelfApprovedOrReadAcrossIssuers() throws Exception {
        var staff=approvalStaff();
        var input=approvalUpdate(staff,"Reviewed name");
        UUID key=UUID.randomUUID();
        var request=approvals.staff(ADMIN,key,staff.id(),input);
        assertThat(access.staff(staff.id(),false).displayName()).isEqualTo(staff.displayName());
        assertThat(approvals.staff(ADMIN,key,staff.id(),input).id()).isEqualTo(request.id());
        assertCode(()->approvals.staff(ADMIN,key,staff.id(),approvalUpdate(staff,"Different name")),"IDEMPOTENCY_CONFLICT");
        mvc.perform(admin(post("/api/v1/admin/approvals/"+request.id()+"/approve")).contentType("application/json").content("{\"reason\":\"My own request\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/approvals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/approvals").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(patch("/api/v1/admin/staff/"+staff.id())).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
        var foreign=new Actor("https://different-issuer.test",ADMIN.subject(),true,UUID.randomUUID());
        assertCode(()->approvals.details(foreign,request.id()),"APPROVAL_NOT_FOUND");
        assertThat(approvals.list(foreign,com.cbs.approvals.ApprovalModels.Scope.INBOX,null,null,100,0).items()).isEmpty();
        assertThat(approvals.list(ADMIN,com.cbs.approvals.ApprovalModels.Scope.MINE,null,null,100,0).items()).extracting("id").contains(request.id());
        approveHttp(request.id());
        assertThat(access.staff(staff.id(),false).displayName()).isEqualTo("Reviewed name");
        assertThat(approvals.staff(ADMIN,key,staff.id(),input).status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.APPROVED);
        assertThat(approvals.details(ADMIN,request.id()).events()).extracting("action").containsExactly("SUBMITTED","APPROVED");
        assertThat(approvals.list(ADMIN,com.cbs.approvals.ApprovalModels.Scope.HISTORY,com.cbs.approvals.ApprovalModels.Action.STAFF_UPDATE,null,100,0).items()).extracting("id").contains(request.id());
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.approval_event WHERE request_id=?",request.id())).isInstanceOf(DataAccessException.class);
    }

    @Test void rejectingAndCancellingLeaveTheTargetUnchanged() {
        var staff=approvalStaff();
        var first=approvals.staff(ADMIN,UUID.randomUUID(),staff.id(),approvalUpdate(staff,"Rejected name"));
        var result=approvals.decide(CHECKER,first.id(),com.cbs.approvals.ApprovalModels.Decision.REJECT,"Insufficient evidence");
        assertThat(result.status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.REJECTED);
        assertCode(()->approvals.decide(CHECKER,first.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Changed mind"),"APPROVAL_ALREADY_DECIDED");
        var second=approvals.staff(ADMIN,UUID.randomUUID(),staff.id(),approvalUpdate(staff,"Cancelled name"));
        assertCode(()->approvals.decide(CHECKER,second.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Not mine"),"ONLY_MAKER_CAN_CANCEL");
        assertThat(approvals.decide(ADMIN,second.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Withdraw request").status())
                .isEqualTo(com.cbs.approvals.ApprovalModels.Status.CANCELLED);
        assertThat(access.staff(staff.id(),false).version()).isZero();
    }

    @Test void aStaleApprovalNeverOverwritesALaterChange() {
        var staff=approvalStaff();
        var first=approvals.staff(ADMIN,UUID.randomUUID(),staff.id(),approvalUpdate(staff,"First approved value"));
        var second=approvals.staff(ADMIN,UUID.randomUUID(),staff.id(),approvalUpdate(staff,"Outdated value"));
        approvals.decide(CHECKER,first.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verified");
        assertThat(approvals.decide(CHECKER,second.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verified").status())
                .isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        assertThat(access.staff(staff.id(),false).displayName()).isEqualTo("First approved value");
        assertThat(access.staff(staff.id(),false).version()).isEqualTo(1);
        assertThat(approvals.details(ADMIN,second.id()).events()).extracting("action").containsExactly("SUBMITTED","STALE");
    }

    @Test void concurrentApprovalsApplyOnceAndReturnTheSameDecision() throws Exception {
        var staff=approvalStaff();
        var request=approvals.staff(ADMIN,UUID.randomUUID(),staff.id(),approvalUpdate(staff,"Once only"));
        CyclicBarrier start=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<com.cbs.approvals.ApprovalModels.Request> task=()->{start.await();return approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verified");};
            var results=executor.invokeAll(List.of(task,task));
            for(var result:results)assertThat(result.get().status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.APPROVED);
        }
        assertThat(access.staff(staff.id(),false).version()).isEqualTo(1);
        assertThat(approvals.details(ADMIN,request.id()).events()).hasSize(2);
    }

    @Test void disabledMakerAndFailedBusinessRulesCannotApplyAnApproval() {
        var makerStaff=approvalStaff();
        var target=approvalStaff();
        var maker=new Actor(ADMIN.issuer(),makerStaff.subject(),true,UUID.randomUUID());
        var request=approvals.staff(maker,UUID.randomUUID(),target.id(),approvalUpdate(target,"Must not apply"));
        access.update(ADMIN,makerStaff.id(),new com.cbs.access.AccessModels.StaffUpdate(0L,makerStaff.displayName(),null,false,"Disable maker"));
        assertCode(()->approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review"),"MAKER_DISABLED");
        assertThat(access.staff(target.id(),false).version()).isZero();
        assertThat(approvals.details(ADMIN,request.id()).events()).hasSize(1);
        var checker=access.requireAssignable(CHECKER.issuer(),CHECKER.subject());
        var selfDisable=approvals.staff(ADMIN,UUID.randomUUID(),checker.id(),new com.cbs.access.AccessModels.StaffUpdate(checker.version(),checker.displayName(),null,false,"Disable checker"));
        assertCode(()->approvals.decide(CHECKER,selfDisable.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review"),"SELF_DEACTIVATION_FORBIDDEN");
        assertThat(approvals.details(ADMIN,selfDisable.id()).request().status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.PENDING);
        assertThat(access.staff(checker.id(),false).active()).isTrue();
        assertThat(approvals.details(ADMIN,selfDisable.id()).events()).hasSize(1);
    }

    @Test void bankSettingsRequireApprovalAndUseExactCurrencyRounding() throws Exception {
        Tenant bank=register("reference-main");
        var initial=reference.get(ADMIN,bank.id());
        assertThat(initial.currencies()).extracting("code").containsExactly("INR");
        var change=new com.cbs.reference.ReferenceModels.Change(initial.version(),List.of(
                new com.cbs.reference.ReferenceModels.CurrencyRule("INR","HALF_EVEN",true),
                new com.cbs.reference.ReferenceModels.CurrencyRule("JPY","HALF_EVEN",true),
                new com.cbs.reference.ReferenceModels.CurrencyRule("BHD","HALF_UP",true)),List.of("en-IN","sw"),"sw",
                "Africa/Nairobi",initial.businessDate(),initial.weekendDays(),List.of(),"Enable bank currencies and Swahili");
        UUID key=UUID.randomUUID();
        String body=mvc.perform(admin(put("/api/v1/admin/tenants/"+bank.id()+"/settings")).header("Idempotency-Key",key)
                .contentType("application/json").content(json.writeValueAsString(change)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.action").value("BANK_SETTINGS"))
                .andReturn().getResponse().getContentAsString();
        var request=json.readValue(body,com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(reference.get(ADMIN,bank.id()).defaultLanguage()).isEqualTo("en-IN");
        assertCode(()->reference.round(ADMIN,bank.id(),"JPY","2.5"),"CURRENCY_NOT_ENABLED");
        assertThat(approvals.settings(ADMIN,key,bank.id(),change).id()).isEqualTo(request.id());
        approveHttp(request.id());
        assertThat(reference.get(ADMIN,bank.id()).defaultLanguage()).isEqualTo("sw");
        assertThat(service.get(ADMIN,bank.id()).defaultLanguage()).isEqualTo("sw");
        assertThat(reference.round(ADMIN,bank.id(),"INR","1.245").roundedAmount()).isEqualTo("1.24");
        assertThat(reference.round(ADMIN,bank.id(),"JPY","2.5").roundedAmount()).isEqualTo("2");
        assertThat(reference.round(ADMIN,bank.id(),"BHD","1.2345").roundedAmount()).isEqualTo("1.235");
        assertThat(reference.round(ADMIN,bank.id(),"BHD","-1.2345").roundedAmount()).isEqualTo("-1.235");
        assertCode(()->reference.round(ADMIN,bank.id(),"INR","1e1000000"),"INVALID_AMOUNT");
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items()).extracting("action").contains("BANK_SETTINGS_UPDATED");
        mvc.perform(admin(get("/api/v1/admin/reference-data/catalogue"))).andExpect(status().isOk());
    }

    @Test void settingsRejectInvalidReferenceDataAndDirectLanguageBypass() throws Exception {
        Tenant bank=register("reference-guards");
        var s=reference.get(ADMIN,bank.id());
        var base=settingsChange(s);
        var tree=json.valueToTree(base);
        ((tools.jackson.databind.node.ObjectNode)tree).put("defaultLanguage","fr");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),json.treeToValue(tree,com.cbs.reference.ReferenceModels.Change.class)),"DEFAULT_LANGUAGE_REQUIRED");
        var disabled=new com.cbs.reference.ReferenceModels.Change(s.version(),List.of(new com.cbs.reference.ReferenceModels.CurrencyRule("INR","HALF_EVEN",false)),s.languages(),s.defaultLanguage(),s.businessTimezone(),s.businessDate(),s.weekendDays(),s.holidays(),"Disable functional currency");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),disabled),"FUNCTIONAL_CURRENCY_REQUIRED");
        var invalid=new com.cbs.reference.ReferenceModels.Change(s.version(),List.of(new com.cbs.reference.ReferenceModels.CurrencyRule("ZZZ","HALF_EVEN",true)),s.languages(),s.defaultLanguage(),s.businessTimezone(),s.businessDate(),s.weekendDays(),s.holidays(),"Invalid currency");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),invalid),"INVALID_CURRENCY_SETTINGS");
        assertCode(()->service.update(ADMIN,bank.id(),new UpdateRequest(bank.version(),bank.displayName(),"fr","Bypass approval")),"SETTINGS_APPROVAL_REQUIRED");
        assertCode(()->service.addLegalEntity(ADMIN,bank.id(),new AddLegalEntityRequest(bank.version(),new LegalEntityInput("USD entity","US","USD","America/New_York"),"New entity")),"CURRENCY_NOT_ENABLED");
        mvc.perform(admin(put("/api/v1/admin/tenants/"+bank.id()+"/settings")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(reference.get(ADMIN,bank.id()).version()).isEqualTo(s.version());
    }

    @Test void bankSettingsAreIsolatedAndMembershipCannotBeForged() throws Exception {
        Tenant first=register("reference-isolation"),second=register("reference-other");
        service.setMembership(ADMIN,first.id(),new MembershipRequest(first.version(),"operator-a",BankRole.BANK_AUDITOR,true,"Read own settings"));
        mvc.perform(get("/api/v1/banks/"+first.id()+"/reference-data").header("Authorization","Bearer "+TestIdentity.token("operator-a",false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bankId").value(first.id().toString()));
        mvc.perform(get("/api/v1/banks/"+second.id()+"/reference-data").header("Authorization","Bearer "+TestIdentity.token("operator-a",false)).header("X-Tenant-ID",first.id()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tenants/"+first.id()+"/settings").header("Authorization","Bearer "+TestIdentity.token("operator-a",false))).andExpect(status().isForbidden());
        mvc.perform(admin(get("/api/v1/banks/"+second.id()+"/reference-data"))).andExpect(status().isForbidden());
        var request=approvals.settings(ADMIN,UUID.randomUUID(),first.id(),settingsChange(reference.get(ADMIN,first.id())));
        approveHttp(request.id());
        assertThat(reference.get(ADMIN,second.id()).version()).isZero();
    }

    @Test void staleSettingsApprovalCannotOverwriteBankChanges() {
        Tenant bank=register("reference-stale");
        var s=reference.get(ADMIN,bank.id());
        var request=approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),settingsChange(s));
        service.update(ADMIN,bank.id(),new UpdateRequest(bank.version(),"Changed bank name",bank.defaultLanguage(),"Update name"));
        assertThat(approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        assertThat(reference.get(ADMIN,bank.id()).currencies()).isEqualTo(s.currencies());
    }

    @Test void calendarsRespectHolidaysWeekendsAndForwardOnlyBusinessDates() throws Exception {
        Tenant bank=register("reference-calendar");
        var s=reference.get(ADMIN,bank.id());
        java.time.LocalDate date=s.businessDate();
        var holidayDate=com.cbs.reference.ReferenceDataService.nextWorkingDay(date,s.weekendDays(),List.of());
        var holidays=List.of(new com.cbs.reference.ReferenceModels.Holiday(holidayDate,"Bank holiday"));
        var change=new com.cbs.reference.ReferenceModels.Change(s.version(),s.currencies(),s.languages(),s.defaultLanguage(),"Africa/Nairobi",date,s.weekendDays(),holidays,"Set bank holiday");
        approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),change).id());
        assertThat(reference.day(ADMIN,bank.id(),holidayDate).workingDay()).isFalse();
        assertThat(reference.day(ADMIN,bank.id(),holidayDate).holiday()).isEqualTo("Bank holiday");
        var current=reference.get(ADMIN,bank.id());
        var backwards=new com.cbs.reference.ReferenceModels.Change(current.version(),current.currencies(),current.languages(),current.defaultLanguage(),current.businessTimezone(),date.minusDays(1),current.weekendDays(),current.holidays(),"Backdate");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),backwards),"BUSINESS_DATE_BACKWARDS");
        var nonWorking=new com.cbs.reference.ReferenceModels.Change(current.version(),current.currencies(),current.languages(),current.defaultLanguage(),current.businessTimezone(),holidayDate,current.weekendDays(),current.holidays(),"Use holiday");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),nonWorking),"BUSINESS_DATE_NOT_WORKING");
        var next=reference.day(ADMIN,bank.id(),date).nextWorkingDay();
        assertThat(next).isAfter(holidayDate);
        mvc.perform(admin(get("/api/v1/admin/tenants/"+bank.id()+"/calendar-day?date="+holidayDate))).andExpect(status().isOk()).andExpect(jsonPath("$.workingDay").value(false));
        assertThat(com.cbs.reference.ReferenceDataService.nextWorkingDay(java.time.LocalDate.of(2024,2,28),List.of(5,6),List.of(new com.cbs.reference.ReferenceModels.Holiday(java.time.LocalDate.of(2024,2,29),"Leap holiday"))))
                .isEqualTo(java.time.LocalDate.of(2024,3,3));
    }

    @Test void languagePreferencesAreOwnedByTheAuthenticatedUser() throws Exception {
        var staff=approvalStaff();
        String token=TestIdentity.token(staff.subject(),false);
        mvc.perform(put("/api/v1/access/preferences").header("Authorization","Bearer "+token).contentType("application/json").content("{\"language\":\"fr\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.language").value("fr"));
        mvc.perform(get("/api/v1/access/preferences").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.language").value("fr"));
        assertThat(reference.preference(ADMIN).language()).isEqualTo("en");
        assertThat(access.staff(staff.id(),false).version()).isZero();
        mvc.perform(put("/api/v1/access/preferences").header("Authorization","Bearer "+token).contentType("application/json").content("{\"language\":\"invalid_@@\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void chartChangesRequireAnotherAdministratorAndReplaySafely() throws Exception {
        Tenant bank=chartBank("chart-main");
        var change=chartChange(bank.id(),null,"1000","Assets",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.GROUP,null,true);
        UUID key=UUID.randomUUID();
        String path="/api/v1/admin/tenants/"+bank.id()+"/chart-of-accounts";
        var pending=json.readValue(mvc.perform(admin(post(path)).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(change)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.action").value("CHART_ACCOUNT")).andReturn().getResponse().getContentAsString(),com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(chart.list(ADMIN,bank.id(),"",null,null,null,25,0).total()).isZero();
        assertCode(()->approvals.decide(ADMIN,pending.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self approve"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(pending.id());
        var account=chart.list(ADMIN,bank.id(),"assets",null,true,null,25,0).items().getFirst();
        assertThat(account.code()).isEqualTo("1000");
        assertThat(approvals.chart(ADMIN,key,bank.id(),change).id()).isEqualTo(pending.id());
        assertThat(chart.list(ADMIN,bank.id(),"",null,null,null,25,0).total()).isEqualTo(1);
        var edit=chartChange(bank.id(),account.id(),"1000","Total assets",account.category(),account.kind(),null,true);
        var update=approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),edit);
        assertThat(chart.get(bank.id(),account.id()).name()).isEqualTo("Assets");
        assertThat(update.beforeValues()).containsEntry("name","Assets");
        approveHttp(update.id());
        assertThat(chart.get(bank.id(),account.id()).name()).isEqualTo("Total assets");
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items().stream().filter(a->a.action().equals("CHART_ACCOUNT_CHANGED")).count()).isEqualTo(2);
        mvc.perform(admin(get(path+"?search=Total&category=ASSET&limit=1"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("Total assets"));
        mvc.perform(admin(get(path+"?offset=1&limit=1"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void chartEnforcesHierarchyUniquenessAndBankBoundaries() throws Exception {
        Tenant bank=chartBank("chart-guards"),other=chartBank("chart-other");
        var rootChange=chartChange(bank.id(),null,"1000","Assets",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.GROUP,null,true);
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),rootChange).id());
        var root=chart.list(ADMIN,bank.id(),"",null,null,null,25,0).items().getFirst();
        var child=chartChange(bank.id(),null,"1100","Cash",root.category(),com.cbs.accounting.ChartModels.Kind.POSTING,root.id(),true);
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),child).id());
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),null,"1000","Duplicate",root.category(),root.kind(),null,true)),"CHART_CODE_IN_USE");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),other.id(),chartChange(other.id(),null,"1100","Foreign parent",root.category(),root.kind(),root.id(),true)),"CHART_ACCOUNT_NOT_FOUND");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),null,"2000","Wrong category",com.cbs.accounting.ChartModels.Category.LIABILITY,root.kind(),root.id(),true)),"CHART_INVALID_PARENT");
        var cash=chart.list(ADMIN,bank.id(),"1100",null,null,null,25,0).items().getFirst();
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),null,"1110","Bad parent",root.category(),root.kind(),cash.id(),true)),"CHART_INVALID_PARENT");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),root.id(),"1000","Assets",root.category(),root.kind(),null,false)),"CHART_ACTIVE_CHILDREN");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),root.id(),"9999","Changed code",root.category(),root.kind(),null,true)),"CHART_STRUCTURE_IMMUTABLE");
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),cash.id(),cash.code(),cash.name(),cash.category(),cash.kind(),root.id(),false)).id());
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),root.id(),root.code(),root.name(),root.category(),root.kind(),null,false)).id());
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),cash.id(),cash.code(),cash.name(),cash.category(),cash.kind(),root.id(),true)),"CHART_INVALID_PARENT");
        assertThat(chart.list(ADMIN,other.id(),"",null,null,null,25,0).total()).isZero();
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),other.id(),chartChange(other.id(),null,"1000","Other bank assets",root.category(),root.kind(),null,true)).id());
        assertThat(chart.get(bank.id(),root.id()).name()).isEqualTo("Assets");
    }

    @Test void chartApiRejectsUnauthorizedInvalidAndInactiveBankChanges() throws Exception {
        Tenant bank=register("chart-access");
        String path="/api/v1/admin/tenants/"+bank.id()+"/chart-of-accounts";
        var input=chartChange(bank.id(),null,"1000","Assets",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.GROUP,null,true);
        String body=json.writeValueAsString(input);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer "+TestIdentity.token("operator",false)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHART_BANK_NOT_ACTIVE"));
        mvc.perform(admin(post(path)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(String invalid:List.of(body.replace("1000","bad code"),body.replace("Assets","   "),body.replace("ASSET","INVALID"),body.replace("\"active\":true","\"active\":null"))) {
            mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(admin(get(path+"?limit=101"))).andExpect(status().isBadRequest());
    }

    @Test void competingChartApprovalsCannotOverwriteEachOther() throws Exception {
        Tenant bank=chartBank("chart-race");
        var input=chartChange(bank.id(),null,"1000","Assets",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.GROUP,null,true);
        var one=approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),input);
        var two=approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),input);
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var results=executor.invokeAll(List.<Callable<com.cbs.approvals.ApprovalModels.Status>>of(
                    ()->{barrier.await();return approvals.decide(CHECKER,one.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Approve first").status();},
                    ()->{barrier.await();return approvals.decide(CHECKER,two.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Approve second").status();}));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(com.cbs.approvals.ApprovalModels.Status.APPROVED,com.cbs.approvals.ApprovalModels.Status.STALE);
        }
        assertThat(chart.list(ADMIN,bank.id(),"",null,null,null,25,0).total()).isEqualTo(1);
    }

    @Test void chartAccountDecisionAndAuditRollBackTogether() {
        Tenant bank=chartBank("chart-rollback");
        var input=chartChange(bank.id(),null,"1000","Assets",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.GROUP,null,true);
        var pending=approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),input);
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(s->{
            approvals.decide(CHECKER,pending.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback simulation");
            assertThat(chart.list(ADMIN,bank.id(),"",null,null,null,25,0).total()).isEqualTo(1);
            s.setRollbackOnly();
        });
        assertThat(chart.list(ADMIN,bank.id(),"",null,null,null,25,0).total()).isZero();
        assertThat(approvals.details(ADMIN,pending.id()).request().status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.PENDING);
        assertThat(approvals.details(ADMIN,pending.id()).events()).hasSize(1);
        assertThat(service.get(ADMIN,bank.id()).version()).isEqualTo(bank.version());
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items()).noneMatch(a->a.action().equals("CHART_ACCOUNT_CHANGED"));
    }

    private UUID ledgerBankId;
    private UUID ledgerOtherId;
    private Tenant ledgerBank() {
        if(ledgerBankId==null)ledgerBankId=provision(register("ledger-main")).id();
        return service.get(ADMIN,ledgerBankId);
    }
    private Tenant ledgerOther() {
        if(ledgerOtherId==null)ledgerOtherId=provision(register("ledger-other")).id();
        return service.get(ADMIN,ledgerOtherId);
    }
    private com.cbs.accounting.ChartModels.Account ledgerPosting(Tenant bank) throws Exception {
        String code="L"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
        var change=chartChange(bank.id(),null,code,"Cash posting definition",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.POSTING,null,true);
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),change).id());
        return chart.list(ADMIN,bank.id(),code,null,null,null,25,0).items().getFirst();
    }
    private com.cbs.accounting.LedgerModels.Change ledgerChange(Tenant bank,UUID ledgerId,String code,UUID chartId,String name,boolean active) {
        return new com.cbs.accounting.LedgerModels.Change(service.get(ADMIN,bank.id()).version(),ledgerId,code,name,chartId,bank.legalEntities().getFirst().id(),bank.legalEntities().getFirst().functionalCurrency(),active,"Set up bank ledger");
    }
    private long bankLedgerCount(String code) throws Exception {
        try(var c=TestDatabases.bank("ledger");var s=c.prepareStatement("SELECT count(*) FROM bank.ledger_account WHERE code=?")){
            s.setString(1,code);try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    @Test void ledgerApprovalAndDeliveryStoreAccountsOnlyInTheirOwnBank() throws Exception {
        Tenant bank=ledgerBank(),other=ledgerOther();var posting=ledgerPosting(bank);
        var input=ledgerChange(bank,null,"CASH_INR",posting.id(),"Cash INR",true);UUID key=UUID.randomUUID();
        String path="/api/v1/admin/tenants/"+bank.id()+"/ledger-accounts";
        var request=json.readValue(mvc.perform(admin(post(path)).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.action").value("LEDGER_ACCOUNT")).andReturn().getResponse().getContentAsString(),com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(bankLedgerCount("CASH_INR")).isZero();
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self review"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(request.id());
        assertThat(bankLedgerCount("CASH_INR")).isZero();
        assertThat(ledger.list(ADMIN,bank.id(),"CASH_INR",null,null,25,0).items().getFirst().delivery()).isEqualTo("PENDING");
        ledger.retry(ADMIN,bank.id(),request.id());ledger.retry(ADMIN,bank.id(),request.id());
        var saved=ledger.list(ADMIN,bank.id(),"CASH_INR","INR",true,25,0).items().getFirst();
        assertThat(saved.delivery()).isEqualTo("APPLIED");assertThat(bankLedgerCount("CASH_INR")).isEqualTo(1);
        assertThat(approvals.ledger(ADMIN,key,bank.id(),input).id()).isEqualTo(request.id());
        assertThat(ledger.list(ADMIN,other.id(),"CASH_INR",null,null,25,0).total()).isZero();
        try(var c=TestDatabases.bank("ledgerother");var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM bank.ledger_account WHERE code='CASH_INR'")){r.next();assertThat(r.getLong(1)).isZero();}
        assertCode(()->bankDatabases.openOwned(other.id(),"ledger-main"),"DATABASE_OWNERSHIP_MISMATCH");
        assertCode(()->ledger.retry(ADMIN,other.id(),request.id()),"APPROVAL_NOT_FOUND");
        var edited=ledgerChange(bank,saved.definition().id(),"CASH_INR",posting.id(),"Cash reserve INR",false);
        var change=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),edited);approveHttp(change.id());ledger.deliver(change.id());
        var result=ledger.list(ADMIN,bank.id(),"reserve",null,false,25,0).items().getFirst();
        assertThat(result.definition().version()).isEqualTo(1);assertThat(result.definition().name()).isEqualTo("Cash reserve INR");
        assertThat(result.definition().active()).isFalse();
        mvc.perform(admin(get(path+"?search=reserve&currency=INR&active=false"))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].delivery").value("APPLIED"));
    }
    @Test void ledgerDeliverySurvivesControlRollbackAfterBankCommit() throws Exception {
        Tenant bank=ledgerBank();var posting=ledgerPosting(bank);
        var input=ledgerChange(bank,null,"RECOVERY_INR",posting.id(),"Recovery ledger",true);
        var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),input);
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{
            approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback approval");s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.ledger_delivery WHERE request_id=?",Integer.class,request.id())).isZero();
        assertThat(bankLedgerCount("RECOVERY_INR")).isZero();
        approveHttp(request.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{ledger.deliver(request.id());s.setRollbackOnly();});
        assertThat(bankLedgerCount("RECOVERY_INR")).isEqualTo(1);
        assertThat(ledger.list(ADMIN,bank.id(),"RECOVERY_INR",null,null,25,0).items().getFirst().delivery()).isEqualTo("PENDING");
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=executor.invokeAll(List.<Callable<Boolean>>of(()->{barrier.await();ledger.deliver(request.id());return true;},()->{barrier.await();ledger.deliver(request.id());return true;}));
            for(var task:tasks)assertThat(task.get()).isTrue();
        }
        assertThat(ledger.list(ADMIN,bank.id(),"RECOVERY_INR",null,null,25,0).items().getFirst().delivery()).isEqualTo("APPLIED");
        try(var c=TestDatabases.bank("ledger");var s=c.prepareStatement("SELECT count(*) FROM bank.ledger_event WHERE request_id=?")){
            s.setObject(1,request.id());try(var r=s.executeQuery()){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
        }
    }
    @Test void ledgerFailureRollsBackBankWriteAndCanBeRetried() throws Exception {
        Tenant bank=ledgerBank();var posting=ledgerPosting(bank);
        var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,"RETRY_INR",posting.id(),"Retry ledger",true));approveHttp(request.id());
        try(var c=TestDatabases.bank("ledger");var s=c.createStatement()){
            s.execute("ALTER TABLE bank.ledger_event RENAME TO ledger_event_offline");
            try{ledger.deliver(request.id());assertThat(bankLedgerCount("RETRY_INR")).isZero();}
            finally{s.execute("ALTER TABLE bank.ledger_event_offline RENAME TO ledger_event");}
        }
        var pending=ledger.list(ADMIN,bank.id(),"RETRY_INR",null,null,25,0).items().getFirst();
        assertThat(pending.delivery()).isEqualTo("PENDING");assertThat(pending.failureCode()).isEqualTo("LEDGER_DATABASE_UNAVAILABLE");
        ledger.retry(ADMIN,bank.id(),request.id());assertThat(bankLedgerCount("RETRY_INR")).isEqualTo(1);
        assertThat(ledger.list(ADMIN,bank.id(),"RETRY_INR",null,null,25,0).items().getFirst().failureCode()).isNull();
    }
    @Test void ledgerValidationProtectsBankChartCurrencyAndPendingDependencies() throws Exception {
        Tenant bank=ledgerBank(),other=ledgerOther();var posting=ledgerPosting(bank);var foreign=ledgerPosting(other);
        var base=ledgerChange(bank,null,"GUARDS_INR",posting.id(),"Guard ledger",true);
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,"FOREIGN",foreign.id(),"Wrong chart",true)),"CHART_ACCOUNT_NOT_FOUND");
        var wrongEntity=new com.cbs.accounting.LedgerModels.Change(base.expectedVersion(),null,base.code(),base.name(),base.chartAccountId(),other.legalEntities().getFirst().id(),"INR",true,base.reason());
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),wrongEntity),"LEGAL_ENTITY_NOT_IN_BANK");
        var usd=new com.cbs.accounting.LedgerModels.Change(base.expectedVersion(),null,base.code(),base.name(),base.chartAccountId(),base.legalEntityId(),"USD",true,base.reason());
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),usd),"CURRENCY_NOT_ENABLED");
        var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),base);approveHttp(request.id());
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,base.code(),posting.id(),"Duplicate",true)),"LEDGER_CODE_IN_USE");
        var saved=ledger.list(ADMIN,bank.id(),base.code(),null,null,25,0).items().getFirst().definition();
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,saved.id(),saved.code(),posting.id(),"Rename",true)),"LEDGER_DELIVERY_PENDING");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),posting.id(),posting.code(),posting.name(),posting.category(),posting.kind(),null,false)),"CHART_USED_BY_LEDGER");
        ledger.deliver(request.id());
        var edit=ledgerChange(bank,saved.id(),"NEW_CODE",posting.id(),"Changed structure",true);
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),edit),"LEDGER_STRUCTURE_IMMUTABLE");
        var config=reference.get(ADMIN,bank.id());
        var rules=new java.util.ArrayList<>(config.currencies());rules.add(new com.cbs.reference.ReferenceModels.CurrencyRule("USD","HALF_EVEN",true));
        var enable=new com.cbs.reference.ReferenceModels.Change(config.version(),rules,config.languages(),config.defaultLanguage(),config.businessTimezone(),config.businessDate(),config.weekendDays(),config.holidays(),"Enable USD");
        approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),enable).id());
        var usdInput=new com.cbs.accounting.LedgerModels.Change(service.get(ADMIN,bank.id()).version(),null,"GUARDS_USD","USD ledger",posting.id(),base.legalEntityId(),"USD",true,"Create USD ledger");
        approveHttp(approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),usdInput).id());
        var now=reference.get(ADMIN,bank.id());
        var disable=new com.cbs.reference.ReferenceModels.Change(now.version(),config.currencies(),now.languages(),now.defaultLanguage(),now.businessTimezone(),now.businessDate(),now.weekendDays(),now.holidays(),"Remove USD");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),disable),"CURRENCY_USED_BY_LEDGER");
    }
    @Test void ledgerApiChecksAuthorizationAndStaleApprovals() throws Exception {
        Tenant bank=ledgerBank();var posting=ledgerPosting(bank);String path="/api/v1/admin/tenants/"+bank.id()+"/ledger-accounts";
        var input=ledgerChange(bank,null,"STALE_INR",posting.id(),"Stale ledger",true);String body=json.writeValueAsString(input);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer "+TestIdentity.token("operator",false)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace("STALE_INR","invalid code"))).andExpect(status().isBadRequest());
        var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),input);
        Tenant latest=service.get(ADMIN,bank.id());service.update(ADMIN,bank.id(),new UpdateRequest(latest.version(),"Renamed bank",latest.defaultLanguage(),"Bank update"));
        assertThat(approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        assertThat(bankLedgerCount("STALE_INR")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.ledger_delivery WHERE request_id=?",Integer.class,request.id())).isZero();
    }
    @Test void existingBankUpgradePreservesOwnershipAndIsRetryable() throws Exception {
        Tenant bank=register("ledger-upgrade");
        // Emulate a bank provisioned by the previous release, without ledger tables.
        try(var c=TestDatabases.bank("ledgerupgrade");var s=c.createStatement()){
            s.execute("CREATE SCHEMA cbs_identity");s.execute("CREATE TABLE cbs_identity.owner(singleton boolean PRIMARY KEY,tenant_id uuid NOT NULL)");
            try(var p=c.prepareStatement("INSERT INTO cbs_identity.owner VALUES (true,?)")){p.setObject(1,bank.id());p.executeUpdate();}
            org.flywaydb.core.Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true))
                    .defaultSchema("bank").schemas("bank").locations("classpath:db/bank-migration").target("1").load().migrate();
        }
        jdbc.update("UPDATE platform.tenant SET status='ACTIVE' WHERE id=?",bank.id());
        assertThat(ledger.list(ADMIN,bank.id(),"",null,null,25,0).storageReady()).isFalse();
        ledger.initialize(ADMIN,bank.id(),new ChangeRequest(0L,"Prepare ledgers"));
        assertThat(ledger.list(ADMIN,bank.id(),"",null,null,25,0).storageReady()).isTrue();
        ledger.initialize(ADMIN,bank.id(),new ChangeRequest(service.get(ADMIN,bank.id()).version(),"Recheck upgrade"));
        assertThat(service.get(ADMIN,bank.id()).databaseBinding().schemaVersion()).isEqualTo("26");
    }

    private List<com.cbs.accounting.LedgerModels.Definition> journalLedgers(Tenant bank) throws Exception {
        var posting=ledgerPosting(bank);
        var result=new java.util.ArrayList<com.cbs.accounting.LedgerModels.Definition>();
        for(int n=0;n<2;n++){
            String code="J"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,code,posting.id(),"Journal test ledger",true));
            approveHttp(request.id());ledger.deliver(request.id());
            result.add(ledger.list(ADMIN,bank.id(),code,null,null,25,0).items().getFirst().definition());
        }
        return result;
    }
    private com.cbs.accounting.JournalModels.Change journalChange(Tenant bank,List<com.cbs.accounting.LedgerModels.Definition> accounts,String amount){
        return new com.cbs.accounting.JournalModels.Change(service.get(ADMIN,bank.id()).version(),"TEST-"+UUID.randomUUID(),"Test journal",
                reference.get(ADMIN,bank.id()).businessDate(),bank.legalEntities().getFirst().id(),"INR",
                List.of(new com.cbs.accounting.JournalModels.Line(accounts.get(0).id(),com.cbs.accounting.JournalModels.Side.DEBIT,amount),new com.cbs.accounting.JournalModels.Line(accounts.get(1).id(),com.cbs.accounting.JournalModels.Side.CREDIT,amount)),null,"Verify journal posting");
    }
    private String journalBalance(UUID id) throws Exception {
        try(var c=TestDatabases.bank("ledger");var s=c.prepareStatement("SELECT debit-credit FROM bank.ledger_balance WHERE ledger_id=?")){
            s.setObject(1,id);try(var r=s.executeQuery()){return r.next()?r.getBigDecimal(1).stripTrailingZeros().toPlainString():"0";}
        }
    }
    @Test void journalApprovalPostingAndReversalAreAuditedAndIdempotent() throws Exception {
        Tenant bank=ledgerBank();var accounts=journalLedgers(bank);var input=journalChange(bank,accounts,"9007199254740993.17");UUID key=UUID.randomUUID();
        String path="/api/v1/admin/tenants/"+bank.id()+"/journals";
        var request=json.readValue(mvc.perform(admin(post(path)).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.action").value("JOURNAL_POST")).andReturn().getResponse().getContentAsString(),com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(journalBalance(accounts.get(0).id())).isEqualTo("0");
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(request.id());assertThat(journalBalance(accounts.get(0).id())).isEqualTo("0");
        var a=accounts.getFirst();
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,a.id(),a.code(),a.chartAccountId(),a.name(),false)),"LEDGER_JOURNAL_PENDING");
        journals.retry(ADMIN,bank.id(),request.id());journals.retry(ADMIN,bank.id(),request.id());
        assertThat(journalBalance(a.id())).isEqualTo("9007199254740993.17");
        assertThat(journalBalance(accounts.get(1).id())).isEqualTo("-9007199254740993.17");
        assertThat(approvals.journal(ADMIN,key,bank.id(),input).id()).isEqualTo(request.id());
        assertCode(()->approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),input),"JOURNAL_REFERENCE_IN_USE");
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,a.id(),a.code(),a.chartAccountId(),a.name(),false)),"LEDGER_BALANCE_NOT_ZERO");
        mvc.perform(admin(get(path+"/"+request.id()))).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].amount").value("9007199254740993.17"));
        assertThat(journals.list(ADMIN,bank.id(),100,0).items()).anyMatch(i->i.entry().id().equals(request.id())&&i.postingStatus().equals("POSTED"));
        UUID reversalKey=UUID.randomUUID();var reverseInput=new com.cbs.accounting.JournalModels.Reversal(service.get(ADMIN,bank.id()).version(),"REV-"+UUID.randomUUID(),"Reverse erroneous entry");
        var reversal=approvals.reverseJournal(ADMIN,reversalKey,bank.id(),request.id(),reverseInput);approveHttp(reversal.id());journals.deliver(reversal.id());
        assertThat(journalBalance(a.id())).isEqualTo("0");assertThat(journalBalance(accounts.get(1).id())).isEqualTo("0");
        assertThat(journals.posted(ADMIN,bank.id(),reversal.id()).reversalOf()).isEqualTo(request.id());
        assertThat(approvals.reverseJournal(ADMIN,reversalKey,bank.id(),request.id(),reverseInput).id()).isEqualTo(reversal.id());
        assertCode(()->approvals.reverseJournal(ADMIN,UUID.randomUUID(),bank.id(),request.id(),reverseInput),"JOURNAL_REFERENCE_IN_USE");
        assertCode(()->approvals.reverseJournal(ADMIN,UUID.randomUUID(),bank.id(),reversal.id(),reverseInput),"JOURNAL_REVERSAL_INVALID");
        try(var c=TestDatabases.bank("ledger");var s=c.createStatement()){
            assertThatThrownBy(()->s.executeUpdate("UPDATE bank.journal SET reference='tamper' WHERE id='"+request.id()+"'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("DELETE FROM bank.journal_line WHERE journal_id='"+request.id()+"'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO bank.journal_line VALUES ('"+request.id()+"',99,'"+a.id()+"','DEBIT',1)")).isInstanceOf(SQLException.class);
        }
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items()).anyMatch(e->e.action().equals("JOURNAL_POSTED"));
    }
    @Test void journalBankCommitSurvivesLostAcknowledgementAndConcurrentRetries() throws Exception {
        Tenant bank=ledgerBank();var accounts=journalLedgers(bank);var input=journalChange(bank,accounts,"0.10");var request=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),input);
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Roll back decision");s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.journal_delivery WHERE request_id=?",Integer.class,request.id())).isZero();
        approveHttp(request.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{journals.deliver(request.id());s.setRollbackOnly();});
        assertThat(journalBalance(accounts.get(0).id())).isEqualTo("0.1");
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.journal_delivery WHERE request_id=?",Boolean.class,request.id())).isFalse();
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=executor.invokeAll(List.<Callable<Boolean>>of(()->{barrier.await();journals.deliver(request.id());return true;},()->{barrier.await();journals.deliver(request.id());return true;}));
            for(var task:tasks)assertThat(task.get()).isTrue();
        }
        assertThat(journalBalance(accounts.get(0).id())).isEqualTo("0.1");
        var second=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"0.20"));approveHttp(second.id());
        var third=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"0.30"));approveHttp(third.id());
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=executor.invokeAll(List.<Callable<Boolean>>of(()->{journals.deliver(second.id());return true;},()->{journals.deliver(third.id());return true;}));
            for(var task:tasks)assertThat(task.get()).isTrue();
        }
        assertThat(journalBalance(accounts.get(0).id())).isEqualTo("0.6");assertThat(journalBalance(accounts.get(1).id())).isEqualTo("-0.6");
    }
    @Test void manualJournalDeliveryRechecksActiveParticipantsBeforeFirstPosting() throws Exception {
        Tenant bank=ledgerBank();var ledgers=journalLedgers(bank);
        String maker="w02-maker-"+UUID.randomUUID(),checker="w02-checker-"+UUID.randomUUID();
        access.bootstrap(TestIdentity.ISSUER,maker);access.bootstrap(TestIdentity.ISSUER,checker);
        var makerActor=new Actor(TestIdentity.ISSUER,maker,true,UUID.randomUUID());
        var checkerActor=new Actor(TestIdentity.ISSUER,checker,true,UUID.randomUUID());
        var request=approvals.journal(makerActor,UUID.randomUUID(),bank.id(),journalChange(bank,ledgers,"25.00"));
        approvals.decide(checkerActor,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent review");
        jdbc.update("UPDATE platform.staff_identity SET active=false WHERE issuer=? AND subject=?",TestIdentity.ISSUER,maker);
        journals.deliver(request.id());
        assertThat(journalBalance(ledgers.getFirst().id())).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.journal_delivery WHERE request_id=?",String.class,request.id())).isEqualTo("APPROVAL_PARTICIPANT_DISABLED");
        jdbc.update("UPDATE platform.staff_identity SET active=true WHERE issuer=? AND subject=?",TestIdentity.ISSUER,maker);
        jdbc.update("UPDATE platform.staff_identity SET active=false WHERE issuer=? AND subject=?",TestIdentity.ISSUER,checker);
        journals.deliver(request.id());assertThat(journalBalance(ledgers.getFirst().id())).isEqualTo("0");
        jdbc.update("UPDATE platform.staff_identity SET active=true WHERE issuer=? AND subject=?",TestIdentity.ISSUER,checker);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{journals.deliver(request.id());t.setRollbackOnly();});
        assertThat(journalBalance(ledgers.getFirst().id())).isEqualTo("25");
        jdbc.update("UPDATE platform.staff_identity SET active=false WHERE issuer=? AND subject IN (?,?)",TestIdentity.ISSUER,maker,checker);
        journals.deliver(request.id());journals.deliver(request.id());
        assertThat(journalBalance(ledgers.getFirst().id())).isEqualTo("25");
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.journal_delivery WHERE request_id=?",Boolean.class,request.id())).isTrue();
    }
    @Test void journalFailureRollsBackAllLinesAndBalances() throws Exception {
        Tenant bank=ledgerBank();var accounts=journalLedgers(bank);var request=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"123.45"));approveHttp(request.id());
        try(var c=TestDatabases.bank("ledger");var s=c.createStatement()){
            // Force failure on the second line after the first line's balance update.
            s.execute("ALTER TABLE bank.journal_line ADD CONSTRAINT simulate_failure CHECK(side='DEBIT') NOT VALID");
            try{journals.deliver(request.id());assertThat(journalBalance(accounts.get(0).id())).isEqualTo("0");assertCode(()->journals.posted(ADMIN,bank.id(),request.id()),"JOURNAL_NOT_POSTED");}
            finally{s.execute("ALTER TABLE bank.journal_line DROP CONSTRAINT simulate_failure");}
        }
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.journal_delivery WHERE request_id=?",String.class,request.id())).isEqualTo("JOURNAL_DATABASE_UNAVAILABLE");
        journals.retry(ADMIN,bank.id(),request.id());assertThat(journalBalance(accounts.get(0).id())).isEqualTo("123.45");
    }
    @Test void journalValidationEnforcesScopePrecisionBalanceDateAndAccess() throws Exception {
        Tenant bank=ledgerBank(),other=ledgerOther();var accounts=journalLedgers(bank);var input=journalChange(bank,accounts,"10.00");String path="/api/v1/admin/tenants/"+bank.id()+"/journals";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer "+TestIdentity.token("operator",false)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        for(String amount:List.of("0","-1","1.001","1e2","1000000000000000000.00"))assertCode(()->approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,amount)),"JOURNAL_AMOUNT_INVALID");
        String body=json.writeValueAsString(input);
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replaceFirst("10.00","10.01"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOURNAL_UNBALANCED"));
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace(input.businessDate().toString(),input.businessDate().minusDays(1).toString()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOURNAL_BUSINESS_DATE_INVALID"));
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace(input.legalEntityId().toString(),other.legalEntities().getFirst().id().toString()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_IN_BANK"));
        var foreign=journalLedgers(other);
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace(accounts.getFirst().id().toString(),foreign.getFirst().id().toString()))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOURNAL_LEDGER_NOT_READY"));
        var pending=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),input);
        Tenant latest=service.get(ADMIN,bank.id());service.update(ADMIN,bank.id(),new UpdateRequest(latest.version(),"Journal test renamed bank",latest.defaultLanguage(),"Change configuration"));
        assertThat(approvals.decide(CHECKER,pending.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review old request").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        var valid=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"7.25"));approveHttp(valid.id());journals.deliver(valid.id());
        assertCode(()->journals.posted(ADMIN,other.id(),valid.id()),"JOURNAL_NOT_POSTED");
        assertCode(()->journals.retry(ADMIN,other.id(),valid.id()),"APPROVAL_NOT_FOUND");
        assertThat(journals.balances(ADMIN,other.id(),100,0).items()).noneMatch(b->b.ledgerId().equals(accounts.getFirst().id()));
    }

    @Test void reportsUsePostedBusinessDatesAndExactStatementBalances() throws Exception {
        Tenant bank=ledgerBank();var accounts=journalLedgers(bank);var input=journalChange(bank,accounts,"9007199254740993.17");
        var request=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(request.id());
        var kind=com.cbs.accounting.AccountingReportService.Kind.TRIAL_BALANCE;
        var pending=reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,input.businessDate(),null);
        assertThat(pending.rows().stream().filter(r->r.getFirst().equals(accounts.getFirst().code())).findFirst().orElseThrow().get(4)).isEqualTo("0");
        // Report reads bank commits even when the control acknowledgement was lost.
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{journals.deliver(request.id());s.setRollbackOnly();});
        var trial=reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,input.businessDate(),null);
        assertThat(trial.reconciled()).isTrue();
        assertThat(trial.rows().stream().filter(r->r.getFirst().equals(accounts.getFirst().code())).findFirst().orElseThrow().get(4)).isEqualTo("9007199254740993.17");
        var earlier=reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,input.businessDate().minusDays(1),null);
        assertThat(earlier.rows().stream().filter(r->r.getFirst().equals(accounts.getFirst().code())).findFirst().orElseThrow().get(4)).isEqualTo("0");
        var statement=reports.generate(ADMIN,bank.id(),com.cbs.accounting.AccountingReportService.Kind.LEDGER_STATEMENT,input.legalEntityId(),"INR",input.businessDate(),input.businessDate(),accounts.getFirst().id());
        assertThat(statement.rows()).hasSize(1);assertThat(statement.summary()).containsEntry("Opening net debit","0").containsEntry("Closing net debit","9007199254740993.17");
        var nextDay=reports.generate(ADMIN,bank.id(),com.cbs.accounting.AccountingReportService.Kind.LEDGER_STATEMENT,input.legalEntityId(),"INR",input.businessDate().plusDays(1),input.businessDate().plusDays(1),accounts.getFirst().id());
        assertThat(nextDay.rows()).isEmpty();assertThat(nextDay.summary()).containsEntry("Opening net debit","9007199254740993.17").containsEntry("Closing net debit","9007199254740993.17");
        journals.deliver(request.id());
        var reverse=approvals.reverseJournal(ADMIN,UUID.randomUUID(),bank.id(),request.id(),new com.cbs.accounting.JournalModels.Reversal(service.get(ADMIN,bank.id()).version(),"RPT-REV-"+UUID.randomUUID(),"Test statement reversal"));approveHttp(reverse.id());journals.deliver(reverse.id());
        var reversed=reports.generate(ADMIN,bank.id(),com.cbs.accounting.AccountingReportService.Kind.LEDGER_STATEMENT,input.legalEntityId(),"INR",input.businessDate(),input.businessDate(),accounts.getFirst().id());
        assertThat(reversed.rows()).hasSize(2);assertThat(reversed.summary()).containsEntry("Closing net debit","0");assertThat(reversed.rows().getLast().get(4)).isEqualTo(request.id().toString());
    }
    @Test void reconciliationFindsTurnoverDriftEvenWhenNetBalanceMatches() throws Exception {
        Tenant bank=ledgerBank();var accounts=journalLedgers(bank);var input=journalChange(bank,accounts,"31.25");var request=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(request.id());journals.deliver(request.id());
        var kind=com.cbs.accounting.AccountingReportService.Kind.RECONCILIATION;
        assertThat(reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,null,null).reconciled()).isTrue();
        try(var c=TestDatabases.bank("ledger");var s=c.prepareStatement("UPDATE bank.ledger_balance SET debit=debit+?,credit=credit+? WHERE ledger_id=?")){
            s.setInt(1,1);s.setInt(2,1);s.setObject(3,accounts.getFirst().id());s.executeUpdate();
            try{
                var report=reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,null,null);
                assertThat(report.reconciled()).isFalse();assertThat(report.summary()).containsEntry("Mismatched accounts","1");
                var row=report.rows().stream().filter(r->r.getFirst().equals(accounts.getFirst().code())).findFirst().orElseThrow();
                assertThat(row.get(4)).isEqualTo("1");assertThat(row.get(7)).isEqualTo("1");assertThat(row.get(8)).isEqualTo("MISMATCH");
                assertThat(reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,null,null).reconciled()).isFalse();
            }finally{s.setInt(1,-1);s.setInt(2,-1);s.executeUpdate();}
        }
        assertThat(reports.generate(ADMIN,bank.id(),kind,input.legalEntityId(),"INR",null,null,null).reconciled()).isTrue();
    }
    @Test void reportApiEnforcesAccessScopeFiltersAndCsvResponse() throws Exception {
        Tenant bank=ledgerBank(),other=ledgerOther();var accounts=journalLedgers(bank);var entity=bank.legalEntities().getFirst().id();
        String path="/api/v1/admin/tenants/"+bank.id()+"/accounting-reports?kind=TRIAL_BALANCE&legalEntityId="+entity+"&currency=INR&toDate=2026-10-05";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(get(path))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(admin(get(path+"&csv=true"))).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=accounting-report.csv")).andExpect(content().contentTypeCompatibleWith("text/csv"));
        mvc.perform(admin(get(path.replace(entity.toString(),other.legalEntities().getFirst().id().toString())))).andExpect(status().isBadRequest());
        mvc.perform(admin(get(path.replace("2026-10-05","1800-01-01")))).andExpect(status().isBadRequest());
        var kind=com.cbs.accounting.AccountingReportService.Kind.LEDGER_STATEMENT;
        var day=java.time.LocalDate.of(2026,10,5);
        assertCode(()->reports.generate(ADMIN,other.id(),kind,other.legalEntities().getFirst().id(),"INR",day,day,accounts.getFirst().id()),"LEDGER_NOT_FOUND");
        assertCode(()->reports.generate(ADMIN,bank.id(),kind,entity,"INR",day.plusDays(1),day,accounts.getFirst().id()),"REPORT_FILTER_INVALID");
        assertCode(()->reports.generate(ADMIN,bank.id(),kind,entity,"USD",day,day,accounts.getFirst().id()),"LEDGER_NOT_FOUND");
        assertThat(reports.generate(ADMIN,bank.id(),com.cbs.accounting.AccountingReportService.Kind.TRIAL_BALANCE,entity,"JPY",null,day,null).rows()).isEmpty();
    }

    private com.cbs.accounting.DayCloseModels.Change closeChange(Tenant bank){
        var s=reference.get(ADMIN,bank.id());return new com.cbs.accounting.DayCloseModels.Change(service.get(ADMIN,bank.id()).version(),s.businessDate(),com.cbs.reference.ReferenceDataService.nextWorkingDay(s.businessDate(),s.weekendDays(),s.holidays()),"Close reconciled business day");
    }
    @Test void dayCloseRequiresApprovalFreezesPostingsAndRecoversAfterBankCommit() throws Exception {
        Tenant bank=provision(register("day-main"));var s=reference.get(ADMIN,bank.id());
        var friday=s.businessDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY));
        var calendar=new com.cbs.reference.ReferenceModels.Change(s.version(),s.currencies(),s.languages(),s.defaultLanguage(),s.businessTimezone(),friday,List.of(6,7),List.of(new com.cbs.reference.ReferenceModels.Holiday(friday.plusDays(3),"Monday holiday")),"Prepare closing test calendar");
        approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),calendar).id());
        var accounts=journalLedgers(bank);var journal=journalChange(bank,accounts,"20.25");var posting=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journal);approveHttp(posting.id());journals.deliver(posting.id());
        var input=closeChange(bank);assertThat(input.nextBusinessDate()).isEqualTo(friday.plusDays(4));UUID key=UUID.randomUUID();
        var request=approvals.closeDay(ADMIN,key,bank.id(),input);
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self review"),"SELF_APPROVAL_FORBIDDEN");
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback approval");t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.business_day_close WHERE request_id=?",Integer.class,request.id())).isZero();
        approveHttp(request.id());
        assertCode(()->approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"1")),"DAY_CLOSE_IN_PROGRESS");
        var account=accounts.getFirst();assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,account.id(),account.code(),account.chartAccountId(),"Renamed",true)),"DAY_CLOSE_IN_PROGRESS");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),settingsChange(reference.get(ADMIN,bank.id()))),"DAY_CLOSE_IN_PROGRESS");
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{days.deliver(request.id());t.setRollbackOnly();});
        var waiting=days.dashboard(ADMIN,bank.id(),25,0);assertThat(waiting.businessDate()).isEqualTo(friday);assertThat(waiting.closedThrough()).isEqualTo(friday);assertThat(waiting.blockers()).contains("DAY_CLOSE_IN_PROGRESS");
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=executor.invokeAll(List.<Callable<Boolean>>of(()->{barrier.await();days.retry(ADMIN,bank.id(),request.id());return true;},()->{barrier.await();days.retry(ADMIN,bank.id(),request.id());return true;}));for(var task:tasks)assertThat(task.get()).isTrue();
        }
        var done=days.dashboard(ADMIN,bank.id(),25,0);assertThat(done.businessDate()).isEqualTo(friday.plusDays(4));assertThat(done.history().getFirst().status()).isEqualTo("CLOSED");
        assertThat(done.history().getFirst().totals().getFirst().debitBalance()).isEqualTo("20.25");assertThat(done.history().getFirst().totals().getFirst().creditBalance()).isEqualTo("20.25");
        assertThat(approvals.closeDay(ADMIN,key,bank.id(),input).id()).isEqualTo(request.id());
        var staleEntry=new com.cbs.accounting.JournalModels.Entry(UUID.randomUUID(),bank.id(),"LATE-"+UUID.randomUUID(),"Late write",friday,journal.legalEntityId(),"INR",journal.lines(),null);
        assertCode(()->bankJournals.post(service.get(ADMIN,bank.id()),new com.cbs.accounting.JournalModels.Dispatch(staleEntry,"administrator",TestIdentity.ISSUER,"checker",UUID.randomUUID())),"JOURNAL_DATABASE_UNAVAILABLE");
        try(var c=TestDatabases.bank("dayclose");var st=c.createStatement();var r=st.executeQuery("SELECT count(*) FROM bank.business_day_close")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        var nextPosting=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"1"));approveHttp(nextPosting.id());journals.deliver(nextPosting.id());assertThat(journals.posted(ADMIN,bank.id(),nextPosting.id()).businessDate()).isEqualTo(friday.plusDays(4));
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items()).anyMatch(a->a.action().equals("BUSINESS_DAY_CLOSED"));
    }
    @Test void dayCloseBlocksPendingWorkAndRechecksAtApproval() throws Exception {
        Tenant bank=provision(register("day-guards"));var accounts=journalLedgers(bank);
        String path="/api/v1/admin/tenants/"+bank.id()+"/business-day";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        var pending=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"5"));
        assertCode(()->approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),closeChange(bank)),"DAY_CLOSE_PENDING_APPROVALS");
        approveHttp(pending.id());assertCode(()->approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),closeChange(bank)),"DAY_CLOSE_PENDING_DELIVERIES");journals.deliver(pending.id());
        var input=closeChange(bank);var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),input);
        var late=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"1"));
        assertCode(()->approvals.decide(CHECKER,closing.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Check current work"),"DAY_CLOSE_PENDING_APPROVALS");
        approvals.decide(ADMIN,late.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Defer posting");
        approveHttp(closing.id());
        assertCode(()->days.retry(ADMIN,ledgerOther().id(),closing.id()),"APPROVAL_NOT_FOUND");days.deliver(closing.id());
        var settings=reference.get(ADMIN,bank.id());var advance=new com.cbs.reference.ReferenceModels.Change(settings.version(),settings.currencies(),settings.languages(),settings.defaultLanguage(),settings.businessTimezone(),com.cbs.reference.ReferenceDataService.nextWorkingDay(settings.businessDate(),settings.weekendDays(),settings.holidays()),settings.weekendDays(),settings.holidays(),"Attempt settings bypass");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),advance),"BUSINESS_DATE_REQUIRES_CLOSE");
        mvc.perform(admin(get(path))).andExpect(status().isOk()).andExpect(jsonPath("$.history[0].status").value("CLOSED"));
        var wrong=new com.cbs.accounting.DayCloseModels.Change(settings.version(),settings.businessDate(),settings.businessDate(),"Invalid next date");
        mvc.perform(admin(post(path+"/close")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(wrong))).andExpect(status().isBadRequest());
    }
    @Test void dayCloseRefusesBalanceDriftAndRetriesWithoutAdvancingEarly() throws Exception {
        Tenant bank=provision(register("day-failure"));var accounts=journalLedgers(bank);var posting=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,accounts,"40"));approveHttp(posting.id());journals.deliver(posting.id());
        try(var c=TestDatabases.bank("dayfailure");var st=c.prepareStatement("UPDATE bank.ledger_balance SET debit=debit+? WHERE ledger_id=?")){
            st.setInt(1,1);st.setObject(2,accounts.getFirst().id());st.executeUpdate();
            try{assertCode(()->approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),closeChange(bank)),"DAY_CLOSE_UNRECONCILED");}
            finally{st.setInt(1,-1);st.executeUpdate();}
            var input=closeChange(bank);var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(closing.id());
            st.setInt(1,1);st.executeUpdate();
            try{
                days.deliver(closing.id());var state=days.dashboard(ADMIN,bank.id(),25,0);
                assertThat(state.businessDate()).isEqualTo(input.businessDate());assertThat(state.closedThrough()).isNull();assertThat(state.history().getFirst().failureCode()).isEqualTo("DAY_CLOSE_UNRECONCILED");
            }finally{st.setInt(1,-1);st.executeUpdate();}
            days.retry(ADMIN,bank.id(),closing.id());assertThat(days.dashboard(ADMIN,bank.id(),25,0).businessDate()).isEqualTo(input.nextBusinessDate());
        }
    }


    private com.cbs.accounting.ChartModels.Account productMapping(Tenant bank) throws Exception {
        var input=new com.cbs.accounting.ChartModels.Change(service.get(ADMIN,bank.id()).version(),null,"2100","Customer deposits","",
            com.cbs.accounting.ChartModels.Category.LIABILITY,com.cbs.accounting.ChartModels.Balance.CREDIT,com.cbs.accounting.ChartModels.Kind.POSTING,null,true,"Product mapping");
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),input).id());return chart.list(ADMIN,bank.id(),"2100",null,null,null,25,0).items().getFirst();
    }
    private com.cbs.products.ProductModels.Change productChange(Tenant bank,UUID id,UUID mapping,String code,String currency,String opening,String minimum,boolean active) {
        return new com.cbs.products.ProductModels.Change(service.get(ADMIN,bank.id()).version(),id,code,"Everyday savings","Deposit product",
            com.cbs.products.ProductModels.Type.SAVINGS,currency,opening,minimum,mapping,active,"Configure deposit product");
    }
    @Test void productApprovalPreservesExactAmountsAndImmutableRevisions() throws Exception {
        Tenant bank=chartBank("product-main");var mapping=productMapping(bank);UUID key=UUID.randomUUID();
        var input=productChange(bank,null,mapping.id(),"SAV_INR","INR","9007199254740993.25","10.25",true);
        var pending=approvals.product(ADMIN,key,bank.id(),input);
        assertThat(products.list(ADMIN,bank.id(),"",null,null,25,0).total()).isZero();
        assertCode(()->approvals.decide(ADMIN,pending.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self review"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(pending.id());var product=products.list(ADMIN,bank.id(),"SAV_",null,true,25,0).items().getFirst();
        assertThat(product.minimumOpening()).isEqualTo("9007199254740993.25");assertThat(product.revision()).isEqualTo(1);
        assertThat(approvals.product(ADMIN,key,bank.id(),input).id()).isEqualTo(pending.id());
        assertCode(()->approvals.product(ADMIN,key,bank.id(),productChange(bank,product.id(),mapping.id(),"SAV_INR","INR","100","5",false)),"IDEMPOTENCY_CONFLICT");
        var edit=approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,product.id(),mapping.id(),"SAV_INR","INR","100","5",false));approveHttp(edit.id());
        var history=products.history(ADMIN,bank.id(),product.id(),25,0).items();assertThat(history).hasSize(2);
        assertThat(history.getFirst().definition()).containsEntry("minimumOpening","100").containsEntry("active",false);
        assertThat(history.getLast().definition()).containsEntry("minimumOpening","9007199254740993.25");
        assertThat(products.get(bank.id(),product.id()).active()).isFalse();
        assertThatThrownBy(()->jdbc.update("UPDATE platform.deposit_product_revision SET reason='tamper' WHERE product_id=?",product.id())).isInstanceOf(DataAccessException.class);
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items()).filteredOn(a->a.action().equals("DEPOSIT_PRODUCT_CHANGED")).hasSize(2);
    }
    @Test void productRulesProtectBankMappingsCurrenciesAndPrecision() throws Exception {
        Tenant bank=chartBank("product-guards"),other=chartBank("product-other");var mapping=productMapping(bank);var foreign=productMapping(other);
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,foreign.id(),"BAD","INR","1","0",true)),"CHART_ACCOUNT_NOT_FOUND");
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"BAD","USD","1","0",true)),"PRODUCT_CURRENCY_DISABLED");
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"BAD","INR","1.001","0",true)),"PRODUCT_INVALID_AMOUNT");
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"BAD","INR","1","2",true)),"PRODUCT_MINIMUM_ORDER");
        var asset=chartChange(bank.id(),null,"1100","Cash",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.POSTING,null,true);
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),asset).id());UUID assetId=chart.list(ADMIN,bank.id(),"1100",null,null,null,25,0).items().getFirst().id();
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,assetId,"BAD","INR","1","0",true)),"PRODUCT_INVALID_MAPPING");
        var settings=reference.get(ADMIN,bank.id());var enable=new com.cbs.reference.ReferenceModels.Change(settings.version(),List.of(new com.cbs.reference.ReferenceModels.CurrencyRule("INR","HALF_EVEN",true),new com.cbs.reference.ReferenceModels.CurrencyRule("USD","HALF_EVEN",true)),settings.languages(),settings.defaultLanguage(),settings.businessTimezone(),settings.businessDate(),settings.weekendDays(),settings.holidays(),"Enable USD");
        approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),enable).id());
        approveHttp(approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"SAV_USD","USD","1","0",true)).id());
        var product=products.list(ADMIN,bank.id(),"",null,null,25,0).items().getFirst();
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"SAV_USD","USD","1","0",true)),"PRODUCT_CODE_IN_USE");
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,product.id(),mapping.id(),"RENAMED","USD","1","0",true)),"PRODUCT_STRUCTURE_IMMUTABLE");
        var disableMapping=new com.cbs.accounting.ChartModels.Change(service.get(ADMIN,bank.id()).version(),mapping.id(),mapping.code(),mapping.name(),"",mapping.category(),mapping.normalBalance(),mapping.kind(),null,false,"Deactivate mapping");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),disableMapping),"CHART_USED_BY_PRODUCT");
        var now=reference.get(ADMIN,bank.id());var disableCurrency=new com.cbs.reference.ReferenceModels.Change(now.version(),settings.currencies(),now.languages(),now.defaultLanguage(),now.businessTimezone(),now.businessDate(),now.weekendDays(),now.holidays(),"Disable USD");
        assertCode(()->approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),disableCurrency),"CURRENCY_USED_BY_PRODUCT");
        approveHttp(approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,product.id(),mapping.id(),"SAV_USD","USD","1","0",false)).id());
        now=reference.get(ADMIN,bank.id());approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.reference.ReferenceModels.Change(now.version(),settings.currencies(),now.languages(),now.defaultLanguage(),now.businessTimezone(),now.businessDate(),now.weekendDays(),now.holidays(),"Disable unused USD")).id());
        assertCode(()->approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,product.id(),mapping.id(),"SAV_USD","USD","1","0",true)),"PRODUCT_CURRENCY_DISABLED");
        assertCode(()->products.get(other.id(),product.id()),"PRODUCT_NOT_FOUND");
        approveHttp(approvals.product(ADMIN,UUID.randomUUID(),other.id(),productChange(other,null,foreign.id(),"SAV_USD","INR","1","0",true)).id());
        assertThat(products.list(ADMIN,bank.id(),"SAV_USD",null,null,25,0).total()).isEqualTo(1);
    }
    @Test void productChangesAreAtomicRejectStaleAndReplayConcurrentSubmissions() throws Exception {
        Tenant bank=chartBank("product-race");var mapping=productMapping(bank);UUID key=UUID.randomUUID();var input=productChange(bank,null,mapping.id(),"STANDARD","INR","10","0",true);
        CyclicBarrier start=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<UUID> task=()->{start.await();return approvals.product(ADMIN,key,bank.id(),input).id();};
            var result=executor.invokeAll(List.of(task,task));assertThat(result.getFirst().get()).isEqualTo(result.getLast().get());
        }
        var request=approvals.product(ADMIN,key,bank.id(),input);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback test");t.setRollbackOnly();});
        assertThat(products.list(ADMIN,bank.id(),"",null,null,25,0).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.deposit_product_revision WHERE tenant_id=?",Integer.class,bank.id())).isZero();
        var competing=approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"ALTERNATIVE","INR","10","0",true));
        approveHttp(request.id());approvals.decide(CHECKER,competing.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review stale request");
        assertThat(approvals.details(ADMIN,competing.id()).request().status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        assertThat(products.list(ADMIN,bank.id(),"",null,null,25,0).total()).isEqualTo(1);
        var reject=approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"REJECTED","INR","10","0",true));
        approvals.decide(CHECKER,reject.id(),com.cbs.approvals.ApprovalModels.Decision.REJECT,"Not required");
        assertThat(products.list(ADMIN,bank.id(),"REJECTED",null,null,25,0).total()).isZero();
    }
    @Test void productHttpValidationAccessAndRevisionPagingAreEnforced() throws Exception {
        Tenant bank=chartBank("product-access");var mapping=productMapping(bank);String path="/api/v1/admin/tenants/"+bank.id()+"/products";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(get(path+"?limit=101"))).andExpect(status().isBadRequest());
        var input=productChange(bank,null,mapping.id(),"CURRENT_INR","INR","10.00","0",false);
        String body=json.writeValueAsString(input).replace("SAVINGS","CURRENT");
        var response=mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        approveHttp(json.readValue(response,com.cbs.approvals.ApprovalModels.Request.class).id());
        var product=products.list(ADMIN,bank.id(),"",com.cbs.products.ProductModels.Type.CURRENT,false,25,0).items().getFirst();
        mvc.perform(admin(get(path+"?type=CURRENT&active=false"))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].minimumOpening").value("10"));
        mvc.perform(admin(get(path+"/"+product.id()+"/revisions?limit=1&offset=1"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(admin(get(path+"/"+UUID.randomUUID()+"/revisions"))).andExpect(status().isNotFound());
    }


    private com.cbs.customers.CustomerModels.Profile customerProfile(String name,String doc,com.cbs.customers.CustomerModels.State state,String review) {
        return new com.cbs.customers.CustomerModels.Profile(name,java.time.LocalDate.of(1990,5,20),"KE","KE","Fictional test address","+254700000000","demo@example.test",com.cbs.customers.CustomerModels.DocumentType.NATIONAL_ID,"KE",doc,null,"DOC/TEST-001",state,review);
    }
    private com.cbs.customers.CustomerModels.Change customerChange(Tenant bank,UUID id,com.cbs.customers.CustomerModels.Profile profile,String duplicate) {
        return new com.cbs.customers.CustomerModels.Change(service.get(ADMIN,bank.id()).version(),id,profile,duplicate,"Review fictional customer");
    }
    @Test void customerOnboardingRequiresApprovalAndDeliversIsolatedProfile() throws Exception {
        Tenant bank=provision(register("customer-main"));var p=customerProfile("Fictional Person","AB-12345",com.cbs.customers.CustomerModels.State.PROSPECT,"");
        UUID key=UUID.randomUUID();var input=customerChange(bank,null,p,"");var request=approvals.customer(ADMIN,key,bank.id(),input);
        assertThat(request.proposedValues().get("documentNumber")).isEqualTo("••••45");
        assertThat(customers.list(ADMIN,bank.id(),"",null,25,0).total()).isZero();
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self review"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(request.id());var summary=customers.list(ADMIN,bank.id(),"Fictional",null,25,0).items().getFirst();assertThat(summary.delivery()).isEqualTo("PENDING");
        assertCode(()->approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,summary.id(),p,"")),"CUSTOMER_DELIVERY_PENDING");
        customers.deliver(request.id());var detail=customers.detail(ADMIN,bank.id(),summary.id());assertThat(detail.delivery()).isEqualTo("APPLIED");assertThat(detail.definition().profile()).isEqualTo(p);
        assertThat(approvals.customer(ADMIN,key,bank.id(),input).id()).isEqualTo(request.id());
        var active=customerProfile("Fictional Person","AB-12345",com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/001");
        var change=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,summary.id(),active,""));approveHttp(change.id());customers.deliver(change.id());
        assertThat(customers.detail(ADMIN,bank.id(),summary.id()).definition().version()).isEqualTo(1);
        assertThat(customers.list(ADMIN,bank.id(),"",com.cbs.customers.CustomerModels.State.ACTIVE,25,0).total()).isEqualTo(1);
        var blocked=customerProfile("Fictional Person","AB-12345",com.cbs.customers.CustomerModels.State.BLOCKED,"REVIEW/002");
        var block=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,summary.id(),blocked,""));approveHttp(block.id());customers.deliver(block.id());
        assertThat(customers.detail(ADMIN,bank.id(),summary.id()).definition().profile().state()).isEqualTo(com.cbs.customers.CustomerModels.State.BLOCKED);
        try(var c=TestDatabases.bank("customer");var st=c.createStatement()){
            try(var r=st.executeQuery("SELECT count(*) FROM bank.customer_event")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.customer_event")).isInstanceOf(SQLException.class);
        }
        assertThat(service.auditEvents(ADMIN,bank.id(),100,0).items()).anyMatch(a->a.action().equals("CUSTOMER_PROFILE_VIEWED"));
    }
    @Test void customerDuplicateReviewAndHttpAccessAreEnforced() throws Exception {
        Tenant bank=provision(register("customer-guards"));var p=customerProfile("Duplicate Example","AA001",com.cbs.customers.CustomerModels.State.PROSPECT,"");String path="/api/v1/admin/tenants/"+bank.id()+"/customers";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        var invalid=customerChange(bank,null,customerProfile("Invalid","ABC1",com.cbs.customers.CustomerModels.State.ACTIVE,""),"");
        assertCode(()->approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),invalid),"CUSTOMER_REVIEW_REQUIRED");
        String body=json.writeValueAsString(customerChange(bank,null,p,""));
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace("1990-05-20","2990-05-20"))).andExpect(status().isBadRequest());
        var approved=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,p,""));approveHttp(approved.id());customers.deliver(approved.id());
        assertCode(()->approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,customerProfile("Other Name","aa 001",com.cbs.customers.CustomerModels.State.PROSPECT,""),"Different person")),"CUSTOMER_DOCUMENT_DUPLICATE");
        var sameName=customerProfile("  duplicate   example  ","BB002",com.cbs.customers.CustomerModels.State.PROSPECT,"");
        assertCode(()->approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,sameName,"")),"CUSTOMER_DUPLICATE_REVIEW_REQUIRED");
        var reviewed=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,sameName,"Reviewed source documents; distinct test people"));approveHttp(reviewed.id());customers.deliver(reviewed.id());
        assertThat(customers.list(ADMIN,bank.id(),"",null,25,0).total()).isEqualTo(2);
        mvc.perform(admin(get(path))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].documentNumber").doesNotExist());
        mvc.perform(admin(get(path+"/"+UUID.randomUUID()))).andExpect(status().isNotFound());
        UUID customer=customers.list(ADMIN,bank.id(),"",null,25,0).items().getFirst().id();Tenant another=register("product-rollback");
        assertCode(()->customers.detail(ADMIN,another.id(),customer),"CUSTOMER_NOT_FOUND");
        assertCode(()->customers.retry(ADMIN,another.id(),approved.id()),"APPROVAL_NOT_FOUND");
    }
    @Test void customerDeliveryRecoversAfterBankCommitAndRejectedChangesStayAbsent() throws Exception {
        Tenant bank=provision(register("customer-recovery"));var p=customerProfile("Recovery Example","REC001",com.cbs.customers.CustomerModels.State.PROSPECT,"");var input=customerChange(bank,null,p,"");UUID key=UUID.randomUUID();
        CyclicBarrier start=new CyclicBarrier(2);UUID request;
        try(var pool=Executors.newFixedThreadPool(2)){Callable<UUID> task=()->{start.await();return approvals.customer(ADMIN,key,bank.id(),input).id();};var results=pool.invokeAll(List.of(task,task));request=results.getFirst().get();assertThat(request).isEqualTo(results.getLast().get());}
        UUID requestId=request;
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{approvals.decide(CHECKER,requestId,com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback approval");t.setRollbackOnly();});
        assertThat(customers.list(ADMIN,bank.id(),"",null,25,0).total()).isZero();approveHttp(request);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{customers.deliver(requestId);t.setRollbackOnly();});
        var summary=customers.list(ADMIN,bank.id(),"",null,25,0).items().getFirst();assertThat(summary.delivery()).isEqualTo("PENDING");
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.of(()->{customers.retry(CHECKER,bank.id(),requestId);return true;},()->{customers.retry(CHECKER,bank.id(),requestId);return true;}));for(var result:results)result.get();}
        assertThat(customers.detail(ADMIN,bank.id(),summary.id()).delivery()).isEqualTo("APPLIED");
        try(var c=TestDatabases.bank("customerfail");var st=c.createStatement();var r=st.executeQuery("SELECT count(*) FROM bank.customer_event")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        var rejected=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,customerProfile("Rejected Example","REC002",com.cbs.customers.CustomerModels.State.PROSPECT,""),""));
        approvals.decide(CHECKER,rejected.id(),com.cbs.approvals.ApprovalModels.Decision.REJECT,"Incomplete evidence");assertThat(customers.list(ADMIN,bank.id(),"Rejected",null,25,0).total()).isZero();
    }


    private record AccountFixture(Tenant bank,UUID customer,com.cbs.products.ProductModels.Product product) {}
    private AccountFixture accountFixture(String binding) throws Exception {
        var bank=provision(register(binding));
        if(binding.equals("payments-lifecycle"))jdbc.update("UPDATE platform.bank_settings SET business_date=DATE '2026-10-05' WHERE tenant_id=?",bank.id()); // Historical initial fixture, before any ledger exists.
        var mapping=productMapping(bank);
        var productRequest=approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,mapping.id(),"SAV_"+bank.legalEntities().getFirst().functionalCurrency(),bank.legalEntities().getFirst().functionalCurrency(),"100",binding.equals("closure-main")?"0":"10",true));approveHttp(productRequest.id());
        var customerRequest=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,customerProfile("Account Example","ACC001",com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/1"),""));approveHttp(customerRequest.id());customers.deliver(customerRequest.id());
        return new AccountFixture(bank,customers.list(ADMIN,bank.id(),"",null,25,0).items().getFirst().id(),products.list(ADMIN,bank.id(),"",null,null,25,0).items().getFirst());
    }
    private com.cbs.accounts.AccountModels.Change accountChange(AccountFixture f,UUID id,com.cbs.accounts.AccountModels.State state){
        return new com.cbs.accounts.AccountModels.Change(service.get(ADMIN,f.bank().id()).version(),id,f.customer(),f.product().id(),f.product().revision(),f.bank().legalEntities().getFirst().id(),"Personal savings",state,"Open or manage test account");
    }
    @Test void depositAccountPinsTermsAndControlsLifecycle() throws Exception {
        var f=accountFixture("account-main");var bank=f.bank();var waiting=com.cbs.accounts.AccountModels.State.AWAITING_FUNDING;
        var input=accountChange(f,null,waiting);UUID key=UUID.randomUUID();var r=approvals.account(ADMIN,key,bank.id(),input);
        assertCode(()->approvals.decide(ADMIN,r.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own request"),"SELF_APPROVAL_FORBIDDEN");
        assertThat(accounts.list(ADMIN,bank.id(),"",null,25,0).total()).isZero();approveHttp(r.id());
        var a=accounts.list(ADMIN,bank.id(),"",null,25,0).items().getFirst();assertThat(a.delivery()).isEqualTo("PENDING");
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,a.id(),waiting)),"ACCOUNT_DELIVERY_PENDING");
        accounts.deliver(r.id());assertThat(accounts.detail(ADMIN,bank.id(),a.id()).delivery()).isEqualTo("APPLIED");
        assertThat(approvals.account(ADMIN,key,bank.id(),input).id()).isEqualTo(r.id());
        var productEdit=approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,f.product().id(),f.product().liabilityAccountId(),"SAV_INR","INR","200","20",false));approveHttp(productEdit.id());
        var disableMapping=new com.cbs.accounting.ChartModels.Change(service.get(ADMIN,bank.id()).version(),f.product().liabilityAccountId(),"2100","Customer deposits","",com.cbs.accounting.ChartModels.Category.LIABILITY,com.cbs.accounting.ChartModels.Balance.CREDIT,com.cbs.accounting.ChartModels.Kind.POSTING,null,false,"Disable mapped chart");
        assertCode(()->approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),disableMapping),"CHART_USED_BY_ACCOUNT");
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,waiting)),"ACCOUNT_PRODUCT_NOT_READY");
        assertThat(accounts.detail(ADMIN,bank.id(),a.id()).definition().terms().revision()).isEqualTo(1);
        assertThat(accounts.detail(ADMIN,bank.id(),a.id()).definition().terms().minimumOpening()).isEqualTo("100");
        for(var state:List.of(com.cbs.accounts.AccountModels.State.FROZEN,waiting,com.cbs.accounts.AccountModels.State.CLOSED)){
            var change=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,a.id(),state));approveHttp(change.id());accounts.deliver(change.id());
            assertThat(accounts.detail(ADMIN,bank.id(),a.id()).definition().state()).isEqualTo(state);
        }
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,a.id(),waiting)),"ACCOUNT_ALREADY_CLOSED");
        try(var c=TestDatabases.bank("account");var st=c.createStatement()){
            try(var rs=st.executeQuery("SELECT count(*) FROM bank.account_event")){rs.next();assertThat(rs.getInt(1)).isEqualTo(4);}
            try(var rs=st.executeQuery("SELECT count(*) FROM bank.journal")){rs.next();assertThat(rs.getInt(1)).isZero();}
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.account_event")).isInstanceOf(SQLException.class);
        }
    }
    @Test void depositAccountEligibilityScopeStaleAndHttpValidation() throws Exception {
        var f=accountFixture("account-guards");var bank=f.bank();var waiting=com.cbs.accounts.AccountModels.State.AWAITING_FUNDING;String path="/api/v1/admin/tenants/"+bank.id()+"/accounts";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,com.cbs.accounts.AccountModels.State.FROZEN)),"ACCOUNT_INITIAL_STATE");
        var expired=new com.cbs.customers.CustomerModels.Profile("Expired",java.time.LocalDate.of(1990,1,1),"KE","KE","Test","+254700000000","",com.cbs.customers.CustomerModels.DocumentType.PASSPORT,"KE","EXP001",java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1),"DOC/EXP",com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/EXP");
        assertCode(()->com.cbs.customers.CustomerService.checkEligible(new com.cbs.customers.CustomerModels.Definition(UUID.randomUUID(),bank.id(),"TEST",0,expired)),"ACCOUNT_CUSTOMER_NOT_READY");
        var stale=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,waiting));
        var block=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,f.customer(),customerProfile("Account Example","ACC001",com.cbs.customers.CustomerModels.State.BLOCKED,"REVIEW/2"),""));approveHttp(block.id());
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,waiting)),"ACCOUNT_CUSTOMER_NOT_READY");customers.deliver(block.id());
        assertThat(approvals.decide(CHECKER,stale.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Review stale request").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,waiting)),"ACCOUNT_CUSTOMER_NOT_READY");
        var active=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,f.customer(),customerProfile("Account Example","ACC001",com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/3"),""));approveHttp(active.id());customers.deliver(active.id());
        var r=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,waiting));approveHttp(r.id());accounts.deliver(r.id());var a=accounts.list(ADMIN,bank.id(),"",waiting,25,0).items().getFirst();
        mvc.perform(admin(get(path+"/"+a.id()))).andExpect(status().isOk()).andExpect(jsonPath("$.definition.terms.currency").value("INR"));
        var another=register("account-isolation");assertCode(()->accounts.detail(ADMIN,another.id(),a.id()),"ACCOUNT_NOT_FOUND");assertCode(()->accounts.retry(ADMIN,another.id(),r.id()),"APPROVAL_NOT_FOUND");
        var wrong=new com.cbs.accounts.AccountModels.Change(service.get(ADMIN,bank.id()).version(),a.id(),f.customer(),f.product().id(),2,bank.legalEntities().getFirst().id(),"Invalid",waiting,"Wrong revision");
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),wrong),"ACCOUNT_STRUCTURE_IMMUTABLE");
    }
    @Test void depositAccountDeliveryRecoversWithoutDuplicatingAccount() throws Exception {
        var f=accountFixture("account-recovery");var bank=f.bank();var input=accountChange(f,null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING);UUID key=UUID.randomUUID();
        CyclicBarrier start=new CyclicBarrier(2);UUID request;
        try(var pool=Executors.newFixedThreadPool(2)){Callable<UUID> task=()->{start.await();return approvals.account(ADMIN,key,bank.id(),input).id();};var results=pool.invokeAll(List.of(task,task));request=results.getFirst().get();assertThat(request).isEqualTo(results.getLast().get());}
        UUID id=request;new TransactionTemplate(transactionManager).executeWithoutResult(t->{approvals.decide(CHECKER,id,com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback approval");t.setRollbackOnly();});
        assertThat(accounts.list(ADMIN,bank.id(),"",null,25,0).total()).isZero();approveHttp(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{accounts.deliver(id);t.setRollbackOnly();});
        var a=accounts.list(ADMIN,bank.id(),"",null,25,0).items().getFirst();assertThat(a.delivery()).isEqualTo("PENDING");
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.of(()->{accounts.retry(CHECKER,bank.id(),id);return true;},()->{accounts.retry(CHECKER,bank.id(),id);return true;}));for(var result:results)result.get();}
        assertThat(accounts.detail(ADMIN,bank.id(),a.id()).delivery()).isEqualTo("APPLIED");
        try(var c=TestDatabases.bank("accountrecovery");var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM bank.account_event")){rs.next();assertThat(rs.getInt(1)).isEqualTo(1);}
        var rejected=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approvals.decide(CHECKER,rejected.id(),com.cbs.approvals.ApprovalModels.Decision.REJECT,"Reject test");assertThat(accounts.list(ADMIN,bank.id(),"",null,25,0).total()).isEqualTo(1);
    }


    @Autowired com.cbs.fx.FxService fx;
    @Autowired com.cbs.credit.CreditService credit;
    @Autowired com.cbs.contracts.ContractService contracts;
    @Autowired com.cbs.contracts.BankContractStore bankContracts;
    @Test void termDepositAndLoanLifecyclesPostReconciledAccrualAndSettlement()throws Exception{
        var f=fundingFixture("contracts-main");var bank=f.f().bank();fund(f,"1000");var currency=f.f().product().currency();var date=reference.get(ADMIN,bank.id()).businessDate();var maturity=days.dashboard(ADMIN,bank.id(),25,0).nextBusinessDate();
        var termPrincipal=adjustmentLedger(bank,"TD_PRINCIPAL",com.cbs.accounting.ChartModels.Category.LIABILITY);var termInterest=adjustmentLedger(bank,"TD_INTEREST",com.cbs.accounting.ChartModels.Category.LIABILITY);var expense=adjustmentLedger(bank,"TD_EXPENSE",com.cbs.accounting.ChartModels.Category.EXPENSE);var tax=adjustmentLedger(bank,"TD_TAX",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var loanPrincipal=fxLedger(bank,"LOAN_PRINCIPAL",f.source().chartAccountId(),currency).id();var loanInterest=fxLedger(bank,"LOAN_INTEREST",f.source().chartAccountId(),currency).id();var income=adjustmentLedger(bank,"LOAN_INCOME",com.cbs.accounting.ChartModels.Category.INCOME);
        for(String op:List.of("TD_OPEN","TD_REDEEM","LOAN_DISBURSE","LOAN_REPAY"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),currency,op,"10000","10000","Fictional contract test limits")).id());
        var terms=new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,f.account(),termPrincipal,termInterest,expense,tax,"100","36.5","10",365,date,maturity,0,"TEST/TD","Fictional fixed term deposit");
        var badTax=new com.cbs.contracts.ContractModels.Open(terms.expectedVersion(),terms.type(),terms.accountId(),terms.principalLedger(),terms.accruedInterestLedger(),terms.interestProfitLossLedger(),f.liability().id(),terms.principal(),terms.annualRatePercent(),terms.withholdingPercent(),terms.dayBasis(),terms.startDate(),terms.maturityDate(),terms.instalments(),"TEST/BAD-TAX",terms.reason());
        assertCode(()->approvals.contractOpen(ADMIN,UUID.randomUUID(),bank.id(),badTax),"CONTRACT_LEDGER_USED");
        var key=UUID.randomUUID();var td=approvals.contractOpen(ADMIN,key,bank.id(),terms);assertThat(approvals.contractOpen(ADMIN,key,bank.id(),terms).id()).isEqualTo(td.id());assertCode(()->approvals.decide(ADMIN,td.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(td.id());
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).blockers()).contains("ACCOUNT_CLOSURE_CONTRACT_REVIEW");
        assertCode(()->journals.deliver(td.id()),"CONTRACT_MANAGED");assertThat(journals.pending()).doesNotContain(td.id());
        // A receipt failure must roll back the customer's debit and the contract principal journal.
        try(var c=TestDatabases.bank("contracts");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_contract_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected contract receipt failure'; END $$");st.execute("CREATE TRIGGER fail_contract_test BEFORE INSERT ON bank.contract_event FOR EACH ROW EXECUTE FUNCTION bank.fail_contract_test()");}
        contracts.deliver(td.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,td.id())).isFalse();assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1000");
        try(var c=TestDatabases.bank("contracts");var st=c.createStatement()){st.execute("DROP TRIGGER fail_contract_test ON bank.contract_event");st.execute("DROP FUNCTION bank.fail_contract_test()");}
        var posting=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.contract_delivery WHERE id=?",String.class,td.id()),com.cbs.contracts.ContractModels.Posting.class);
        bankContracts.post(service.get(ADMIN,bank.id()),new com.cbs.contracts.ContractModels.Dispatch(posting,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));contracts.deliver(td.id());contracts.deliver(td.id());
        assertThat(bankContracts.position(service.get(ADMIN,bank.id()),td.id()).principal()).isEqualTo("100");assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        assertCode(()->approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),td.id(),com.cbs.contracts.ContractModels.Kind.REDEEM,date,"100","Early redemption unsupported")),"CONTRACT_REDEMPTION_INVALID");
        var duplicate=new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),terms.type(),terms.accountId(),terms.principalLedger(),terms.accruedInterestLedger(),terms.interestProfitLossLedger(),terms.taxLedger(),terms.principal(),terms.annualRatePercent(),terms.withholdingPercent(),terms.dayBasis(),terms.startDate(),terms.maturityDate(),terms.instalments(),terms.agreementReference(),terms.reason());
        assertCode(()->approvals.contractOpen(ADMIN,UUID.randomUUID(),bank.id(),duplicate),"CONTRACT_REFERENCE_IN_USE");
        approveCreditFixture(f,"TEST/LOAN","200","36.5");
        var loanTerms=new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),com.cbs.contracts.ContractModels.Type.LOAN,f.account(),loanPrincipal,loanInterest,income,null,"200","36.5","0",365,date,date.plusMonths(2),2,"TEST/LOAN","Fictional approved credit agreement");
        var loan=approvals.contractOpen(ADMIN,UUID.randomUUID(),bank.id(),loanTerms);approveHttp(loan.id());contracts.deliver(loan.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),loan.id()).principal()).isEqualTo("200");assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1100");
        var day=days.dashboard(ADMIN,bank.id(),25,0);assertThat(day.blockers()).isEmpty();var close=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Advance fictional contract day"));approveHttp(close.id());days.deliver(close.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CONTRACT_ACCRUAL_REQUIRED");
        assertCode(()->approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.ContractModels.Kind.REPAY,maturity,"100","Accrual required")),"CONTRACT_ACCRUAL_REQUIRED");
        for(UUID contract:List.of(td.id(),loan.id())){var accrual=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),contract,com.cbs.contracts.ContractModels.Kind.ACCRUE,maturity,"0","Accrue actual elapsed days"));approveHttp(accrual.id());contracts.deliver(accrual.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,accrual.id())).isTrue();}
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CONTRACT_ACCRUAL_REQUIRED");
        var tdPosition=bankContracts.position(service.get(ADMIN,bank.id()),td.id());var tdTax=new java.math.BigDecimal(tdPosition.interest()).multiply(new java.math.BigDecimal("0.1")).setScale(2,java.math.RoundingMode.HALF_EVEN);
        var redeem=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),td.id(),com.cbs.contracts.ContractModels.Kind.REDEEM,maturity,"100","Pay matured term deposit"));approveHttp(redeem.id());contracts.deliver(redeem.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),td.id()).closed()).isTrue();
        var loanBefore=bankContracts.position(service.get(ADMIN,bank.id()),loan.id());var repayment=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.ContractModels.Kind.REPAY,maturity,"100","Partial repayment interest first"));approveHttp(repayment.id());contracts.deliver(repayment.id());var remaining=bankContracts.position(service.get(ADMIN,bank.id()),loan.id());assertThat(remaining.interest()).isEqualTo("0");assertThat(new java.math.BigDecimal(remaining.principal())).isEqualByComparingTo(new java.math.BigDecimal("100").add(new java.math.BigDecimal(loanBefore.interest())));
        var payoff=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.ContractModels.Kind.REPAY,maturity,remaining.principal(),"Pay remaining loan"));approveHttp(payoff.id());contracts.deliver(payoff.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),loan.id()).closed()).isTrue();
        var expected=new java.math.BigDecimal("1000").add(new java.math.BigDecimal(tdPosition.interest())).subtract(tdTax).subtract(new java.math.BigDecimal(loanBefore.interest()));assertThat(new java.math.BigDecimal(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance())).isEqualByComparingTo(expected);assertThat(new java.math.BigDecimal(statements.read(ADMIN,bank.id(),f.account(),date,maturity,100,0).closingBalance())).isEqualByComparingTo(expected);
        assertCode(()->approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.ContractModels.Kind.REPAY,maturity,"1","Duplicate payoff")),"CONTRACT_CLOSED");
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/contracts").param("type","LOAN").header("Authorization","Bearer "+TestIdentity.token("administrator",true))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("CLOSED"));
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/contracts").param("type","LOAN").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
    }
    @Autowired com.cbs.fx.FxTradeService fxTrades;
    @Autowired com.cbs.fx.BankFxStore bankFx;
    @Autowired com.cbs.contracts.LoanServicingService loanServicing;
    @Test void creditApprovalConditionsRestructureImpairmentAndWriteoffAreControlled()throws Exception {
        var f=fundingFixture("credit-main");lendingFixture(f);var bank=f.f().bank();fund(f,"1000");var date=reference.get(ADMIN,bank.id()).businessDate();String currency=f.f().product().currency();
        var principal=fxLedger(bank,"CR_P",f.source().chartAccountId(),currency).id();var interest=fxLedger(bank,"CR_I",f.source().chartAccountId(),currency).id();var income=adjustmentLedger(bank,"CR_INCOME",com.cbs.accounting.ChartModels.Category.INCOME);
        var allowance=adjustmentLedger(bank,"CR_ALLOWANCE",com.cbs.accounting.ChartModels.Category.ASSET);var expense=adjustmentLedger(bank,"CR_EXPENSE",com.cbs.accounting.ChartModels.Category.EXPENSE);
        for(String op:List.of("LOAN_DISBURSE","LOAN_REPAY","LOAN_REPAY_REVERSAL","LOAN_RESTRUCTURE","LOAN_IMPAIR","LOAN_WRITE_OFF"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),currency,op,"10000","20000","Test approved loan limits")).id());
        java.util.function.Supplier<com.cbs.contracts.ContractModels.Open> opening=()->new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),com.cbs.contracts.ContractModels.Type.LOAN,f.account(),principal,interest,income,null,"200","0","0",365,date,date.plusMonths(2),2,"CR/APP","Verified agreement");
        assertCode(()->contracts.previewOpen(ADMIN,bank.id(),opening.get()),"CREDIT_APPROVAL_REQUIRED");
        approveHttp(approvals.creditPolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.credit.CreditModels.Policy(service.get(ADMIN,bank.id()).version(),currency,"10000","50","100","0",5,365,CHECKER.subject(),"CR/POLICY","Approved test policy")).id());
        var doc=new com.cbs.credit.CreditModels.Document("CR/DOC","a".repeat(64),date.plusYears(1));
        var security=new com.cbs.credit.CreditModels.Security(com.cbs.credit.CreditModels.SecurityType.COLLATERAL,"CR/SECURITY","Fictional borrower","300",date,doc);
        var application=new com.cbs.credit.CreditModels.Application(service.get(ADMIN,bank.id()).version(),f.account(),"CR/APP","Business equipment","200","0",365,2,"1000","100","50","100",List.of(doc),List.of(security),"Verified financial evidence");var key=UUID.randomUUID();var created=credit.create(ADMIN,bank.id(),key,application);var app=(UUID)created.get("id");assertThat(credit.create(ADMIN,bank.id(),key,application).get("id")).isEqualTo(app);
        var review=new com.cbs.credit.CreditModels.Review(service.get(ADMIN,bank.id()).version(),app,com.cbs.credit.CreditModels.Decision.APPROVE,2,date.plusMonths(1),doc,List.of("Verify signed agreement"),"Verified affordability");var expiredReview=new com.cbs.credit.CreditModels.Review(service.get(ADMIN,bank.id()).version(),app,com.cbs.credit.CreditModels.Decision.APPROVE,2,date.minusDays(1),doc,List.of(),"Expired review");assertCode(()->approvals.creditReview(ADMIN,UUID.randomUUID(),bank.id(),expiredReview),"CREDIT_APPROVAL_EXPIRED");
        var request=approvals.creditReview(ADMIN,UUID.randomUUID(),bank.id(),review);assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("credit-outsider","Other administrator","credit-outsider@example.test","Test reviewer restriction"));var outsider=new Actor(ADMIN.issuer(),"credit-outsider",true,UUID.randomUUID());assertCode(()->approvals.decide(outsider,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Unauthorized credit reviewer"),"LENDING_ACCESS_DENIED");approveHttp(request.id());
        assertThat(credit.detail(ADMIN,bank.id(),app).get("status")).isEqualTo("APPROVED");assertCode(()->contracts.previewOpen(ADMIN,bank.id(),opening.get()),"CREDIT_CONDITIONS_REQUIRED");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.credit_application SET status='READY' WHERE id=?",app)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertCode(()->approvals.creditRelease(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.credit.CreditModels.Release(service.get(ADMIN,bank.id()).version(),app,List.of(),"Missing condition")),"CREDIT_CONDITIONS_REQUIRED");
        approveHttp(approvals.creditRelease(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.credit.CreditModels.Release(service.get(ADMIN,bank.id()).version(),app,List.of(doc),"Verified signed agreement")).id());
        var mismatch=new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),com.cbs.contracts.ContractModels.Type.LOAN,f.account(),principal,interest,income,null,"201","0","0",365,date,date.plusMonths(2),2,"CR/APP","Mismatched amount");assertCode(()->contracts.previewOpen(ADMIN,bank.id(),mismatch),"CREDIT_TERMS_MISMATCH");
        var loan=approvals.contractOpen(ADMIN,UUID.randomUUID(),bank.id(),opening.get());approveHttp(loan.id());contracts.deliver(loan.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1200");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.credit_reservation WHERE application_id=?",Integer.class,app)).isEqualTo(1);
        var collection=new com.cbs.contracts.LoanServicingModels.Collection(service.get(ADMIN,bank.id()).version(),loan.id(),ADMIN.subject(),date.plusDays(7),"CR/CALL","Borrower contacted; follow-up agreed");var collectionKey=UUID.randomUUID();loanServicing.collection(ADMIN,bank.id(),collectionKey,collection);loanServicing.collection(ADMIN,bank.id(),collectionKey,collection);assertThat((List<?>)loanServicing.collections(ADMIN,bank.id(),loan.id()).get("items")).hasSize(1);
        var paid=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.ContractModels.Kind.REPAY,date,"10","Fictional end-to-end repayment"));approveHttp(paid.id());contracts.deliver(paid.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1190");correctContract(bank.id(),loan.id(),paid.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1200");
        approveCreditFixture(f,"CR/RESTRUCTURE","200","7.5");
        var restructure=new com.cbs.contracts.LoanServicingModels.Instruction(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.LoanServicingModels.Operation.RESTRUCTURE,date,"0",1,null,null,"7.5",2,"CR/RESTRUCTURE","Approved new affordability assessment");var rr=approvals.loanServicing(ADMIN,UUID.randomUUID(),bank.id(),restructure);approveHttp(rr.id());contracts.deliver(rr.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.contract_delivery WHERE id=?",String.class,rr.id())).isNull();assertThat(contracts.definition(bank.id(),loan.id()).terms().annualRatePercent()).isEqualTo("7.5");assertThat(jdbc.queryForObject("SELECT payload->'terms'->>'annualRatePercent' FROM platform.contract WHERE id=?",String.class,loan.id())).isEqualTo("0");
        var impairment=new com.cbs.contracts.LoanServicingModels.Instruction(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.LoanServicingModels.Operation.IMPAIR,date,"200",3,allowance,expense,null,0,"CR/ECL","Approved full loss assessment");var ir=approvals.loanServicing(ADMIN,UUID.randomUUID(),bank.id(),impairment);approveHttp(ir.id());
        var posting=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.contract_delivery WHERE id=?",String.class,ir.id()),com.cbs.contracts.ContractModels.Posting.class);bankContracts.post(service.get(ADMIN,bank.id()),new com.cbs.contracts.ContractModels.Dispatch(posting,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));contracts.deliver(ir.id());contracts.deliver(ir.id());
        assertThat(com.cbs.contracts.LoanServicingService.allowance(jdbc,loan.id())).isEqualByComparingTo("200");assertCode(()->contracts.previewEvent(ADMIN,bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.ContractModels.Kind.REPAY,date,"1","Payment")),"LOAN_ALLOWANCE_RELEASE_REQUIRED");
        var writeoff=new com.cbs.contracts.LoanServicingModels.Instruction(service.get(ADMIN,bank.id()).version(),loan.id(),com.cbs.contracts.LoanServicingModels.Operation.WRITE_OFF,date,"0",3,allowance,expense,null,0,"CR/WO","Approved exhausted recovery options");var wr=approvals.loanServicing(ADMIN,UUID.randomUUID(),bank.id(),writeoff);approveHttp(wr.id());
        try(var c=TestDatabases.bank("credit");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_writeoff_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.kind='WRITE_OFF' THEN RAISE EXCEPTION 'Injected writeoff receipt failure'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER fail_writeoff_test BEFORE INSERT ON bank.contract_event FOR EACH ROW EXECUTE FUNCTION bank.fail_writeoff_test()");}
        contracts.deliver(wr.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),loan.id()).principal()).isEqualTo("200");assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,wr.id())).isFalse();
        try(var c=TestDatabases.bank("credit");var st=c.createStatement()){st.execute("DROP TRIGGER fail_writeoff_test ON bank.contract_event");st.execute("DROP FUNCTION bank.fail_writeoff_test()");}
        contracts.deliver(wr.id());contracts.deliver(wr.id());assertThat(jdbc.queryForObject("SELECT status FROM platform.contract WHERE id=?",String.class,loan.id())).isEqualTo("WRITTEN_OFF");assertThat(bankContracts.position(service.get(ADMIN,bank.id()),loan.id()).principal()).isEqualTo("0");assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1200");assertThat(com.cbs.contracts.LoanServicingService.allowance(jdbc,loan.id())).isEqualByComparingTo("0");
        var secondApplication=new com.cbs.credit.CreditModels.Application(service.get(ADMIN,bank.id()).version(),f.account(),"CR/SECOND","Another equipment purchase","200","0",365,2,"1000","100","50","100",List.of(doc),List.of(security),"Reuse must remain blocked after accounting write-off");var secondId=(UUID)credit.create(ADMIN,bank.id(),UUID.randomUUID(),secondApplication).get("id");approveHttp(approvals.creditReview(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.credit.CreditModels.Review(service.get(ADMIN,bank.id()).version(),secondId,com.cbs.credit.CreditModels.Decision.APPROVE,2,date.plusMonths(1),doc,List.of(),"Verified assessment")).id());
        var secondOpening=new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),com.cbs.contracts.ContractModels.Type.LOAN,f.account(),principal,interest,income,null,"200","0","0",365,date,date.plusMonths(2),2,"CR/SECOND","Duplicate collateral");assertCode(()->contracts.previewOpen(ADMIN,bank.id(),secondOpening),"CREDIT_SECURITY_ALREADY_PLEDGED");
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/credit").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        assertThatThrownBy(()->jdbc.update("UPDATE platform.credit_event SET kind='TAMPERED' WHERE application_id=?",app)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(((List<?>)credit.detail(ADMIN,bank.id(),app).get("events")).size()).isGreaterThanOrEqualTo(3);
        var closing=days.dashboard(ADMIN,bank.id(),25,0);assertThat(closing.blockers()).isEmpty();var closeRequest=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(closing.version(),closing.businessDate(),closing.nextBusinessDate(),"Validated complete fictional lending lifecycle"));approveHttp(closeRequest.id());days.deliver(closeRequest.id());assertThat(reference.get(ADMIN,bank.id()).businessDate()).isEqualTo(closing.nextBusinessDate());
    }
    @Autowired com.cbs.contracts.LoanCollectionsService collections;
    @Autowired com.cbs.contracts.LoanFeeService loanFees;
    @Test void collectionsFeesPromisesAndImmutableHistoryReconcile()throws Exception {
        var f=fundingFixture("arrears-main");var bank=f.f().bank();fund(f,"1000");var date=reference.get(ADMIN,bank.id()).businessDate();var currency=f.f().product().currency();
        for(String op:List.of("LOAN_DISBURSE","LOAN_REPAY","LOAN_REPAY_REVERSAL","LOAN_FEE_ASSESS","LOAN_FEE_PAY","LOAN_FEE_WAIVE"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),currency,op,"10000","20000","Fictional collection limits")).id());
        var loan=testContract(f,com.cbs.contracts.ContractModels.Type.LOAN,"COL_LOAN","200","0");var receivable=fxLedger(bank,"COL_FEE",f.source().chartAccountId(),currency).id();var income=adjustmentLedger(bank,"COL_INCOME",com.cbs.accounting.ChartModels.Category.INCOME);
        var assign=new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.ASSIGN,ADMIN.subject(),date,null,null,null,"COL/ASSIGN","Assign recovery owner");var key=UUID.randomUUID();collections.change(ADMIN,bank.id(),key,assign);collections.change(ADMIN,bank.id(),key,assign);assertThat(collections.detail(ADMIN,bank.id(),loan,25,0).get("historyTotal")).isEqualTo(1L);
        assertCode(()->collections.change(ADMIN,bank.id(),UUID.randomUUID(),assign),"STALE_VERSION");
        var assessment=new com.cbs.contracts.LoanFeeModels.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanFeeModels.Operation.FEE_ASSESS,null,date,date,receivable,income,"10","COL/FEE","Fictional agreed fee");var assessed=approvals.loanFee(ADMIN,UUID.randomUUID(),bank.id(),assessment);assertCode(()->approvals.decide(ADMIN,assessed.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(assessed.id());contracts.deliver(assessed.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,assessed.id())).isTrue();assertThat(com.cbs.contracts.LoanFeeService.outstanding(jdbc,loan)).isEqualByComparingTo("10");
        collections.change(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.PROMISE,null,null,"5",date,null,"COL/PROMISE","Borrower promises cash"));
        var payment=approvals.loanFee(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.LoanFeeModels.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanFeeModels.Operation.FEE_PAY,assessed.id(),date,null,null,null,"5","COL/PAY","Pay agreed fee"));approveHttp(payment.id());
        try(var c=TestDatabases.bank("arrears");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_fee_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected fee receipt failure'; END $$");st.execute("CREATE TRIGGER fail_fee_test BEFORE INSERT ON bank.loan_fee_event FOR EACH ROW EXECUTE FUNCTION bank.fail_fee_test()");}
        contracts.deliver(payment.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,payment.id())).isFalse();assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1200");
        try(var c=TestDatabases.bank("arrears");var st=c.createStatement()){st.execute("DROP TRIGGER fail_fee_test ON bank.loan_fee_event");st.execute("DROP FUNCTION bank.fail_fee_test()");}
        var posting=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.contract_delivery WHERE id=?",String.class,payment.id()),com.cbs.contracts.ContractModels.Posting.class);bankContracts.post(service.get(ADMIN,bank.id()),new com.cbs.contracts.ContractModels.Dispatch(posting,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));contracts.deliver(payment.id());contracts.deliver(payment.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1195");
        assertThat(((com.cbs.contracts.LoanCollectionsService.PromiseStatus)collections.detail(ADMIN,bank.id(),loan,25,0).get("promiseStatus")).status()).isEqualTo("FULFILLED");
        var waiver=approvals.loanFee(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.LoanFeeModels.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanFeeModels.Operation.FEE_WAIVE,assessed.id(),date,null,null,null,"5","COL/WAIVE","Approved fee concession"));approveHttp(waiver.id());contracts.deliver(waiver.id());assertThat(com.cbs.contracts.LoanFeeService.outstanding(jdbc,loan)).isEqualByComparingTo("0");
        collections.change(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.PROMISE,null,null,"10",date,null,"COL/PROMISE2","New cash promise"));
        var repayment=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.ContractModels.Kind.REPAY,date,"10","Cash repayment"));approveHttp(repayment.id());contracts.deliver(repayment.id());assertThat(((com.cbs.contracts.LoanCollectionsService.PromiseStatus)collections.detail(ADMIN,bank.id(),loan,25,0).get("promiseStatus")).status()).isEqualTo("FULFILLED");correctContract(bank.id(),loan,repayment.id());assertThat(((com.cbs.contracts.LoanCollectionsService.PromiseStatus)collections.detail(ADMIN,bank.id(),loan,25,0).get("promiseStatus")).status()).isEqualTo("OPEN");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.loan_collection_event SET kind='CONTACT' WHERE contract_id=?",loan)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertCode(()->collections.detail(ADMIN,UUID.randomUUID(),loan,25,0),"TENANT_NOT_FOUND");
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/loan-collections").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        try(var c=TestDatabases.bank("arrears");var st=c.createStatement()){var rs=st.executeQuery("SELECT debit-credit FROM bank.ledger_balance WHERE ledger_id='"+receivable+"'");assertThat(rs.next()).isTrue();assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("0");assertThatThrownBy(()->st.execute("DELETE FROM bank.loan_fee_event")).isInstanceOf(java.sql.SQLException.class);}
        var detail=collections.detail(ADMIN,bank.id(),loan,25,0);assertThat(((com.cbs.contracts.LoanArrears.Report)detail.get("report")).reconciled()).isTrue();
        collections.change(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.ESCALATE,null,date.plusDays(1),null,null,com.cbs.contracts.LoanCollectionsService.Escalation.CREDIT_MANAGER,"COL/ESCALATE","Supervisor review needed"));
        assertCode(()->collections.change(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.RESOLVE,null,null,null,null,null,"COL/RESOLVE","Promise still open")),"COLLECTION_RESOLUTION_BLOCKED");
        var secondFee=approvals.loanFee(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.LoanFeeModels.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanFeeModels.Operation.FEE_ASSESS,null,date,date,receivable,income,"3","COL/FEE2","Second agreed charge"));approveHttp(secondFee.id());contracts.deliver(secondFee.id());
        var payoff=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.ContractModels.Kind.REPAY,date,"200","Pay remaining principal"));approveHttp(payoff.id());contracts.deliver(payoff.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),loan).closed()).isFalse();
        var lastFee=approvals.loanFee(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.LoanFeeModels.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanFeeModels.Operation.FEE_PAY,secondFee.id(),date,null,null,null,"3","COL/PAY2","Pay final fee"));approveHttp(lastFee.id());contracts.deliver(lastFee.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),loan).closed()).isTrue();assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("992");
        collections.change(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.RESOLVE,null,null,null,null,null,"COL/RESOLVED","All obligations settled"));
        assertThat(((com.cbs.contracts.LoanCollectionsService.CaseState)collections.detail(ADMIN,bank.id(),loan,25,0).get("case")).status()).isEqualTo("RESOLVED");
        var close=days.dashboard(ADMIN,bank.id(),25,0);assertThat(close.blockers()).isEmpty();var closed=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(close.version(),close.businessDate(),close.nextBusinessDate(),"Verified fees and collections closure"));approveHttp(closed.id());days.deliver(closed.id());assertThat(reference.get(ADMIN,bank.id()).businessDate()).isEqualTo(close.nextBusinessDate());

    }
    @Autowired com.cbs.access.LendingAccess lendingAccess;
    private Actor lendingStaff(String subject){access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput(subject,subject,subject+"@example.test","Fictional access boundary test"));return new Actor(ADMIN.issuer(),subject,false,UUID.randomUUID());}
    private void grantLending(UUID bank,Actor staff,boolean active,List<UUID> branches,List<com.cbs.access.LendingAccess.Role> roles,List<com.cbs.access.LendingAccess.Limit> limits){
        var g=new com.cbs.access.LendingAccess.Grant(service.get(ADMIN,bank).version(),access.requireAssignable(staff.issuer(),staff.subject()).id(),active,branches.isEmpty(),branches,roles,limits,"Fictional bank-approved authority");
        var request=approvals.lendingGrant(ADMIN,UUID.randomUUID(),bank,g);approvals.decide(CHECKER,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent authority review");
    }
    @Test void lendingRolesBranchesLimitsAndRevocationsAreEnforcedEndToEnd()throws Exception {
        var f=fundingFixture("lending-access");var bank=f.f().bank();var bid=bank.id();var currency=f.f().product().currency();fund(f,"1000");
        assertCode(()->contracts.list(ADMIN,bid,com.cbs.contracts.ContractModels.Type.LOAN,25,0),"LENDING_ACCESS_DENIED");
        for(String op:List.of("LOAN_DISBURSE","LOAN_REPAY"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bid,new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bid).version(),currency,op,"10000","20000","Fictional global loan limits")).id());
        var loan=testContract(f,com.cbs.contracts.ContractModels.Type.LOAN,"ACCESS_LOAN","200","0");var day=reference.get(ADMIN,bid).businessDate();
        var main=branchService.create(ADMIN,bid,new com.cbs.access.AccessModels.BranchInput(bank.legalEntities().getFirst().id(),"ACCESS_A","Lending branch A","Test scope"));
        var other=branchService.create(ADMIN,bid,new com.cbs.access.AccessModels.BranchInput(bank.legalEntities().getFirst().id(),"ACCESS_B","Lending branch B","Test scope"));
        approveHttp(approvals.assignment(ADMIN,UUID.randomUUID(),bid,new com.cbs.access.BranchOperations.Assignment(service.get(ADMIN,bid).version(),f.account(),main.id(),"Assign loan account")).id());
        Actor finance=lendingStaff("lending-finance"),checker=lendingStaff("lending-checker"),auditor=lendingStaff("lending-auditor"),wrong=lendingStaff("lending-wrong-branch"),collector=lendingStaff("lending-collector");
        var financeRoles=List.of(com.cbs.access.LendingAccess.Role.FINANCE_OFFICER);var checkerRoles=List.of(com.cbs.access.LendingAccess.Role.CREDIT_APPROVER,com.cbs.access.LendingAccess.Role.FINANCE_OFFICER);
        var cap=List.of(new com.cbs.access.LendingAccess.Limit(com.cbs.access.LendingAccess.Operation.LOAN_REPAY,currency,"60","100"));
        grantLending(bid,finance,true,List.of(main.id()),financeRoles,List.of());grantLending(bid,checker,true,List.of(main.id()),checkerRoles,cap);
        grantLending(bid,auditor,true,List.of(main.id()),List.of(com.cbs.access.LendingAccess.Role.AUDITOR),List.of());grantLending(bid,wrong,true,List.of(other.id()),List.of(com.cbs.access.LendingAccess.Role.AUDITOR),List.of());grantLending(bid,collector,true,List.of(main.id()),List.of(com.cbs.access.LendingAccess.Role.COLLECTIONS_OFFICER),List.of());
        String base="/api/v1/banks/"+bid+"/lending-operations";
        mvc.perform(get(base+"/contracts?type=LOAN").header("Authorization","Bearer "+TestIdentity.token(auditor.subject(),false))).andExpect(status().isOk());
        mvc.perform(get(base+"/contracts/"+loan+"/history").header("Authorization","Bearer "+TestIdentity.token(wrong.subject(),false))).andExpect(status().isForbidden());
        mvc.perform(get(base+"/credit").header("Authorization","Bearer "+TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,auditor.subject(),false,java.time.Instant.now().plusSeconds(300),java.time.Instant.now(),java.time.Instant.now(),UUID.randomUUID().toString(),false))).andExpect(status().isForbidden());
        assertThat(contracts.list(wrong,bid,com.cbs.contracts.ContractModels.Type.LOAN,25,0).get("total")).isEqualTo(0L);
        var otherBank=register("lending-other");assertCode(()->lendingAccess.read(auditor,otherBank.id(),f.account()),"LENDING_ACCESS_DENIED");mvc.perform(get("/api/v1/banks/"+otherBank.id()+"/lending-operations").header("Authorization","Bearer "+TestIdentity.token(auditor.subject(),false))).andExpect(status().isForbidden());assertThat(lendingAccess.banks(auditor).stream().map(row->row.get("id"))).contains(bid).doesNotContain(otherBank.id());
        assertThat(approvals.lendingInbox(wrong,bid,25,0).items()).isEmpty();assertCode(()->lendingAccess.read(auditor,UUID.randomUUID(),f.account()),"LENDING_ACCESS_DENIED");
        assertCode(()->contracts.prepare(auditor,bid),"ACCESS_DENIED");
        java.util.function.Function<String,com.cbs.contracts.ContractModels.Event> payment=amount->new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bid).version(),loan,com.cbs.contracts.ContractModels.Kind.REPAY,day,amount,"Fictional scoped repayment");
        assertCode(()->approvals.contractEvent(auditor,UUID.randomUUID(),bid,payment.apply("1")),"LENDING_ROLE_REQUIRED");
        assertCode(()->lendingAccess.maker(finance,bid,new com.cbs.access.LendingAccess.Scope(f.account(),com.cbs.access.LendingAccess.Operation.LOAN_RESTRUCTURE,currency,"200",day)),"LENDING_ROLE_REQUIRED");
        assertCode(()->lendingAccess.checker(checker,bid,new com.cbs.access.LendingAccess.Scope(f.account(),com.cbs.access.LendingAccess.Operation.LOAN_FEE_WAIVE,currency,"1",day),UUID.randomUUID(),false),"LENDING_APPROVAL_LIMIT_REQUIRED");
        assertCode(()->lendingAccess.checker(checker,bid,new com.cbs.access.LendingAccess.Scope(f.account(),com.cbs.access.LendingAccess.Operation.LOAN_REPAY,"USD","1",day),UUID.randomUUID(),false),"LENDING_APPROVAL_LIMIT_REQUIRED");
        var self=approvals.contractEvent(checker,UUID.randomUUID(),bid,payment.apply("1"));assertCode(()->approvals.decide(checker,self.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approvals.decide(checker,self.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Cancel test");
        var excessive=approvals.contractEvent(finance,UUID.randomUUID(),bid,payment.apply("61"));assertCode(()->approvals.decide(checker,excessive.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Too much"),"LENDING_APPROVAL_LIMIT_EXCEEDED");approvals.decide(finance,excessive.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Cancel test");
        var first=approvals.contractEvent(finance,UUID.randomUUID(),bid,payment.apply("60"));
        mvc.perform(post(base+"/approvals/"+first.id()+"/APPROVE").header("Authorization","Bearer "+TestIdentity.token(checker.subject(),false)).contentType("application/json").content("{\"reason\":\"Independent scoped review\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT amount FROM platform.lending_approval_usage WHERE request_id=?",java.math.BigDecimal.class,first.id())).isEqualByComparingTo("60");
        grantLending(bid,finance,false,List.of(main.id()),financeRoles,List.of());assertCode(()->contracts.history(finance,bid,loan,25,0),"LENDING_ACCESS_DENIED");contracts.deliver(first.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.contract_delivery WHERE id=?",String.class,first.id())).isEqualTo("LENDING_ACCESS_DENIED");assertThat(bankContracts.position(service.get(ADMIN,bid),loan).principal()).isEqualTo("200");
        grantLending(bid,finance,true,List.of(main.id()),financeRoles,List.of());contracts.retry(finance,bid,first.id());assertThat(bankContracts.position(service.get(ADMIN,bid),loan).principal()).isEqualTo("140");
        var second=approvals.contractEvent(finance,UUID.randomUUID(),bid,payment.apply("40"));approvals.decide(checker,second.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Daily boundary");
        var p=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.contract_delivery WHERE id=?",String.class,second.id()),com.cbs.contracts.ContractModels.Posting.class);bankContracts.post(service.get(ADMIN,bid),new com.cbs.contracts.ContractModels.Dispatch(p,finance.subject(),checker.issuer(),checker.subject(),checker.correlationId()));
        grantLending(bid,checker,false,List.of(main.id()),checkerRoles,cap);contracts.deliver(second.id());contracts.deliver(second.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,second.id())).isTrue();assertThat(bankContracts.position(service.get(ADMIN,bid),loan).principal()).isEqualTo("100");
        grantLending(bid,checker,true,List.of(main.id()),checkerRoles,cap);var overDaily=approvals.contractEvent(finance,UUID.randomUUID(),bid,payment.apply("1"));assertCode(()->approvals.decide(checker,overDaily.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Over daily total"),"LENDING_DAILY_APPROVAL_LIMIT_EXCEEDED");
        var change=new com.cbs.contracts.LoanCollectionsService.Change(service.get(ADMIN,bid).version(),loan,com.cbs.contracts.LoanCollectionsService.Action.ASSIGN,collector.subject(),day,null,null,null,"ACCESS/CASE","Assign scoped collector");collections.change(collector,bid,UUID.randomUUID(),change);assertCode(()->collections.change(auditor,bid,UUID.randomUUID(),change),"LENDING_ROLE_REQUIRED");
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.lending_access_event WHERE tenant_id=?",bid)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM platform.lending_approval_usage WHERE request_id=?",first.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(((Number)lendingAccess.report(ADMIN,bid,25,0).get("historyTotal")).longValue()).isGreaterThan(5L);
        var beneficiary=access.requireAssignable(CHECKER.issuer(),CHECKER.subject());var selfGrant=approvals.lendingGrant(ADMIN,UUID.randomUUID(),bid,new com.cbs.access.LendingAccess.Grant(service.get(ADMIN,bid).version(),beneficiary.id(),true,true,List.of(),List.of(com.cbs.access.LendingAccess.Role.AUDITOR),List.of(),"Self grant test"));assertCode(()->approvals.decide(CHECKER,selfGrant.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Beneficiary review"),"LENDING_SELF_GRANT_APPROVAL");approvals.decide(ADMIN,selfGrant.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Cancel rejected grant");
        String migration=new String(getClass().getResourceAsStream("/db/migration/V37__lending_access_and_approval_limits.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);String legacy=migration.substring(migration.indexOf("WITH legacy AS"),migration.indexOf("-- Credit decisions"));legacy=legacy.replace("INSERT INTO platform.lending_approval_scope(request_id,tenant_id,account_id,payload)","");var migrated=jdbc.queryForList(legacy).stream().filter(row->first.id().equals(row.get("id"))).findFirst().orElseThrow();var migratedScope=json.readValue(migrated.get("jsonb_build_object").toString(),com.cbs.access.LendingAccess.Scope.class);assertThat(migratedScope).isEqualTo(lendingAccess.request(first.id()));
        var current=branchService.list(ADMIN,bid,100,0).items().stream().filter(b->b.id().equals(main.id())).findFirst().orElseThrow();branchService.update(ADMIN,bid,main.id(),new com.cbs.access.AccessModels.BranchUpdate(current.version(),"Closed test branch",false,"Scope revocation"));assertCode(()->contracts.history(auditor,bid,loan,25,0),"LENDING_BRANCH_DENIED");
    }

    private void lendingFixture(FundingFixture f)throws Exception {
        var bank=f.f().bank();if(jdbc.queryForObject("SELECT count(*) FROM platform.lending_access WHERE tenant_id=?",Integer.class,bank.id())>0)return;
        var limits=java.util.Arrays.stream(com.cbs.access.LendingAccess.Operation.values()).map(op->new com.cbs.access.LendingAccess.Limit(op,f.f().product().currency(),"1000000","10000000")).toList();
        for(var subject:List.of(ADMIN,CHECKER)){var staff=access.requireAssignable(subject.issuer(),subject.subject());var input=new com.cbs.access.LendingAccess.Grant(service.get(ADMIN,bank.id()).version(),staff.id(),true,true,List.of(),List.of(com.cbs.access.LendingAccess.Role.values()),limits,"Explicit fictional test authority");var request=approvals.lendingGrant(subject,UUID.randomUUID(),bank.id(),input);approvals.decide(subject==ADMIN?CHECKER:ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent grant review");}
    }
    private UUID approveCreditFixture(FundingFixture f,String ref,String amount,String rate)throws Exception {
        lendingFixture(f);
        var bank=f.f().bank();var date=reference.get(ADMIN,bank.id()).businessDate();
        if(jdbc.queryForObject("SELECT count(*) FROM platform.credit_policy WHERE tenant_id=?",Integer.class,bank.id())==0)approveHttp(approvals.creditPolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.credit.CreditModels.Policy(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),"10000","80","0","0",5,365,CHECKER.subject(),"TEST/POLICY","Fictional credit policy")).id());
        var doc=new com.cbs.credit.CreditModels.Document("TEST/DOC", "a".repeat(64),date.plusYears(1));
        var input=new com.cbs.credit.CreditModels.Application(service.get(ADMIN,bank.id()).version(),f.account(),ref,"Fictional test business equipment",amount,rate,365,2,"10000","100","0","0",List.of(doc),List.of(),"Fictional application");
        var result=credit.create(ADMIN,bank.id(),UUID.randomUUID(),input);var id=(UUID)result.get("id");
        var review=new com.cbs.credit.CreditModels.Review(service.get(ADMIN,bank.id()).version(),id,com.cbs.credit.CreditModels.Decision.APPROVE,2,date.plusMonths(1),doc,List.of(),"Verified test affordability evidence");
        approveHttp(approvals.creditReview(ADMIN,UUID.randomUUID(),bank.id(),review).id());return id;
    }
    private UUID testContract(FundingFixture f,com.cbs.contracts.ContractModels.Type type,String code,String amount,String annualRate)throws Exception {
        var bank=f.f().bank();boolean td=type==com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT;
        UUID principal=td?adjustmentLedger(bank,code+"_P",com.cbs.accounting.ChartModels.Category.LIABILITY):fxLedger(bank,code+"_P",f.source().chartAccountId(),f.f().product().currency()).id();
        UUID interest=td?adjustmentLedger(bank,code+"_I",com.cbs.accounting.ChartModels.Category.LIABILITY):fxLedger(bank,code+"_I",f.source().chartAccountId(),f.f().product().currency()).id();
        UUID pnl=adjustmentLedger(bank,code+"_PL",td?com.cbs.accounting.ChartModels.Category.EXPENSE:com.cbs.accounting.ChartModels.Category.INCOME);
        var date=reference.get(ADMIN,bank.id()).businessDate();
        boolean taxed=code.startsWith("REN_")||code.startsWith("SVC_");UUID tax=taxed?adjustmentLedger(bank,code+"_TAX",com.cbs.accounting.ChartModels.Category.LIABILITY):null;
        if(!td)approveCreditFixture(f,code,amount,annualRate);
        var terms=new com.cbs.contracts.ContractModels.Open(service.get(ADMIN,bank.id()).version(),type,f.account(),principal,interest,pnl,tax,amount,annualRate,taxed?"10":"0",365,date,code.startsWith("REN_")?days.dashboard(ADMIN,bank.id(),25,0).nextBusinessDate():date.plusMonths(2),td?0:2,code,"Fictional approved contract");
        var request=approvals.contractOpen(ADMIN,UUID.randomUUID(),bank.id(),terms);approveHttp(request.id());contracts.deliver(request.id());return request.id();
    }
    private com.cbs.contracts.ContractModels.Correction correction(UUID bank,UUID contract,UUID original) {
        return new com.cbs.contracts.ContractModels.Correction(service.get(ADMIN,bank).version(),contract,original,reference.get(ADMIN,bank).businessDate(),"Verified original event entered incorrectly");
    }
    private UUID correctContract(UUID bank,UUID contract,UUID original)throws Exception {
        var r=approvals.contractCorrection(ADMIN,UUID.randomUUID(),bank,correction(bank,contract,original));approveHttp(r.id());contracts.deliver(r.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,r.id())).isTrue();return r.id();
    }
    @Test void contractCorrectionsRestoreCashPositionsAndAccrualCarryWithoutEditingHistory()throws Exception {
        var f=fundingFixture("contracts-corrections");var bank=f.f().bank();fund(f,"1000");
        for(String op:List.of("TD_OPEN","TD_REDEEM","LOAN_DISBURSE","LOAN_REPAY","TD_OPEN_REVERSAL","LOAN_DISBURSE_REVERSAL","LOAN_REPAY_REVERSAL"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),op,"10000","20000","Fictional correction policy")).id());
        var td=testContract(f,com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,"COR_TD","100","7.12345678");
        var input=correction(bank.id(),td,td);var key=UUID.randomUUID();var r=approvals.contractCorrection(ADMIN,key,bank.id(),input);
        assertThat(approvals.contractCorrection(ADMIN,key,bank.id(),input).id()).isEqualTo(r.id());
        assertCode(()->approvals.decide(ADMIN,r.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(r.id());
        assertThat(journals.pending()).doesNotContain(r.id());assertCode(()->journals.deliver(r.id()),"CONTRACT_MANAGED");
        try(var c=TestDatabases.bank("corrections");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_correction_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected link failure'; END $$");st.execute("CREATE TRIGGER fail_correction_test BEFORE INSERT ON bank.contract_reversal FOR EACH ROW EXECUTE FUNCTION bank.fail_correction_test()");}
        contracts.deliver(r.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,r.id())).isFalse();assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        try(var c=TestDatabases.bank("corrections");var st=c.createStatement()){st.execute("DROP TRIGGER fail_correction_test ON bank.contract_reversal");st.execute("DROP FUNCTION bank.fail_correction_test()");}
        var posting=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.contract_delivery WHERE id=?",String.class,r.id()),com.cbs.contracts.ContractModels.Posting.class);
        bankContracts.post(service.get(ADMIN,bank.id()),new com.cbs.contracts.ContractModels.Dispatch(posting,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));contracts.deliver(r.id());contracts.deliver(r.id());
        assertThat(jdbc.queryForObject("SELECT status FROM platform.contract WHERE id=?",String.class,td)).isEqualTo("CANCELLED");assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1000");
        assertCode(()->approvals.contractCorrection(ADMIN,UUID.randomUUID(),bank.id(),correction(bank.id(),td,td)),"CONTRACT_CORRECTION_INVALID");
        var loan=testContract(f,com.cbs.contracts.ContractModels.Type.LOAN,"COR_LOAN","200","7.12345678");var date=reference.get(ADMIN,bank.id()).businessDate();
        var repay=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),loan,com.cbs.contracts.ContractModels.Kind.REPAY,date,"50","Incorrect repayment"));approveHttp(repay.id());contracts.deliver(repay.id());
        assertCode(()->approvals.contractCorrection(ADMIN,UUID.randomUUID(),bank.id(),correction(bank.id(),loan,loan)),"CONTRACT_CORRECTION_DEPENDENCIES");correctContract(bank.id(),loan,repay.id());
        var spent=testContract(f,com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,"COR_SPENT","1100","0");
        assertCode(()->approvals.contractCorrection(ADMIN,UUID.randomUUID(),bank.id(),correction(bank.id(),loan,loan)),"INSUFFICIENT_AVAILABLE_BALANCE");correctContract(bank.id(),spent,spent);correctContract(bank.id(),loan,loan);
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1000");
        var active=testContract(f,com.cbs.contracts.ContractModels.Type.LOAN,"COR_ACTIVE","200","7.12345678");
        var zero=testContract(f,com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,"COR_ZERO","100","0");
        var day=days.dashboard(ADMIN,bank.id(),25,0);var close=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Close correction test day"));approveHttp(close.id());days.deliver(close.id());
        assertCode(()->approvals.contractCorrection(ADMIN,UUID.randomUUID(),bank.id(),correction(bank.id(),active,active)),"CONTRACT_CORRECTION_CLOSED_DAY");
        var next=reference.get(ADMIN,bank.id()).businessDate();
        for(UUID contract:List.of(active,zero)) {
            var before=bankContracts.position(service.get(ADMIN,bank.id()),contract);
            var accrual=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),contract,com.cbs.contracts.ContractModels.Kind.ACCRUE,next,"0","Accrue"));approveHttp(accrual.id());contracts.deliver(accrual.id());var accrued=bankContracts.position(service.get(ADMIN,bank.id()),contract);
            UUID reversed=correctContract(bank.id(),contract,accrual.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),contract)).isEqualTo(before);
            if(contract.equals(zero))assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.journal_delivery WHERE request_id=?",Integer.class,reversed)).isZero();
            var recalc=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),contract,com.cbs.contracts.ContractModels.Kind.ACCRUE,next,"0","Recalculate from restored carry"));approveHttp(recalc.id());contracts.deliver(recalc.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),contract)).isEqualTo(accrued);
        }
        var beforePayoff=bankContracts.position(service.get(ADMIN,bank.id()),active);var amount=new java.math.BigDecimal(beforePayoff.principal()).add(new java.math.BigDecimal(beforePayoff.interest())).toPlainString();
        var payoff=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),active,com.cbs.contracts.ContractModels.Kind.REPAY,next,amount,"Incorrect full repayment"));approveHttp(payoff.id());contracts.deliver(payoff.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),active).closed()).isTrue();correctContract(bank.id(),active,payoff.id());assertThat(bankContracts.position(service.get(ADMIN,bank.id()),active)).isEqualTo(beforePayoff);
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).isEmpty();
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.contract_reversal WHERE original_id=?",td)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/contracts/"+active+"/history").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(get("/api/v1/admin/tenants/"+bank.id()+"/contracts/"+active+"/history"))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].posting.originalEventId").value(payoff.id().toString()));
    }
    @Autowired com.cbs.contracts.TermServicingService termServicing;
    @Test void termServicingRenewsWithImmutableTermsAndClosesEarlyWithExplicitDeductions()throws Exception {
        var f=fundingFixture("contracts-servicing");var bank=f.f().bank();fund(f,"1000");
        for(String op:List.of("TD_OPEN","TD_REDEEM","TD_EARLY_CLOSE","TD_RENEW"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),op,"10000","20000","Fictional servicing policy")).id());
        var cap=testContract(f,com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,"REN_CAP","100","36.5");
        var pay=testContract(f,com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,"REN_PAY","100","36.5");
        var early=testContract(f,com.cbs.contracts.ContractModels.Type.TERM_DEPOSIT,"SVC_EARLY","100","36.5");
        UUID penaltyLedger=adjustmentLedger(bank,"SVC_PENALTY",com.cbs.accounting.ChartModels.Category.INCOME);
        assertThat(termServicing.alerts(ADMIN,bank.id(),7,25,0).get("total")).isEqualTo(2L);
        var day=days.dashboard(ADMIN,bank.id(),25,0);var close=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Reach maturity"));approveHttp(close.id());days.deliver(close.id());var date=reference.get(ADMIN,bank.id()).businessDate();
        for(UUID id:List.of(cap,pay,early)){var accrual=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),id,com.cbs.contracts.ContractModels.Kind.ACCRUE,date,"0","Accrue before servicing"));approveHttp(accrual.id());contracts.deliver(accrual.id());}
        var op=com.cbs.contracts.TermServicingModels.Operation.RENEW;var gross=com.cbs.contracts.TermServicingModels.TaxBasis.GROSS_INTEREST;
        for(UUID id:List.of(cap,pay)) {
            boolean capital=id.equals(cap);var before=bankContracts.position(service.get(ADMIN,bank.id()),id);var customerBefore=funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance();
            var instruction=new com.cbs.contracts.TermServicingModels.Instruction(service.get(ADMIN,bank.id()).version(),id,op,date,"0",null,gross,capital?com.cbs.contracts.TermServicingModels.InterestDisposition.CAPITALIZE_NET_INTEREST:com.cbs.contracts.TermServicingModels.InterestDisposition.PAY_OUT_INTEREST,"7.5",date.plusMonths(1),capital?"RENEWED_CAP":"RENEWED_PAY","Verified customer renewal authorization");
            var preview=termServicing.preview(ADMIN,bank.id(),instruction);var net=new java.math.BigDecimal(before.interest()).subtract(new java.math.BigDecimal(preview.tax()));
            var request=approvals.termServicing(ADMIN,UUID.randomUUID(),bank.id(),instruction);assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(request.id());
            if(capital){var posting=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.contract_delivery WHERE id=?",String.class,request.id()),com.cbs.contracts.ContractModels.Posting.class);bankContracts.post(service.get(ADMIN,bank.id()),new com.cbs.contracts.ContractModels.Dispatch(posting,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));}
            contracts.deliver(request.id());contracts.deliver(request.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,request.id())).isTrue();
            var after=bankContracts.position(service.get(ADMIN,bank.id()),id);assertThat(new java.math.BigDecimal(after.principal())).isEqualByComparingTo(new java.math.BigDecimal(before.principal()).add(capital?net:java.math.BigDecimal.ZERO));assertThat(after.interest()).isEqualTo("0");
            assertThat(new java.math.BigDecimal(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance())).isEqualByComparingTo(new java.math.BigDecimal(customerBefore).add(capital?java.math.BigDecimal.ZERO:net));
            assertThat(contracts.definition(bank.id(),id).terms().agreementReference()).isEqualTo(capital?"RENEWED_CAP":"RENEWED_PAY");assertThat(jdbc.queryForObject("SELECT payload->'terms'->>'agreementReference' FROM platform.contract WHERE id=?",String.class,id)).isEqualTo(capital?"REN_CAP":"REN_PAY");
            assertCode(()->approvals.contractCorrection(ADMIN,UUID.randomUUID(),bank.id(),correction(bank.id(),id,request.id())),"CONTRACT_CORRECTION_INVALID");
        }
        var interest=new java.math.BigDecimal(bankContracts.position(service.get(ADMIN,bank.id()),early).interest());var penalty=interest.multiply(new java.math.BigDecimal("0.6")).setScale(2).toPlainString();
        var input=new com.cbs.contracts.TermServicingModels.Instruction(service.get(ADMIN,bank.id()).version(),early,com.cbs.contracts.TermServicingModels.Operation.EARLY_CLOSE,date,penalty,penaltyLedger,com.cbs.contracts.TermServicingModels.TaxBasis.INTEREST_AFTER_PENALTY,null,null,null,"EARLY_AUTH","Verified agreed early-closure penalty");
        var preview=termServicing.preview(ADMIN,bank.id(),input);assertThat(new java.math.BigDecimal(preview.customerMovement())).isEqualByComparingTo(new java.math.BigDecimal("100").add(interest).subtract(new java.math.BigDecimal(penalty)).subtract(new java.math.BigDecimal(preview.tax())));
        var request=approvals.termServicing(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(request.id());contracts.deliver(request.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,request.id())).isTrue();assertThat(bankContracts.position(service.get(ADMIN,bank.id()),early).closed()).isTrue();
        assertThat(termServicing.alerts(ADMIN,bank.id(),7,25,0).get("total")).isEqualTo(0L);
        var d2=days.dashboard(ADMIN,bank.id(),25,0);assertThat(d2.blockers()).isEmpty();var c2=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(d2.version(),d2.businessDate(),d2.nextBusinessDate(),"Advance renewed contracts"));approveHttp(c2.id());days.deliver(c2.id());assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CONTRACT_ACCRUAL_REQUIRED");
        var next=reference.get(ADMIN,bank.id()).businessDate();var accrue=approvals.contractEvent(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.contracts.ContractModels.Event(service.get(ADMIN,bank.id()).version(),cap,com.cbs.contracts.ContractModels.Kind.ACCRUE,next,"0","Use renewed rate and principal"));approveHttp(accrue.id());contracts.deliver(accrue.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.contract_delivery WHERE id=?",Boolean.class,accrue.id())).isTrue();assertThat(bankContracts.position(service.get(ADMIN,bank.id()),cap).accruedThrough()).isEqualTo(next);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.contract SET current_terms=payload->'terms' WHERE id=?",cap)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/contracts/maturity-alerts").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
    }
    @Autowired com.cbs.fx.FxValuationService valuations;
    @Autowired com.cbs.fx.BankFxValuationStore bankValuations;
    private com.cbs.accounting.LedgerModels.Definition fxLedger(Tenant bank,String code,UUID chartId,String currency)throws Exception{
        var change=new com.cbs.accounting.LedgerModels.Change(service.get(ADMIN,bank.id()).version(),null,code,code,chartId,bank.legalEntities().getFirst().id(),currency,true,"Fictional FX test mapping");
        var r=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),change);approveHttp(r.id());ledger.deliver(r.id());return ledger.list(ADMIN,bank.id(),code,null,null,25,0).items().getFirst().definition();
    }
    @Test void fxConversionPostsBothCurrenciesAtomicallyAndReversesExactlyOnce()throws Exception{
        var f=fundingFixture("fx-trade");var bank=f.f().bank();fund(f,"1000");var settings=reference.get(ADMIN,bank.id());
        approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.reference.ReferenceModels.Change(settings.version(),List.of(new com.cbs.reference.ReferenceModels.CurrencyRule("KES","HALF_EVEN",true),new com.cbs.reference.ReferenceModels.CurrencyRule("USD","HALF_EVEN",true)),settings.languages(),settings.defaultLanguage(),settings.businessTimezone(),settings.businessDate(),settings.weekendDays(),settings.holidays(),"Fictional multicurrency fixture")).id());
        var date=reference.get(ADMIN,bank.id()).businessDate();
        approveHttp(approvals.product(ADMIN,UUID.randomUUID(),bank.id(),productChange(bank,null,f.f().product().liabilityAccountId(),"USD_SAVE","USD","1","0",true)).id());
        var product=products.list(ADMIN,bank.id(),"USD_SAVE",null,null,25,0).items().getFirst();var usdFixture=new AccountFixture(bank,f.f().customer(),product);
        var opening=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(usdFixture,null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approveHttp(opening.id());accounts.deliver(opening.id());
        var usd=accounts.list(ADMIN,bank.id(),"",null,25,0).items().stream().filter(x->x.productCode().equals(product.code())).findFirst().orElseThrow().id();
        var settlement=fxLedger(bank,"USD_SETTLE",f.source().chartAccountId(),"USD");var liability=fxLedger(bank,"USD_CUSTOMER",product.liabilityAccountId(),"USD");
        approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"USD","FUNDING","1000","2000","Fictional fixture limit")).id());
        var seed=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounts.FundingModels.Change(service.get(ADMIN,bank.id()).version(),usd,settlement.id(),liability.id(),"10",date,"USD-SEED","Fictional opening receipt"));approveHttp(seed.id());funding.deliver(seed.id());
        UUID kesPosition=adjustmentLedger(bank,"FX_POSITION",com.cbs.accounting.ChartModels.Category.EQUITY);
        UUID chartId=ledger.list(ADMIN,bank.id(),"FX_POSITION",null,null,25,0).items().getFirst().definition().chartAccountId();UUID usdPosition=fxLedger(bank,"USD_POSITION",chartId,"USD").id();
        UUID realizedGain=adjustmentLedger(bank,"FX_REAL_GAIN",com.cbs.accounting.ChartModels.Category.INCOME);
        UUID realizedLoss=adjustmentLedger(bank,"FX_REAL_LOSS",com.cbs.accounting.ChartModels.Category.EXPENSE);
        UUID unrealizedGain=adjustmentLedger(bank,"FX_UNREAL_GAIN",com.cbs.accounting.ChartModels.Category.INCOME);
        UUID unrealizedLoss=adjustmentLedger(bank,"FX_UNREAL_LOSS",com.cbs.accounting.ChartModels.Category.EXPENSE);
        var bookRequest=approvals.fxBook(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxValuationModels.Policy(service.get(ADMIN,bank.id()).version(),bank.legalEntities().getFirst().id(),"USD",usdPosition,kesPosition,realizedGain,realizedLoss,unrealizedGain,unrealizedLoss,com.cbs.fx.FxValuationModels.CostBasis.WEIGHTED_AVERAGE,"1","1","TEST/COST-POLICY","Fictional bank-approved cost basis and limits"));
        assertCode(()->approvals.decide(ADMIN,bookRequest.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(bookRequest.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("FX_VALUATION_REQUIRED");
        for(var policy:List.of(new String[]{"KES","FX_CONVERSION"},new String[]{"USD","FX_REVERSAL"}))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),policy[0],policy[1],"500","500","Fictional test-only FX limit")).id());
        var rate=approvals.fxRate(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxModels.Rate(service.get(ADMIN,bank.id()).version(),"USD","KES",date,date,"129","130","131",java.time.Instant.now().plusSeconds(3600),"TEST/FX","Fictional FX rate"));approveHttp(rate.id());
        var input=new com.cbs.fx.FxTradeModels.Trade(service.get(ADMIN,bank.id()).version(),rate.id(),com.cbs.fx.FxModels.Side.BANK_SELLS_BASE,f.account(),usd,kesPosition,usdPosition,"131","1",date,"Convert own funds");
        var preview=fxTrades.preview(ADMIN,bank.id(),input);assertThat(preview.quote().targetAmount()).isEqualTo("1");
        var key=UUID.randomUUID();var trade=approvals.fxTrade(ADMIN,key,bank.id(),input);assertThat(approvals.fxTrade(ADMIN,key,bank.id(),input).id()).isEqualTo(trade.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("DAY_CLOSE_PENDING_APPROVALS");
        assertCode(()->approvals.decide(ADMIN,trade.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(trade.id());
        var batch=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.fx_trade WHERE id=?",String.class,trade.id()),com.cbs.fx.FxTradeModels.Batch.class);
        assertCode(()->com.cbs.fx.FxValuationService.requireTrade(jdbc,service.get(ADMIN,bank.id()),bank.legalEntities().getFirst().id(),"KES","USD",kesPosition,usdPosition,"131","1",date),"FX_POSITION_LIMIT_EXCEEDED");

        assertCode(()->journals.deliver(batch.sourceJournal().id()),"FX_MANAGED");assertThat(journals.pending()).doesNotContain(batch.sourceJournal().id(),batch.targetJournal().id());
        assertCode(()->com.cbs.accounts.OperationService.requireIdle(jdbc,f.account()),"ACCOUNT_OPERATION_PENDING");
        // Force failure after the source leg, proving rollback of the first journal and balances.
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_fx_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.currency='USD' THEN RAISE EXCEPTION 'Injected second-leg failure'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER fail_fx_test BEFORE INSERT ON bank.journal FOR EACH ROW EXECUTE FUNCTION bank.fail_fx_test()");}
        fxTrades.deliver(trade.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.fx_trade WHERE id=?",Boolean.class,trade.id())).isFalse();
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM bank.journal WHERE id='"+batch.sourceJournal().id()+"'")){rs.next();assertThat(rs.getInt(1)).isZero();}
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1000");
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement()){st.execute("DROP TRIGGER fail_fx_test ON bank.journal");st.execute("DROP FUNCTION bank.fail_fx_test()");}
        // Simulate a committed bank transaction with a lost control-plane acknowledgement.
        bankFx.post(service.get(ADMIN,bank.id()),new com.cbs.fx.FxTradeModels.Dispatch(batch,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));fxTrades.deliver(trade.id());fxTrades.deliver(trade.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.fx_trade WHERE id=?",Boolean.class,trade.id())).isTrue();
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("869");assertThat(funding.dashboard(ADMIN,bank.id(),usd,25,0).balance().postedBalance()).isEqualTo("11");
        assertThat(statements.read(ADMIN,bank.id(),usd,date,date,25,0).closingBalance()).isEqualTo("11");
        assertCode(()->journals.reversal(ADMIN,bank.id(),batch.sourceJournal().id(),new com.cbs.accounting.JournalModels.Reversal(service.get(ADMIN,bank.id()).version(),"BAD-REV","No single-leg reversal")),"FX_MANAGED");
        var reversal=approvals.fxReversal(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxTradeModels.Reversal(service.get(ADMIN,bank.id()).version(),trade.id(),date,"Undo complete original conversion"));approveHttp(reversal.id());fxTrades.deliver(reversal.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.fx_trade WHERE id=?",Boolean.class,reversal.id())).isTrue();
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1000");assertThat(funding.dashboard(ADMIN,bank.id(),usd,25,0).balance().postedBalance()).isEqualTo("10");
        assertCode(()->approvals.fxReversal(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxTradeModels.Reversal(service.get(ADMIN,bank.id()).version(),trade.id(),date,"Duplicate reversal")),"FX_REVERSAL_INVALID");
        var second=approvals.fxTrade(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxTradeModels.Trade(service.get(ADMIN,bank.id()).version(),rate.id(),com.cbs.fx.FxModels.Side.BANK_SELLS_BASE,f.account(),usd,kesPosition,usdPosition,"131","1",date,"Open short FX position"));approveHttp(second.id());fxTrades.deliver(second.id());
        var closingRate=approvals.fxClosingRate(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxValuationModels.ClosingRate(service.get(ADMIN,bank.id()).version(),bookRequest.id(),date,"130","TEST/CLOSE-1","Fictional verified closing rate"));approveHttp(closingRate.id());
        var valuationInput=new com.cbs.fx.FxValuationModels.Run(service.get(ADMIN,bank.id()).version(),bookRequest.id(),closingRate.id(),date,"Close reconciled short position");
        var valuationPreview=valuations.preview(ADMIN,bank.id(),valuationInput);assertThat(valuationPreview.snapshot().totals().unrealized()).isEqualTo("1");assertThat(valuationPreview.snapshot().totals().quantity()).isEqualTo("-1");
        var valuation=approvals.fxValuation(ADMIN,UUID.randomUUID(),bank.id(),valuationInput);
        assertCode(()->approvals.decide(ADMIN,valuation.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");approveHttp(valuation.id());
        assertThat(journals.pending()).doesNotContain(valuation.id());assertCode(()->journals.deliver(valuation.id()),"FX_VALUATION_MANAGED");
        assertCode(()->approvals.fxReversal(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxTradeModels.Reversal(service.get(ADMIN,bank.id()).version(),second.id(),date,"Sealed day")),"FX_BOOK_SEALED");
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_valuation_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected receipt failure'; END $$");st.execute("CREATE TRIGGER fail_valuation_test BEFORE INSERT ON bank.fx_valuation FOR EACH ROW EXECUTE FUNCTION bank.fail_valuation_test()");}
        valuations.deliver(valuation.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.fx_valuation WHERE id=?",Boolean.class,valuation.id())).isFalse();
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM bank.journal WHERE id='"+valuation.id()+"'")){rs.next();assertThat(rs.getInt(1)).isZero();}
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement()){st.execute("DROP TRIGGER fail_valuation_test ON bank.fx_valuation");st.execute("DROP FUNCTION bank.fail_valuation_test()");}
        var posting=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.fx_valuation WHERE id=?",String.class,valuation.id()),com.cbs.fx.FxValuationModels.Posting.class);
        bankValuations.post(service.get(ADMIN,bank.id()),new com.cbs.fx.FxValuationModels.Dispatch(posting,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),CHECKER.correlationId()));valuations.deliver(valuation.id());valuations.deliver(valuation.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.fx_valuation WHERE id=?",Boolean.class,valuation.id())).isTrue();
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("869");
        assertCode(()->journals.reversal(ADMIN,bank.id(),valuation.id(),new com.cbs.accounting.JournalModels.Reversal(service.get(ADMIN,bank.id()).version(),"BAD-FXV-REV","No generic valuation reversal")),"FX_VALUATION_MANAGED");
        var day=days.dashboard(ADMIN,bank.id(),25,0);assertThat(day.blockers()).isEmpty();
        var close=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Close valued FX day"));approveHttp(close.id());days.deliver(close.id());
        var next=reference.get(ADMIN,bank.id()).businessDate();
        approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"USD","FX_CONVERSION","500","500","Fictional cover limit")).id());
        var nextRate=approvals.fxRate(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxModels.Rate(service.get(ADMIN,bank.id()).version(),"USD","KES",next,next,"129","130","131",java.time.Instant.now().plusSeconds(3600),"TEST/FX-NEXT","Fictional next-day FX rate"));approveHttp(nextRate.id());
        var cover=approvals.fxTrade(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxTradeModels.Trade(service.get(ADMIN,bank.id()).version(),nextRate.id(),com.cbs.fx.FxModels.Side.BANK_BUYS_BASE,usd,f.account(),usdPosition,kesPosition,"1","129",next,"Cover short position"));approveHttp(cover.id());fxTrades.deliver(cover.id());
        var nextClosing=approvals.fxClosingRate(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.fx.FxValuationModels.ClosingRate(service.get(ADMIN,bank.id()).version(),bookRequest.id(),next,"130","TEST/CLOSE-2","Fictional verified rate"));approveHttp(nextClosing.id());
        var nextInput=new com.cbs.fx.FxValuationModels.Run(service.get(ADMIN,bank.id()).version(),bookRequest.id(),nextClosing.id(),next,"Recognize realized gain and release unrealized gain");
        var nextPreview=valuations.preview(ADMIN,bank.id(),nextInput);assertThat(nextPreview.snapshot().totals().realized()).isEqualTo("2");assertThat(nextPreview.snapshot().totals().unrealized()).isEqualTo("0");assertThat(nextPreview.snapshot().previousUnrealized()).isEqualTo("1");
        var nextValuation=approvals.fxValuation(ADMIN,UUID.randomUUID(),bank.id(),nextInput);approveHttp(nextValuation.id());valuations.deliver(nextValuation.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.fx_valuation WHERE id=?",Boolean.class,nextValuation.id())).isTrue();
        try(var c=TestDatabases.bank("fxtrade");var st=c.createStatement();var rs=st.executeQuery("SELECT coalesce((SELECT debit-credit FROM bank.ledger_balance WHERE ledger_id='"+kesPosition+"'),0),coalesce((SELECT credit-debit FROM bank.ledger_balance WHERE ledger_id='"+realizedGain+"'),0),coalesce((SELECT credit-debit FROM bank.ledger_balance WHERE ledger_id='"+unrealizedGain+"'),0)")){rs.next();assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("0");assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("2");assertThat(rs.getBigDecimal(3)).isEqualByComparingTo("0");}
        assertThatThrownBy(()->jdbc.update("UPDATE platform.fx_closing_rate SET payload='{}'::jsonb WHERE id=?",nextClosing.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/fx/valuation").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());

    }
    @Test void fxRatesRequireIndependentApprovalAndRejectOverlappingInversePairs() throws Exception {
        var bank=register("fx-main");assertThat(fxTrades.list(ADMIN,bank.id(),25,0).get("storageReady")).isEqualTo(false);var ungrantedBank=bank.id();assertCode(()->contracts.list(ADMIN,ungrantedBank,com.cbs.contracts.ContractModels.Type.LOAN,25,0),"LENDING_ACCESS_DENIED");var settings=reference.get(ADMIN,bank.id());
        approveHttp(approvals.settings(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.reference.ReferenceModels.Change(settings.version(),List.of(new com.cbs.reference.ReferenceModels.CurrencyRule("KES","HALF_EVEN",true),new com.cbs.reference.ReferenceModels.CurrencyRule("USD","HALF_EVEN",true)),settings.languages(),settings.defaultLanguage(),settings.businessTimezone(),settings.businessDate(),settings.weekendDays(),settings.holidays(),"Enable fictional FX currencies")).id());
        var date=reference.get(ADMIN,bank.id()).businessDate();bank=provision(service.get(ADMIN,bank.id()));final UUID bankId=bank.id();
        var rate=new com.cbs.fx.FxModels.Rate(service.get(ADMIN,bankId).version(),"USD","KES",date,date,"129","130","131",java.time.Instant.now().plusSeconds(3600),"TEST/FX","Fictional rate evidence");
        var request=approvals.fxRate(ADMIN,UUID.randomUUID(),bankId,rate);assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own rate"),"SELF_APPROVAL_FORBIDDEN");approveHttp(request.id());
        assertThat(fx.quote(ADMIN,bankId,new com.cbs.fx.FxModels.QuoteInput(request.id(),com.cbs.fx.FxModels.Side.BANK_BUYS_BASE,"100")).targetAmount()).isEqualTo("12900");
        var overlap=new com.cbs.fx.FxModels.Rate(service.get(ADMIN,bankId).version(),"KES","USD",date,date,"0.007","0.008","0.009",java.time.Instant.now().plusSeconds(3600),"TEST/INVERSE","Duplicate inverse period");assertCode(()->approvals.fxRate(ADMIN,UUID.randomUUID(),bankId,overlap),"FX_RATE_OVERLAP");
        assertCode(()->fx.quote(ADMIN,UUID.randomUUID(),new com.cbs.fx.FxModels.QuoteInput(request.id(),com.cbs.fx.FxModels.Side.BANK_BUYS_BASE,"100")),"TENANT_NOT_FOUND");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.fx_rate SET payload='{}'::jsonb WHERE id=?",request.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        approveHttp(approvals.fxWithdraw(ADMIN,UUID.randomUUID(),bankId,new com.cbs.fx.FxModels.Withdraw(service.get(ADMIN,bankId).version(),request.id(),"Withdraw incorrect fictional quote")).id());assertCode(()->fx.quote(ADMIN,bankId,new com.cbs.fx.FxModels.QuoteInput(request.id(),com.cbs.fx.FxModels.Side.BANK_BUYS_BASE,"100")),"FX_RATE_UNAVAILABLE");
        mvc.perform(get("/api/v1/admin/tenants/"+bankId+"/fx").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
    }
    @Autowired com.cbs.payments.PaymentCaseService paymentCases;
    @Autowired com.cbs.payments.PaymentReturnService paymentReturns;
    @Autowired com.cbs.payments.PaymentAccess paymentAccess;
    @Test @SuppressWarnings("unchecked") void paymentLifecycleCarriesTimingDifferencesAndCreditsVerifiedReturnsOnce() throws Exception {
        var f=fundingFixture("payments-lifecycle");var bank=f.f().bank();var initial=fund(f,"1000");
        var maker=paymentOperator(bank,"reconcile-maker",com.cbs.payments.PaymentAccess.Role.RECONCILER);
        var checker=paymentOperator(bank,"reconcile-checker",com.cbs.payments.PaymentAccess.Role.SUPERVISOR);
        var auditor=paymentOperator(bank,"reconcile-auditor",com.cbs.payments.PaymentAccess.Role.AUDITOR);
        assertCode(()->paymentAccess.require(auditor,bank.id(),com.cbs.payments.PaymentAccess.Capability.MAKE),"PAYMENT_ACCESS_DENIED");
        assertCode(()->paymentCases.dashboard(maker,UUID.randomUUID(),25,0),"PAYMENT_ACCESS_DENIED");
        mvc.perform(get("/api/v1/banks/"+bank.id()+"/payment-operations/ledgers").header("Authorization","Bearer "+TestIdentity.token(maker.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].definition.currency").value("KES"));
        var opened=compliance.open(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.compliance.ComplianceModels.Category.KYC_REVIEW,"KYC/LIFECYCLE","Fictional verified onboarding"));
        approveHttp(approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),(UUID)((java.util.Map<?,?>)opened.get("case")).get("id"),com.cbs.compliance.ComplianceModels.Decision.CLEARED)).id());
        approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"KES","WITHDRAWAL_REVERSAL","1000","2000","Fictional return policy")).id());
        org.mockito.Mockito.when(mpesa.configured(bank.id())).thenReturn(true);org.mockito.Mockito.when(mpesa.binding(bank.id(),f.source().id())).thenReturn(true);org.mockito.Mockito.when(mpesa.profile(bank.id())).thenReturn("fictional-sandbox-profile");
        var payout=approvals.payment(ADMIN,UUID.randomUUID(),bank.id(),paymentOrder(f,"100","LIFECYCLE-1"));approveHttp(payout.id());operations.deliver(payout.id());
        // Monitoring classifies the payout once, as a mobile debit, not also as a plain withdrawal.
        monitoring.scan();
        assertThat(jdbc.queryForList("SELECT channel||'/'||direction FROM platform.monitoring_event WHERE source_id=?",String.class,payout.id())).containsExactly("MOBILE/OUT");
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).blockers()).contains("ACCOUNT_CLOSURE_PAYMENT_REVIEW");
        var lifecycleToken=new java.util.concurrent.atomic.AtomicReference<String>();org.mockito.Mockito.when(mpesa.transfer(org.mockito.ArgumentMatchers.eq(bank.id()),org.mockito.ArgumentMatchers.eq(payout.id()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation->{lifecycleToken.set(invocation.getArgument(3));return new com.cbs.payments.PaymentModels.Acceptance("life-conversation","life-originator");});payments.dispatch(ADMIN,bank.id(),payout.id());
        var date=reference.get(ADMIN,bank.id()).businessDate();String header="receipt,date,type,credit,debit,balance,payment_reference,beneficiary\n";
        UUID first=statementImports.upload(maker,bank.id(),UUID.randomUUID(),new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"LIFE-DAY1","WALLET",date,date,"0","1000","TEST/DAY1",java.util.Map.of(),header+"LIFE-TOPUP,"+date+",CREDIT,1000,0,1000,,\n"));
        var firstRows=(List<com.cbs.payments.StatementImportService.Row>)statementImports.detail(maker,bank.id(),50,0,first,false).get("rows");
        decidePayment(checker,approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),first,com.cbs.payments.StatementImportModels.Kind.LINK,firstRows.getFirst().id(),initial)).id());
        var open=new com.cbs.payments.PaymentCaseModels.Open(service.get(ADMIN,bank.id()).version(),payout.id(),com.cbs.payments.PaymentCaseModels.Kind.SETTLEMENT_DELAY,first,null,date.plusDays(2),"TEST/TIMING","Provider payout absent from verified statement");UUID key=UUID.randomUUID(),timing=paymentCases.open(maker,bank.id(),key,open);assertThat(paymentCases.open(maker,bank.id(),key,open)).isEqualTo(timing);
        var carry=approvals.paymentCase(maker,UUID.randomUUID(),bank.id(),paymentCaseChange(bank.id(),timing,com.cbs.payments.PaymentCaseModels.Decision.CARRY,date.plusDays(2)));
        assertCode(()->approvals.decide(auditor,carry.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Auditor cannot approve"),"PAYMENT_ACCESS_DENIED");decidePayment(checker,carry.id());
        var firstReport=statementImports.detail(auditor,bank.id(),50,0,first,false);assertThat((List<?>)firstReport.get("blockers")).isEmpty();assertThat(((java.util.Map<?,?>)firstReport.get("balances")).get("closingDifference")).isEqualTo("-100");
        decidePayment(checker,approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),first,com.cbs.payments.StatementImportModels.Kind.CLOSE,null,null)).id());
        var day=days.dashboard(ADMIN,bank.id(),25,0);assertThat(day.blockers()).isEmpty();var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Carry independently investigated settlement"));approveHttp(closing.id());days.deliver(closing.id());
        var next=reference.get(ADMIN,bank.id()).businessDate();UUID second=statementImports.upload(maker,bank.id(),UUID.randomUUID(),new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"LIFE-DAY2","WALLET",next,next,"1000","1000","TEST/DAY2",java.util.Map.of(),header+"LIFE-PAID,"+next+",PAYOUT,0,100,900,LIFECYCLE-1,254700000001\nLIFE-RETURN,"+next+",REVERSAL,100,0,1000,LIFECYCLE-1,254700000001\n"));
        var rows=(List<com.cbs.payments.StatementImportService.Row>)statementImports.detail(maker,bank.id(),50,0,second,false).get("rows");
        mvc.perform(get("/api/v1/banks/"+bank.id()+"/payment-operations/statement-imports/"+second+"/return-candidates").param("paymentId",payout.id().toString()).header("Authorization","Bearer "+TestIdentity.token(maker.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$[0].source.credit").value("100"));
        mvc.perform(get("/api/v1/banks/"+bank.id()+"/payment-operations/operators").header("Authorization","Bearer "+TestIdentity.token(maker.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        decidePayment(checker,approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),second,com.cbs.payments.StatementImportModels.Kind.MATCH,rows.getFirst().id(),payout.id())).id());
        decidePayment(checker,approvals.paymentCase(maker,UUID.randomUUID(),bank.id(),paymentCaseChange(bank.id(),timing,com.cbs.payments.PaymentCaseModels.Decision.RESOLVE,next)).id());
        UUID partial=statementImports.upload(maker,bank.id(),UUID.randomUUID(),new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"LIFE-PARTIAL","WALLET",next,next,"900","950","TEST/PARTIAL",java.util.Map.of(),header+"PARTIAL-RETURN,"+next+",REVERSAL,50,0,950,LIFECYCLE-1,254700000001\n"));
        var partialRow=((List<com.cbs.payments.StatementImportService.Row>)statementImports.detail(maker,bank.id(),50,0,partial,false).get("rows")).getFirst();
        UUID partialCase=paymentCases.open(maker,bank.id(),UUID.randomUUID(),new com.cbs.payments.PaymentCaseModels.Open(service.get(ADMIN,bank.id()).version(),payout.id(),com.cbs.payments.PaymentCaseModels.Kind.PROVIDER_REVERSAL,partial,partialRow.id(),next,"TEST/PARTIAL","Investigate unsupported partial credit"));
        assertCode(()->approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),partial,com.cbs.payments.StatementImportModels.Kind.RETURN,partialRow.id(),partialCase)),"PAYMENT_RETURN_INVALID");
        decidePayment(checker,approvals.paymentCase(maker,UUID.randomUUID(),bank.id(),paymentCaseChange(bank.id(),partialCase,com.cbs.payments.PaymentCaseModels.Decision.CANCEL,next)).id());
        decidePayment(checker,approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),partial,com.cbs.payments.StatementImportModels.Kind.SUPERSEDE,null,second)).id());
        UUID returned=paymentCases.open(maker,bank.id(),UUID.randomUUID(),new com.cbs.payments.PaymentCaseModels.Open(service.get(ADMIN,bank.id()).version(),payout.id(),com.cbs.payments.PaymentCaseModels.Kind.PROVIDER_REVERSAL,second,rows.getLast().id(),next,"TEST/RETURN","Provider confirms full funds returned"));
        paymentCases.note(maker,bank.id(),returned,new com.cbs.payments.PaymentCaseModels.Note(0,"TEST/SUPPORT","Verified wallet credit and original beneficiary"));
        assertCode(()->approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),second,com.cbs.payments.StatementImportModels.Kind.BOOK,rows.getLast().id(),f.liability().id())),"PAYMENT_RETURN_REQUIRED");
        mvc.perform(get("/api/v1/banks/"+bank.id()+"/payment-operations/cases/"+returned).header("Authorization","Bearer "+TestIdentity.token(auditor.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$.case.source.kind").value("PROVIDER_REVERSAL"));
        var credit=approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),second,com.cbs.payments.StatementImportModels.Kind.RETURN,rows.getLast().id(),returned));
        assertThat(approvals.details(checker,credit.id()).request().proposedValues()).containsKey("customerCredit");decidePayment(checker,credit.id());
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        payments.callback(payout.id(),lifecycleToken.get(),"{\"Result\":{\"ResultCode\":0,\"ConversationID\":\"life-conversation\",\"OriginatorConversationID\":\"life-originator\",\"TransactionID\":\"CONTRADICTORY-RECEIPT\"}}");
        operations.deliver(credit.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.account_operation WHERE request_id=?",Boolean.class,credit.id())).isFalse();
        decidePayment(checker,approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),second,com.cbs.payments.StatementImportModels.Kind.CLEAR_CONFLICT,null,payout.id())).id());
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{operations.deliver(credit.id());t.setRollbackOnly();});operations.deliver(credit.id());operations.deliver(credit.id());paymentReturns.refresh(returned);paymentReturns.refresh(returned);
        assertThat(payments.detail(auditor,bank.id(),payout.id()).get("state")).isEqualTo("RETURNED");assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("1000");assertThat(paymentCases.row(auditor,bank.id(),returned).get("state")).isEqualTo("RESOLVED");
        assertThat((List<?>)statementImports.detail(auditor,bank.id(),50,0,first,false).get("blockers")).isEmpty();assertThat((List<?>)statementImports.detail(auditor,bank.id(),50,0,second,false).get("blockers")).isEmpty();
        decidePayment(checker,approvals.statementAction(maker,UUID.randomUUID(),bank.id(),statementAction(bank.id(),second,com.cbs.payments.StatementImportModels.Kind.CLOSE,null,null)).id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).isEmpty();
        UUID dispute=paymentCases.open(maker,bank.id(),UUID.randomUUID(),new com.cbs.payments.PaymentCaseModels.Open(service.get(ADMIN,bank.id()).version(),payout.id(),com.cbs.payments.PaymentCaseModels.Kind.DISPUTE,null,null,next,"TEST/COMPLAINT","Fictional follow-up investigation"));
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("PAYMENT_CASE_OPEN");
        var cancellation=approvals.paymentCase(maker,UUID.randomUUID(),bank.id(),paymentCaseChange(bank.id(),dispute,com.cbs.payments.PaymentCaseModels.Decision.CANCEL,next));
        var makerStaff=access.requireAssignable(ADMIN.issuer(),maker.subject());approveHttp(approvals.paymentGrant(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.payments.PaymentAccess.Grant(service.get(ADMIN,bank.id()).version(),makerStaff.id(),com.cbs.payments.PaymentAccess.Role.RECONCILER,false,"Revoke fictional access")).id());
        assertCode(()->approvals.decide(checker,cancellation.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Revoked maker"),"PAYMENT_ACCESS_DENIED");assertCode(()->paymentCases.dashboard(maker,bank.id(),25,0),"PAYMENT_ACCESS_DENIED");
        var replacement=approvals.paymentCase(ADMIN,UUID.randomUUID(),bank.id(),paymentCaseChange(bank.id(),dispute,com.cbs.payments.PaymentCaseModels.Decision.CANCEL,next));decidePayment(checker,replacement.id());
        assertThatThrownBy(()->jdbc.update("UPDATE platform.payment_case SET state='OPEN',version=version+1 WHERE id=?",returned)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.payment_case_event SET action='FORGED' WHERE case_id=?",returned)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private Actor paymentOperator(Tenant bank,String subject,com.cbs.payments.PaymentAccess.Role role)throws Exception{var staff=access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput(subject,subject,subject+"@example.test","Fictional payment operator"));approveHttp(approvals.paymentGrant(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.payments.PaymentAccess.Grant(service.get(ADMIN,bank.id()).version(),staff.id(),role,true,"Assign fictional bank payment role")).id());return new Actor(ADMIN.issuer(),subject,false,UUID.randomUUID());}
    private void decidePayment(Actor checker,UUID request){assertThat(approvals.decide(checker,request,com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent verification of provider evidence").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.APPROVED);}
    private com.cbs.payments.PaymentCaseModels.Change paymentCaseChange(UUID bank,UUID id,com.cbs.payments.PaymentCaseModels.Decision decision,java.time.LocalDate due){return new com.cbs.payments.PaymentCaseModels.Change(service.get(ADMIN,bank).version(),id,((Number)paymentCases.row(ADMIN,bank,id).get("version")).longValue(),decision,null,due,"TEST/REVIEW","Verified settlement investigation");}

    @Autowired com.cbs.payments.StatementImportService statementImports;
    @Test void mpesaPaymentsRequireEvidenceAndNeverResendUnknownOutcomes() throws Exception {
        var f=fundingFixture("payments-main");var bank=f.f().bank();var initialFunding=fund(f,"1000");
        org.mockito.Mockito.when(mpesa.configured(bank.id())).thenReturn(true);
        org.mockito.Mockito.when(mpesa.binding(bank.id(),f.source().id())).thenReturn(true);
        org.mockito.Mockito.when(mpesa.profile(bank.id())).thenReturn("fictional-sandbox-profile");
        var input=paymentOrder(f,"100","MPESA-001");
        assertCode(()->approvals.payment(ADMIN,UUID.randomUUID(),bank.id(),input),"PAYMENT_VERIFICATION_REQUIRED");
        var opened=compliance.open(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.compliance.ComplianceModels.Category.KYC_REVIEW,"KYC/TEST","Fictional verified onboarding"));
        UUID caseId=(UUID)((java.util.Map<?,?>)opened.get("case")).get("id");approveHttp(approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),caseId,com.cbs.compliance.ComplianceModels.Decision.CLEARED)).id());
        var order=paymentOrder(f,"100","MPESA-001");UUID key=UUID.randomUUID();var request=approvals.payment(ADMIN,key,bank.id(),order);
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(request.id());assertThat(approvals.payment(ADMIN,key,bank.id(),order).id()).isEqualTo(request.id());
        assertCode(()->payments.dispatch(ADMIN,bank.id(),request.id()),"PAYMENT_POSTING_REQUIRED");operations.deliver(request.id());
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("PAYMENT_UNRESOLVED");
        var callbackToken=new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.when(mpesa.transfer(org.mockito.ArgumentMatchers.eq(bank.id()),org.mockito.ArgumentMatchers.eq(request.id()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation->{callbackToken.set(invocation.getArgument(3));return new com.cbs.payments.PaymentModels.Acceptance("conversation-1","originator-1");});
        try(var pool=Executors.newFixedThreadPool(2)){for(var result:pool.invokeAll(List.of(()->{payments.dispatch(ADMIN,bank.id(),request.id());return true;},()->{payments.dispatch(ADMIN,bank.id(),request.id());return true;})))result.get();}
        org.mockito.Mockito.verify(mpesa,org.mockito.Mockito.times(1)).transfer(org.mockito.ArgumentMatchers.eq(bank.id()),org.mockito.ArgumentMatchers.eq(request.id()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        String callback="{\"Result\":{\"ResultCode\":0,\"ConversationID\":\"conversation-1\",\"OriginatorConversationID\":\"originator-1\",\"TransactionID\":\"TESTRECEIPT1\",\"ResultParameters\":{\"ResultParameter\":[{\"Key\":\"TransactionAmount\",\"Value\":100}]}}}";
        assertCode(()->payments.callback(request.id(),"0".repeat(64),callback),"PAYMENT_CALLBACK_INVALID");
        mvc.perform(post("/api/v1/payments/mpesa/callback/"+request.id()+"/"+callbackToken.get()).contentType("application/json").content(callback)).andExpect(status().isOk());
        payments.callback(request.id(),callbackToken.get(),callback);
        assertThat(payments.detail(ADMIN,bank.id(),request.id()).get("state")).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payment_observation WHERE payment_id=?",Integer.class,request.id())).isEqualTo(1);
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL_REVERSAL,"100",null,request.id())),"PAYMENT_MANAGED");
        UUID wrong=payments.statement(ADMIN,bank.id(),UUID.randomUUID(),paymentStatement(bank.id(),request.id(),"99","TESTRECEIPT1",com.cbs.payments.PaymentModels.Outcome.PAID));
        assertCode(()->approvals.paymentReconcile(ADMIN,UUID.randomUUID(),bank.id(),paymentResolution(bank.id(),request.id(),wrong,com.cbs.payments.PaymentModels.Resolution.MATCH)),"PAYMENT_RECONCILIATION_MISMATCH");
        var statement=paymentStatement(bank.id(),request.id(),"100","TESTRECEIPT1",com.cbs.payments.PaymentModels.Outcome.PAID);UUID statementKey=UUID.randomUUID();UUID matched=payments.statement(ADMIN,bank.id(),statementKey,statement);assertThat(payments.statement(ADMIN,bank.id(),statementKey,statement)).isEqualTo(matched);
        var review=approvals.paymentReconcile(ADMIN,UUID.randomUUID(),bank.id(),paymentResolution(bank.id(),request.id(),matched,com.cbs.payments.PaymentModels.Resolution.MATCH));approveHttp(review.id());
        assertThat(payments.detail(ADMIN,bank.id(),request.id()).get("state")).isEqualTo("SETTLED");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        var uncertain=approvals.payment(ADMIN,UUID.randomUUID(),bank.id(),paymentOrder(f,"50","MPESA-002"));approveHttp(uncertain.id());operations.deliver(uncertain.id());
        org.mockito.Mockito.when(mpesa.transfer(org.mockito.ArgumentMatchers.eq(bank.id()),org.mockito.ArgumentMatchers.eq(uncertain.id()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation->{callbackToken.set(invocation.getArgument(3));throw new RuntimeException("Simulated transport timeout");});
        payments.dispatch(ADMIN,bank.id(),uncertain.id());payments.dispatch(ADMIN,bank.id(),uncertain.id());
        assertThat(payments.detail(ADMIN,bank.id(),uncertain.id()).get("state")).isEqualTo("UNKNOWN");
        org.mockito.Mockito.verify(mpesa,org.mockito.Mockito.times(1)).transfer(org.mockito.ArgumentMatchers.eq(bank.id()),org.mockito.ArgumentMatchers.eq(uncertain.id()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        var limit=new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"KES","WITHDRAWAL_REVERSAL","1000","2000","Fictional refund policy");approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),limit).id());
        UUID failed=payments.statement(ADMIN,bank.id(),UUID.randomUUID(),paymentStatement(bank.id(),uncertain.id(),"50","SUPPORT-FAILED-2",com.cbs.payments.PaymentModels.Outcome.FAILED));
        var selfEvidence=approvals.paymentReconcile(CHECKER,UUID.randomUUID(),bank.id(),paymentResolution(bank.id(),uncertain.id(),failed,com.cbs.payments.PaymentModels.Resolution.REFUND));
        assertCode(()->approvals.decide(ADMIN,selfEvidence.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own evidence"),"PAYMENT_INDEPENDENT_EVIDENCE_REQUIRED");
        approvals.decide(CHECKER,selfEvidence.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"New request by evidence recorder");
        var refund=approvals.paymentReconcile(ADMIN,UUID.randomUUID(),bank.id(),paymentResolution(bank.id(),uncertain.id(),failed,com.cbs.payments.PaymentModels.Resolution.REFUND));approveHttp(refund.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{operations.deliver(refund.id());t.setRollbackOnly();});
        operations.deliver(refund.id());operations.deliver(refund.id());payments.refreshRefund(ADMIN,bank.id(),uncertain.id());
        assertThat(payments.detail(ADMIN,bank.id(),uncertain.id()).get("state")).isEqualTo("REFUNDED");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("PAYMENT_UNRESOLVED");
        payments.callback(uncertain.id(),callbackToken.get(),callback);assertThat(payments.detail(ADMIN,bank.id(),uncertain.id()).get("reconciliation_conflict")).isEqualTo(true);
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("PAYMENT_UNRESOLVED");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("900");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.payment_statement SET payload='{}' WHERE id=?",failed)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/payments").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(get("/api/v1/admin/tenants/"+UUID.randomUUID()+"/payments/"+request.id()))).andExpect(status().isNotFound());
        verifyFullStatementImport(f,initialFunding,request.id(),uncertain.id());
    }
    @SuppressWarnings("unchecked")
    private void verifyFullStatementImport(FundingFixture f,UUID initialFunding,UUID paid,UUID refunded)throws Exception{
        var bank=f.f().bank();var date=reference.get(ADMIN,bank.id()).businessDate();
        UUID expense=adjustmentLedger(bank,"5900",com.cbs.accounting.ChartModels.Category.EXPENSE);
        var importedPayment=approvals.payment(ADMIN,UUID.randomUUID(),bank.id(),paymentOrder(f,"20","MPESA-003"));approveHttp(importedPayment.id());operations.deliver(importedPayment.id());
        org.mockito.Mockito.when(mpesa.transfer(org.mockito.ArgumentMatchers.eq(bank.id()),org.mockito.ArgumentMatchers.eq(importedPayment.id()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.cbs.payments.PaymentModels.Acceptance("conversation-3","originator-3"));payments.dispatch(ADMIN,bank.id(),importedPayment.id());
        String csv="receipt,date,type,credit,debit,balance,payment_reference,beneficiary\nTOPUP,"+date+",CREDIT,1000,0,1000,,\nTESTRECEIPT1,"+date+",PAYOUT,0,100,900,MPESA-001,254700000001\nTESTRECEIPT3,"+date+",PAYOUT,0,20,880,MPESA-003,254700000001\nFEE1,"+date+",FEE,0,5,875,,\n";
        var upload=new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"STMT-001","TEST-WALLET",date,date,"0","875","TEST/ORIGINAL",java.util.Map.of(),csv);
        assertThat(statementImports.preview(ADMIN,bank.id(),upload).get("rows")).isEqualTo(4);
        UUID key=UUID.randomUUID(),batch=statementImports.upload(ADMIN,bank.id(),key,upload);
        assertThat(statementImports.upload(ADMIN,bank.id(),key,upload)).isEqualTo(batch);
        var same=new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"STMT-COPY","TEST-WALLET",date,date,"0","875","TEST/COPY",java.util.Map.of(),csv);
        assertThat(statementImports.upload(ADMIN,bank.id(),UUID.randomUUID(),same)).isEqualTo(batch);
        assertCode(()->statementImports.upload(ADMIN,bank.id(),key,same),"IDEMPOTENCY_CONFLICT");
        var detail=statementImports.detail(ADMIN,bank.id(),50,0,batch,false);
        var rows=(List<com.cbs.payments.StatementImportService.Row>)detail.get("rows");UUID topup=rows.get(0).id(),payout=rows.get(1).id(),fee=rows.get(3).id();
        assertThat((List<String>)detail.get("blockers")).contains("UNRESOLVED_ROWS","CLOSING_DIFFERENCE","PAYMENT_CONFLICT");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("STATEMENT_IMPORT_OPEN");
        assertCode(()->approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.CLOSE,null,null)),"STATEMENT_NOT_RECONCILED");
        var self=approvals.statementAction(CHECKER,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.LINK,topup,initialFunding));
        assertCode(()->approvals.decide(ADMIN,self.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own uploaded evidence"),"STATEMENT_INDEPENDENT_REVIEW_REQUIRED");
        approvals.decide(CHECKER,self.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Correct maker");
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.LINK,topup,initialFunding)).id());
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.MATCH,payout,paid)).id());
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.MATCH,rows.get(2).id(),importedPayment.id())).id());
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.UNLINK,topup,null)).id());
        assertThat((List<String>)statementImports.detail(ADMIN,bank.id(),50,0,batch,false).get("blockers")).contains("UNMATCHED_CBS");
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.LINK,topup,initialFunding)).id());
        var book=approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.BOOK,fee,expense));approveHttp(book.id());
        var stale=statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.CLEAR_CONFLICT,null,refunded);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{journals.deliver(book.id());t.setRollbackOnly();});journals.deliver(book.id());journals.deliver(book.id());
        assertCode(()->approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),stale),"STATEMENT_REPORT_CHANGED");
        assertCode(()->approvals.reverseJournal(ADMIN,UUID.randomUUID(),bank.id(),book.id(),new com.cbs.accounting.JournalModels.Reversal(service.get(ADMIN,bank.id()).version(),"BAD-REV","Managed adjustment")),"STATEMENT_MANAGED");
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.CLEAR_CONFLICT,null,refunded)).id());
        detail=statementImports.detail(ADMIN,bank.id(),50,0,batch,false);assertThat((List<String>)detail.get("blockers")).isEmpty();
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.CLOSE,null,null)).id());
        assertThat(statementImports.detail(ADMIN,bank.id(),50,0,batch,false).get("status")).isEqualTo("CLOSED");
        assertCode(()->com.cbs.payments.StatementImportService.requireWritable(jdbc,bank.id(),f.source().id(),date),"STATEMENT_PERIOD_CLOSED");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.statement_import SET status='OPEN' WHERE id=?",batch)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.REOPEN,null,null)).id());
        var reverse=approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.REVERSE,fee,null));approveHttp(reverse.id());journals.deliver(reverse.id());
        var corrected=approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.BOOK,fee,expense));approveHttp(corrected.id());journals.deliver(corrected.id());
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.CLOSE,null,null)).id());
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("880");
        assertThat(statementImports.export(ADMIN,bank.id(),batch).get("csv")).contains("TESTRECEIPT3","CLOSED","875");
        mvc.perform(admin(get("/api/v1/admin/tenants/"+UUID.randomUUID()+"/statement-imports/"+batch))).andExpect(status().isNotFound());
        var overlap=new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"STMT-OVERLAP","TEST-WALLET",date.minusDays(1),date,"0","875","TEST/OVERLAP",java.util.Map.of(),csv);
        UUID duplicate=statementImports.upload(ADMIN,bank.id(),UUID.randomUUID(),overlap);
        var duplicateRows=(List<com.cbs.payments.StatementImportService.Row>)statementImports.detail(ADMIN,bank.id(),50,0,duplicate,false).get("rows");
        assertCode(()->approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),duplicate,com.cbs.payments.StatementImportModels.Kind.BOOK,duplicateRows.get(3).id(),expense)),"STATEMENT_RECEIPT_USED");
        for(var row:duplicateRows)approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),duplicate,com.cbs.payments.StatementImportModels.Kind.DUPLICATE,row.id(),row.relatedId())).id());
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),duplicate,com.cbs.payments.StatementImportModels.Kind.CLOSE,null,null)).id());
        assertCode(()->approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),batch,com.cbs.payments.StatementImportModels.Kind.REOPEN,null,null)),"STATEMENT_DEPENDENTS_CLOSED");
        var incorrect=new com.cbs.payments.StatementImportModels.Upload(service.get(ADMIN,bank.id()).version(),f.source().id(),"STMT-INCORRECT","TEST-WALLET",date,date,"0","0","TEST/INCORRECT",java.util.Map.of(),"receipt,date,type,credit,debit,balance,payment_reference,beneficiary\n");
        UUID incorrectId=statementImports.upload(ADMIN,bank.id(),UUID.randomUUID(),incorrect);
        approveHttp(approvals.statementAction(ADMIN,UUID.randomUUID(),bank.id(),statementAction(bank.id(),incorrectId,com.cbs.payments.StatementImportModels.Kind.SUPERSEDE,null,batch)).id());
        assertThat(statementImports.detail(ADMIN,bank.id(),50,0,incorrectId,false).get("status")).isEqualTo("SUPERSEDED");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("STATEMENT_IMPORT_OPEN","PAYMENT_UNRESOLVED");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.statement_line SET source='{}' WHERE id=?",fee)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/statement-imports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/tenants/"+bank.id()+"/statement-imports").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(get("/api/v1/admin/tenants/"+bank.id()+"/statement-imports/"+batch))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.balances.closingDifference").value("0"));
    }
    private com.cbs.payments.StatementImportModels.Action statementAction(UUID bank,UUID batch,com.cbs.payments.StatementImportModels.Kind kind,UUID line,UUID related){var report=statementImports.detail(ADMIN,bank,50,0,batch,false);return new com.cbs.payments.StatementImportModels.Action(((Number)report.get("version")).longValue(),batch,kind,line,related,(String)report.get("reportHash"),"TEST/VERIFIED","Fictional independently verified statement");}
    private com.cbs.payments.PaymentModels.Order paymentOrder(FundingFixture f,String amount,String ref){return new com.cbs.payments.PaymentModels.Order(service.get(ADMIN,f.f().bank().id()).version(),f.account(),f.source().id(),amount,"254700000001","Fictional beneficiary",reference.get(ADMIN,f.f().bank().id()).businessDate(),ref,"Fictional sandbox payout");}
    private com.cbs.payments.PaymentModels.Statement paymentStatement(UUID bank,UUID payment,String amount,String ref,com.cbs.payments.PaymentModels.Outcome outcome){return new com.cbs.payments.PaymentModels.Statement(service.get(ADMIN,bank).version(),payment,ref,amount,"KES","254700000001",outcome,reference.get(ADMIN,bank).businessDate(),"PROVIDER/TEST","Fictional verified statement");}
    private com.cbs.payments.PaymentModels.Reconcile paymentResolution(UUID bank,UUID payment,UUID statement,com.cbs.payments.PaymentModels.Resolution outcome){return new com.cbs.payments.PaymentModels.Reconcile(service.get(ADMIN,bank).version(),payment,outcome,statement,"Fictional independent evidence review");}

    @Test void zeroBalanceClosurePreservesHistoryAndRechecksApprovalBeforeDelivery() throws Exception {
        var f=fundingFixture("closure-main");var bank=f.f().bank();var closed=com.cbs.accounts.AccountModels.State.CLOSED;
        String path="/api/v1/admin/tenants/"+bank.id()+"/accounts/"+f.account()+"/closure";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).eligible()).isTrue();
        var original=fund(f,"100.25");
        var holdRequest=approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.HOLD,"1",null,null));approveHttp(holdRequest.id());
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).blockers()).contains("ACCOUNT_OPERATION_PENDING");
        operations.deliver(holdRequest.id());var held=holdRequest.id();
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).blockers()).contains("FUNDING_BALANCE_NOT_ZERO","ACCOUNT_CLOSURE_HOLDS");
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),f.account(),closed)),"FUNDING_BALANCE_NOT_ZERO");
        try(var c=TestDatabases.bank("closure");var st=c.createStatement()){
            assertThatThrownBy(()->st.executeUpdate("UPDATE bank.account SET payload=jsonb_set(payload,'{state}','\"CLOSED\"') WHERE id='"+f.account()+"'")).isInstanceOf(java.sql.SQLException.class);
        }
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.RELEASE,"1",null,held));
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.FUNDING_REVERSAL,"100.25",null,original));
        var check=accounts.closure(ADMIN,bank.id(),f.account());assertThat(check.eligible()).isTrue();
        assertThat(new java.math.BigDecimal(check.posted())).isEqualByComparingTo("0");assertThat(new java.math.BigDecimal(check.ledgerBalance())).isEqualByComparingTo("0");
        mvc.perform(admin(get(path))).andExpect(status().isOk()).andExpect(jsonPath("$.blockers").isEmpty());
        assertCode(()->accounts.closure(ADMIN,UUID.randomUUID(),f.account()),"TENANT_NOT_FOUND");
        String maker="w03-maker-"+UUID.randomUUID(),checker="w03-checker-"+UUID.randomUUID();
        access.bootstrap(TestIdentity.ISSUER,maker);access.bootstrap(TestIdentity.ISSUER,checker);
        var makerActor=new Actor(TestIdentity.ISSUER,maker,true,UUID.randomUUID());var checkerActor=new Actor(TestIdentity.ISSUER,checker,true,UUID.randomUUID());
        var input=accountChange(f.f(),f.account(),closed);var key=UUID.randomUUID();
        var request=approvals.account(makerActor,key,bank.id(),input);
        assertThat(approvals.account(makerActor,key,bank.id(),input).id()).isEqualTo(request.id());
        assertCode(()->approvals.decide(makerActor,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self review"),"SELF_APPROVAL_FORBIDDEN");
        approvals.decide(checkerActor,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent closure review");
        jdbc.update("UPDATE platform.staff_identity SET active=false WHERE issuer=? AND subject=?",TestIdentity.ISSUER,maker);
        accounts.deliver(request.id());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.account_delivery WHERE request_id=?",String.class,request.id())).isEqualTo("APPROVAL_PARTICIPANT_DISABLED");
        jdbc.update("UPDATE platform.staff_identity SET active=true WHERE issuer=? AND subject=?",TestIdentity.ISSUER,maker);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{accounts.deliver(request.id());t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.account_delivery WHERE request_id=?",Boolean.class,request.id())).isFalse();
        jdbc.update("UPDATE platform.staff_identity SET active=false WHERE issuer=? AND subject IN (?,?)",TestIdentity.ISSUER,maker,checker);
        accounts.deliver(request.id());accounts.deliver(request.id());
        assertThat(accounts.detail(ADMIN,bank.id(),f.account()).definition().state()).isEqualTo(closed);
        assertThat(accounts.detail(ADMIN,bank.id(),f.account()).delivery()).isEqualTo("APPLIED");
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","AFTER-CLOSE")),"FUNDING_ACCOUNT_NOT_READY");
        try(var c=TestDatabases.bank("closure");var st=c.createStatement();var r=st.executeQuery("SELECT (SELECT count(*) FROM bank.account_event WHERE request_id='"+request.id()+"'),(SELECT count(*) FROM bank.funding),(SELECT count(*) FROM bank.customer_entry),(SELECT count(*) FROM bank.account_ledger)")){
            r.next();assertThat(r.getInt(1)).isEqualTo(1);assertThat(r.getInt(2)).isEqualTo(1);assertThat(r.getInt(3)).isEqualTo(1);assertThat(r.getInt(4)).isEqualTo(1);
            assertThatThrownBy(()->st.executeUpdate("INSERT INTO bank.journal_line(journal_id,line_number,ledger_id,side,amount) VALUES ('"+UUID.randomUUID()+"',1,'"+f.liability().id()+"','CREDIT',1)")).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Posting to a closed customer account");
            assertThatThrownBy(()->st.executeUpdate("UPDATE bank.account SET payload=jsonb_set(payload,'{state}','\"ACTIVE\"') WHERE id='"+f.account()+"'")).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test void positiveBalanceClosureSettlesWholeBalanceForfeitsCarryAndThenCloses() throws Exception {
        var f=fundingFixture("closure-settle");var bank=f.f().bank();var K=com.cbs.accounts.OperationModels.Kind.CLOSURE_SETTLEMENT;
        fund(f,"1000");
        // Fictional servicing at 1% p.a. leaves a sub-unit interest carry after one day.
        var expense=adjustmentLedger(bank,"CS_EXP",com.cbs.accounting.ChartModels.Category.EXPENSE);var payable=adjustmentLedger(bank,"CS_PAY",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var income=adjustmentLedger(bank,"CS_FEE",com.cbs.accounting.ChartModels.Category.INCOME);var tax=adjustmentLedger(bank,"CS_TAX",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var date=reference.get(ADMIN,bank.id()).businessDate();
        approveHttp(approvals.servicingPolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.servicing.ServicingModels.Policy(service.get(ADMIN,bank.id()).version(),f.f().product().id(),f.f().product().revision(),bank.legalEntities().getFirst().id(),date,"1",365,"0","10","0","HALF_EVEN",expense,payable,income,tax,"Fictional closure settlement policy")).id());
        var runInput=new com.cbs.servicing.ServicingModels.Run(service.get(ADMIN,bank.id()).version(),date,"Fictional servicing before closure");
        var run=approvals.servicingRun(ADMIN,UUID.randomUUID(),bank.id(),runInput);approveHttp(run.id());servicing.deliver(run.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.servicing_run WHERE request_id=?",Boolean.class,run.id())).isTrue();
        var day=days.dashboard(ADMIN,bank.id(),25,0);var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Close fictional serviced day"));approveHttp(closing.id());days.deliver(closing.id());
        var before=accounts.closure(ADMIN,bank.id(),f.account());
        assertThat(new java.math.BigDecimal(before.carry()).signum()).isNotZero();assertThat(before.settled()).isFalse();
        assertThat(before.blockers()).containsExactlyInAnyOrder("FUNDING_BALANCE_NOT_ZERO","ACCOUNT_CLOSURE_CARRY_SETTLEMENT");
        String balance=before.posted();assertThat(new java.math.BigDecimal(balance)).isGreaterThan(new java.math.BigDecimal("1000"));
        java.util.function.BiFunction<String,UUID,com.cbs.accounts.OperationModels.Change> settle=(amount,asset)->new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,bank.id()).version(),K,f.account(),null,asset,null,amount,reference.get(ADMIN,bank.id()).businessDate(),"CLOSE-"+UUID.randomUUID(),"Fictional customer closure request");
        // Mandatory, independently approved limit; missing policy denies the payout.
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),settle.apply(balance,f.source().id())),"TRANSACTION_LIMIT_REQUIRED");
        approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),"CLOSURE_SETTLEMENT","999999999999999999","999999999999999999","Fictional integration test limit")).id());
        // Ordinary withdrawals still protect the product minimum; only the final settlement may take it.
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,bank.id()).version(),com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,f.account(),null,f.source().id(),null,balance,reference.get(ADMIN,bank.id()).businessDate(),"W-ALL","Fictional full withdrawal")),"INSUFFICIENT_AVAILABLE_BALANCE");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),settle.apply(balance,null)),"OPERATION_FIELDS_INVALID");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),settle.apply(new java.math.BigDecimal(balance).subtract(new java.math.BigDecimal("0.01")).toPlainString(),f.source().id())),"CLOSURE_SETTLEMENT_AMOUNT_MISMATCH");
        var hold=postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.HOLD,"1",null,null));
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),settle.apply(balance,f.source().id())),"ACCOUNT_CLOSURE_HOLDS");
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.RELEASE,"1",null,hold));
        // Independent approval, the bank-side guard against a changed balance, and lost-acknowledgement recovery.
        var key=UUID.randomUUID();var request=approvals.operation(ADMIN,key,bank.id(),settle.apply(balance,f.source().id()));
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(request.id());
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),settle.apply(balance,f.source().id())),"ACCOUNT_OPERATION_PENDING");
        try(var c=TestDatabases.bank("settle");var st=c.createStatement()){
            assertThatThrownBy(()->st.executeUpdate("INSERT INTO bank.closure_settlement(request_id,account_id,amount,forfeited_carry) VALUES ('"+UUID.randomUUID()+"','"+f.account()+"',1,0)")).isInstanceOf(SQLException.class);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{operations.deliver(request.id());t.setRollbackOnly();});
        operations.deliver(request.id());operations.deliver(request.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.account_operation WHERE request_id=?",Boolean.class,request.id())).isTrue();
        var position=operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position();
        assertThat(new java.math.BigDecimal(position.posted())).isEqualByComparingTo("0");assertThat(position.reconciled()).isTrue();
        var after=accounts.closure(ADMIN,bank.id(),f.account());
        assertThat(after.settled()).isTrue();assertThat(after.blockers()).isEmpty();assertThat(after.carry()).isEqualTo(before.carry());
        try(var c=TestDatabases.bank("settle");var st=c.createStatement()){
            try(var r=st.executeQuery("SELECT count(*),sum(amount),min(forfeited_carry) FROM bank.closure_settlement")){r.next();assertThat(r.getInt(1)).isEqualTo(1);assertThat(r.getBigDecimal(2)).isEqualByComparingTo(balance);assertThat(r.getBigDecimal(3)).isEqualByComparingTo(before.carry());}
            try(var r=st.executeQuery("SELECT coalesce(debit,0)-coalesce(credit,0) FROM bank.ledger_balance WHERE ledger_id='"+f.source().id()+"'")){r.next();assertThat(r.getBigDecimal(1)).isEqualByComparingTo(new java.math.BigDecimal("1000").subtract(new java.math.BigDecimal(balance)));}
            assertThatThrownBy(()->st.executeUpdate("UPDATE bank.closure_settlement SET forfeited_carry=0")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->st.executeUpdate("INSERT INTO bank.journal_line(journal_id,line_number,ledger_id,side,amount) VALUES ('"+UUID.randomUUID()+"',1,'"+f.liability().id()+"','CREDIT',1)")).isInstanceOf(SQLException.class).hasMessageContaining("pending closure");
        }
        // Only the closure may follow: no new money, holds or second settlement.
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"10","AFTER-SETTLEMENT")),"ACCOUNT_PENDING_CLOSURE");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.HOLD,"1",null,null)),"ACCOUNT_PENDING_CLOSURE");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),settle.apply("1",f.source().id())),"ACCOUNT_PENDING_CLOSURE");
        var next=servicing.preview(ADMIN,bank.id(),new com.cbs.servicing.ServicingModels.Run(service.get(ADMIN,bank.id()).version(),reference.get(ADMIN,bank.id()).businessDate(),"Settled accounts are excluded"));
        assertThat(next.items()).noneMatch(i->i.account().id().equals(f.account()));
        var closure=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),f.account(),com.cbs.accounts.AccountModels.State.CLOSED));approveHttp(closure.id());accounts.deliver(closure.id());
        assertThat(accounts.detail(ADMIN,bank.id(),f.account()).definition().state()).isEqualTo(com.cbs.accounts.AccountModels.State.CLOSED);
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).items()).anyMatch(i->i.entry().change().kind()==K&&i.status().equals("POSTED"));
    }

    private record FundingFixture(AccountFixture f,UUID account,com.cbs.accounting.LedgerModels.Definition source,com.cbs.accounting.LedgerModels.Definition liability) {}
    private FundingFixture fundingFixture(String binding)throws Exception{
        var f=accountFixture(binding);var bank=f.bank();var opening=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f,null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approveHttp(opening.id());accounts.deliver(opening.id());
        UUID account=accounts.list(ADMIN,bank.id(),"",null,25,0).items().getFirst().id();
        var chartRequest=approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartChange(bank.id(),null,"1100","Settlement asset",com.cbs.accounting.ChartModels.Category.ASSET,com.cbs.accounting.ChartModels.Kind.POSTING,null,true));approveHttp(chartRequest.id());
        var asset=chart.list(ADMIN,bank.id(),"1100",null,null,null,25,0).items().getFirst();
        var sourceRequest=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,"SETTLEMENT",asset.id(),"Received funds",true));approveHttp(sourceRequest.id());ledger.deliver(sourceRequest.id());
        var liabilityRequest=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,"CUSTOMER_001",f.product().liabilityAccountId(),"Customer liability",true));approveHttp(liabilityRequest.id());ledger.deliver(liabilityRequest.id());
        var list=ledger.list(ADMIN,bank.id(),"",null,null,25,0).items();
        // Explicit, independently approved test-only policies; never copied to a live bank.
        for(String operation:List.of("FUNDING","TRANSFER","WITHDRAWAL","FUNDING_REVERSAL","HOLD")){
            assertCode(()->com.cbs.access.TransactionLimits.check(jdbc,bank.id(),f.product().currency(),operation,reference.get(ADMIN,bank.id()).businessDate(),java.math.BigDecimal.ONE),"TRANSACTION_LIMIT_REQUIRED");
            var policy=new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.product().currency(),operation,"999999999999999999","999999999999999999","Fictional integration test limit");
            var request=approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),policy);
            assertCode(()->com.cbs.access.TransactionLimits.check(jdbc,bank.id(),f.product().currency(),operation,reference.get(ADMIN,bank.id()).businessDate(),java.math.BigDecimal.ONE),"TRANSACTION_LIMIT_REQUIRED");
            approveHttp(request.id());
        }
        return new FundingFixture(f,account,list.stream().filter(x->x.definition().code().equals("SETTLEMENT")).findFirst().orElseThrow().definition(),list.stream().filter(x->x.definition().code().equals("CUSTOMER_001")).findFirst().orElseThrow().definition());
    }
    private com.cbs.accounts.FundingModels.Change fundingChange(FundingFixture f,String amount,String ref){var bank=f.f().bank();return new com.cbs.accounts.FundingModels.Change(service.get(ADMIN,bank.id()).version(),f.account(),f.source().id(),f.liability().id(),amount,reference.get(ADMIN,bank.id()).businessDate(),ref,"Verified fictional receipt");}
    @Test void fundingPostsExactCustomerAndGeneralLedgerBalances()throws Exception{
        var f=fundingFixture("funding-main");var bank=f.f().bank();var input=fundingChange(f,"9007199254740993.17","RECEIPT-001");UUID key=UUID.randomUUID();var r=approvals.funding(ADMIN,key,bank.id(),input);
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("0");
        assertCode(()->approvals.decide(ADMIN,r.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");approveHttp(r.id());
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("0");
        assertCode(()->accounts.deliver(r.id()),"FUNDING_MANAGED");assertCode(()->journals.deliver(r.id()),"FUNDING_MANAGED");
        funding.deliver(r.id());var balance=funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance();assertThat(balance.postedBalance()).isEqualTo("9007199254740993.17");assertThat(balance.reconciled()).isTrue();assertThat(balance.ledgerBalance()).isEqualTo(balance.postedBalance());
        assertThat(accounts.detail(ADMIN,bank.id(),f.account()).definition().state()).isEqualTo(com.cbs.accounts.AccountModels.State.ACTIVE);
        assertThat(approvals.funding(ADMIN,key,bank.id(),input).id()).isEqualTo(r.id());
        var topup=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"0.10","RECEIPT-002"));approveHttp(topup.id());funding.deliver(topup.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("9007199254740993.27");
        var balances=journals.balances(ADMIN,bank.id(),25,0).items();assertThat(balances.stream().filter(b->b.ledgerId().equals(f.source().id())).findFirst().orElseThrow().debit()).isEqualTo("9007199254740993.27");
        assertThat(journals.list(ADMIN,bank.id(),25,0).items().getFirst().funding()).isTrue();
        var bypass=new com.cbs.accounting.JournalModels.Entry(UUID.randomUUID(),bank.id(),"DIRECT-BYPASS","Must require subledger",input.businessDate(),bank.legalEntities().getFirst().id(),"INR",List.of(new com.cbs.accounting.JournalModels.Line(f.source().id(),com.cbs.accounting.JournalModels.Side.DEBIT,"1"),new com.cbs.accounting.JournalModels.Line(f.liability().id(),com.cbs.accounting.JournalModels.Side.CREDIT,"1")),null);
        assertCode(()->bankJournals.post(bank,new com.cbs.accounting.JournalModels.Dispatch(bypass,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),UUID.randomUUID())),"JOURNAL_DATABASE_UNAVAILABLE");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("9007199254740993.27");
        assertCode(()->approvals.funding(ADMIN,key,bank.id(),fundingChange(f,"101","RECEIPT-001")),"IDEMPOTENCY_CONFLICT");
        assertCode(()->approvals.reverseJournal(ADMIN,UUID.randomUUID(),bank.id(),r.id(),new com.cbs.accounting.JournalModels.Reversal(service.get(ADMIN,bank.id()).version(),"REV-FUND","Must not bypass subledger")),"FUNDING_MANAGED");
        assertCode(()->approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),journalChange(bank,List.of(f.source(),f.liability()),"1")),"FUNDING_MANAGED");
        assertCode(()->approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,f.liability().id(),f.liability().code(),f.liability().chartAccountId(),"Rename",true)),"FUNDING_MANAGED");
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),f.account(),com.cbs.accounts.AccountModels.State.CLOSED)),"FUNDING_BALANCE_NOT_ZERO");
        try(var c=TestDatabases.bank("funding");var st=c.createStatement()){
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.funding")).isInstanceOf(SQLException.class);
            try(var rs=st.executeQuery("SELECT count(*) FROM bank.journal")){rs.next();assertThat(rs.getInt(1)).isEqualTo(2);}
        }
    }
    @Test void fundingValidatesMinimumScopeDatesStatusAndApprovalStaleness()throws Exception{
        var f=fundingFixture("funding-guards");var bank=f.f().bank();String path="/api/v1/admin/tenants/"+bank.id()+"/funding";
        mvc.perform(get(path+"/"+f.account())).andExpect(status().isUnauthorized());mvc.perform(get(path+"/"+f.account()).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM platform.transaction_limit WHERE tenant_id=? AND operation='FUNDING'",bank.id());
        mvc.perform(admin(get("/api/v1/admin/tenants/"+bank.id()+"/transaction-limits"))).andExpect(status().isOk()).andExpect(jsonPath("$.missing[?(@.operation == 'FUNDING')].currency").value(org.hamcrest.Matchers.hasItem("INR")));
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","NO-POLICY")),"TRANSACTION_LIMIT_REQUIRED");
        var limitRequest=approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),"FUNDING","1000","10000","Fictional funding policy"));
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","PENDING-POLICY")),"TRANSACTION_LIMIT_REQUIRED");
        approveHttp(limitRequest.id());
        mvc.perform(admin(get("/api/v1/admin/tenants/"+bank.id()+"/transaction-limits"))).andExpect(status().isOk()).andExpect(jsonPath("$.missing[?(@.operation == 'FUNDING')]").isEmpty());
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"99.99","SMALL")),"FUNDING_OPENING_MINIMUM");
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100.001","SCALE")),"JOURNAL_AMOUNT_INVALID");
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"0","ZERO")),"JOURNAL_AMOUNT_INVALID");
        var base=fundingChange(f,"100","DATE");var wrongDate=new com.cbs.accounts.FundingModels.Change(base.expectedVersion(),base.accountId(),base.sourceLedgerId(),base.liabilityLedgerId(),base.amount(),base.businessDate().minusDays(1),base.reference(),base.reason());
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),wrongDate),"JOURNAL_BUSINESS_DATE_INVALID");
        var stale=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","STALE"));
        var freeze=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),f.account(),com.cbs.accounts.AccountModels.State.FROZEN));approveHttp(freeze.id());accounts.deliver(freeze.id());
        assertThat(approvals.decide(CHECKER,stale.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Stale").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","FROZEN")),"FUNDING_ACCOUNT_NOT_READY");
        var unfreeze=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),f.account(),com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approveHttp(unfreeze.id());accounts.deliver(unfreeze.id());
        var r=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","GOOD"));approveHttp(r.id());
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","PENDING")),"FUNDING_ACCOUNT_NOT_READY");
        var day=days.dashboard(ADMIN,bank.id(),25,0);assertThat(day.blockers()).contains("DAY_CLOSE_PENDING_DELIVERIES");
        funding.deliver(r.id());assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"100","GOOD")),"JOURNAL_REFERENCE_IN_USE");
        var foreign=register("funding-isolation");assertCode(()->funding.dashboard(ADMIN,foreign.id(),f.account(),25,0),"ACCOUNT_NOT_FOUND");assertCode(()->funding.retry(ADMIN,foreign.id(),r.id()),"APPROVAL_NOT_FOUND");
    }
    @Test void fundingRecoversLostAcknowledgementAndConcurrentRetries()throws Exception{
        var f=fundingFixture("funding-recovery");var bank=f.f().bank();var input=fundingChange(f,"123.45","RECOVERY");UUID key=UUID.randomUUID();CyclicBarrier start=new CyclicBarrier(2);UUID id;
        try(var pool=Executors.newFixedThreadPool(2)){Callable<UUID> task=()->{start.await();return approvals.funding(ADMIN,key,bank.id(),input).id();};var results=pool.invokeAll(List.of(task,task));id=results.getFirst().get();assertThat(id).isEqualTo(results.getLast().get());}
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{approvals.decide(CHECKER,id,com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Rollback approval");t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.funding_delivery WHERE tenant_id=?",Long.class,bank.id())).isZero();approveHttp(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{funding.deliver(id);t.setRollbackOnly();});
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).items().getFirst().status()).isEqualTo("AWAITING_POSTING");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("123.45");
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.of(()->{funding.retry(CHECKER,bank.id(),id);return true;},()->{funding.retry(CHECKER,bank.id(),id);return true;}));for(var result:results)result.get();}
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).items().getFirst().status()).isEqualTo("POSTED");
        try(var c=TestDatabases.bank("fundrecovery");var st=c.createStatement();var rs=st.executeQuery("SELECT (SELECT count(*) FROM bank.funding) f,(SELECT count(*) FROM bank.journal) j")){rs.next();assertThat(rs.getInt("f")).isEqualTo(1);assertThat(rs.getInt("j")).isEqualTo(1);}
    }
    @Test void fundingBankFailureRollsBackJournalBalanceAndActivation()throws Exception{
        var f=fundingFixture("funding-failure");var bank=f.f().bank();var r=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"150","FAILURE"));approveHttp(r.id());
        try(var c=TestDatabases.bank("fundfailure");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_funding BEFORE INSERT ON bank.funding FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        funding.deliver(r.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).items().getFirst().failureCode()).isEqualTo("FUNDING_DATABASE_UNAVAILABLE");
        try(var c=TestDatabases.bank("fundfailure");var st=c.createStatement()){
            try(var rs=st.executeQuery("SELECT (SELECT count(*) FROM bank.journal) j,(SELECT count(*) FROM bank.ledger_balance) b,(SELECT payload->>'state' FROM bank.account LIMIT 1) state")){rs.next();assertThat(rs.getInt("j")).isZero();assertThat(rs.getInt("b")).isZero();assertThat(rs.getString("state")).isEqualTo("AWAITING_FUNDING");}
            st.execute("DROP TRIGGER fail_funding ON bank.funding");
        }
        funding.retry(CHECKER,bank.id(),r.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("150");
        try(var c=TestDatabases.bank("fundfailure");var st=c.createStatement()){st.executeUpdate("UPDATE bank.ledger_balance SET credit=credit+1 WHERE ledger_id='"+f.liability().id()+"'");}
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().reconciled()).isFalse();
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"1","DRIFT")),"FUNDING_UNRECONCILED");assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("DAY_CLOSE_UNRECONCILED");
    }

    private com.cbs.accounts.OperationModels.Change operationChange(FundingFixture f,com.cbs.accounts.OperationModels.Kind kind,String amount,UUID target,UUID original){return new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,f.f().bank().id()).version(),kind,f.account(),target,kind==com.cbs.accounts.OperationModels.Kind.WITHDRAWAL?f.source().id():null,original,amount,reference.get(ADMIN,f.f().bank().id()).businessDate(),"OP-"+UUID.randomUUID(),"Fictional operation evidence");}
    private UUID postOperation(FundingFixture f,com.cbs.accounts.OperationModels.Change input)throws Exception{var r=approvals.operation(ADMIN,UUID.randomUUID(),f.f().bank().id(),input);approveHttp(r.id());operations.deliver(r.id());assertThat(operations.dashboard(ADMIN,f.f().bank().id(),f.account(),25,0).items().getFirst().failureCode()).isNull();return r.id();}
    private UUID fund(FundingFixture f,String amount)throws Exception{var r=approvals.funding(ADMIN,UUID.randomUUID(),f.f().bank().id(),fundingChange(f,amount,"F-"+UUID.randomUUID()));approveHttp(r.id());funding.deliver(r.id());return r.id();}
    private com.cbs.access.AccessModels.Staff branchStaff(UUID bank,UUID branch,String subject,com.cbs.access.AccessModels.BranchRole role){
        var staff=access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput(subject,subject,null,"Test branch onboarding"));
        service.setMembership(ADMIN,bank,new MembershipRequest(service.get(ADMIN,bank).version(),subject,BankRole.BANK_OPERATOR,true,"Assign bank"));
        var b=branchService.list(ADMIN,bank,100,0).items().stream().filter(x->x.id().equals(branch)).findFirst().orElseThrow();
        branchService.grant(ADMIN,bank,branch,new com.cbs.access.AccessModels.BranchGrant(b.version(),staff.id(),role,true,"Assign branch"));return staff;
    }
    @Test void branchOperationsRequireScopedRolesMfaAndApprovedLimits()throws Exception{
        var f=fundingFixture("branch-operations");var bank=f.f().bank();fund(f,"1000");
        var branch=branchService.create(ADMIN,bank.id(),new com.cbs.access.AccessModels.BranchInput(bank.legalEntities().getFirst().id(),"OPS","Operations branch","Open branch"));
        var teller=branchStaff(bank.id(),branch.id(),"ops-teller",com.cbs.access.AccessModels.BranchRole.TELLER);
        var manager=branchStaff(bank.id(),branch.id(),"ops-manager",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var assignment=approvals.assignment(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Assignment(service.get(ADMIN,bank.id()).version(),f.account(),branch.id(),"Assign operational ownership"));approveHttp(assignment.id());
        var actor=new Actor(TestIdentity.ISSUER,teller.subject(),false,UUID.randomUUID());var reviewer=new Actor(TestIdentity.ISSUER,manager.subject(),false,UUID.randomUUID());
        var kind=com.cbs.accounts.OperationModels.Kind.HOLD;
        assertCode(()->approvals.operation(actor,UUID.randomUUID(),bank.id(),operationChange(f,kind,"100",null,null)),"BRANCH_LIMIT_REQUIRED");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,kind,"100",null,null)),"BANK_ACCESS_DENIED");
        for(UUID staff:java.util.Arrays.asList(null,teller.id())){
            var policy=new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,bank.id()).version(),branch.id(),staff,"INR","HOLD",staff==null?"500":"300",staff==null?"600":"350","Approve fixture caps");
            var request=approvals.branchLimit(ADMIN,UUID.randomUUID(),bank.id(),policy);approveHttp(request.id());
        }
        String path="/api/v1/banks/"+bank.id()+"/branches/"+branch.id()+"/operations";
        String token=TestIdentity.token(teller.subject(),false);String managerToken=TestIdentity.token(manager.subject(),false);
        mvc.perform(get(path+"/accounts").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(f.account().toString()));
        String weak=TestIdentity.token(TestIdentity.KEY,TestIdentity.ISSUER,TestIdentity.AUDIENCE,teller.subject(),false,Instant.now().plusSeconds(300),Instant.now(),Instant.now(),UUID.randomUUID().toString(),false);
        mvc.perform(get(path+"/accounts").header("Authorization","Bearer "+weak)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        assertCode(()->approvals.operation(actor,UUID.randomUUID(),bank.id(),operationChange(f,kind,"301",null,null)),"TRANSACTION_LIMIT_EXCEEDED");
        var input=operationChange(f,kind,"100",null,null);UUID key=UUID.randomUUID();
        var result=mvc.perform(post(path+"/requests").header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var request=json.readValue(result,com.cbs.approvals.ApprovalModels.Request.class);
        mvc.perform(post(path+"/requests/"+request.id()+"/approve").header("Authorization","Bearer "+token).contentType("application/json").content("{\"reason\":\"Own approval\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path+"/requests/"+request.id()+"/approve").header("Authorization","Bearer "+managerToken).contentType("application/json").content("{\"reason\":\"Independent review\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        operations.deliver(request.id());
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().held()).isEqualTo("100");
        assertThat(approvals.operation(actor,key,bank.id(),input).id()).isEqualTo(request.id());
        var release=approvals.operation(actor,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.RELEASE,"100",null,request.id()));
        approvals.decide(reviewer,release.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Release reviewed");operations.deliver(release.id());
        assertCode(()->approvals.operation(actor,UUID.randomUUID(),bank.id(),operationChange(f,kind,"251",null,null)),"DAILY_LIMIT_EXCEEDED");
        assertThatThrownBy(()->jdbc.update("UPDATE platform.operation_scope SET staff_id=? WHERE request_id=?",manager.id(),request.id())).isInstanceOf(DataAccessException.class);
        mvc.perform(get("/api/v1/banks/"+bank.id()+"/branches/"+UUID.randomUUID()+"/operations/requests/"+request.id()).header("Authorization","Bearer "+managerToken)).andExpect(status().isForbidden());
        var current=branchService.list(ADMIN,bank.id(),100,0).items().getFirst();branchService.grant(ADMIN,bank.id(),branch.id(),new com.cbs.access.AccessModels.BranchGrant(current.version(),teller.id(),com.cbs.access.AccessModels.BranchRole.TELLER,false,"Revoke teller"));
        mvc.perform(get(path+"/accounts").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        assertCode(()->approvals.operation(actor,key,bank.id(),input),"BRANCH_ACCESS_DENIED");
    }
    @Test void operationsProtectHoldsMinimumAndRecoverWithdrawals()throws Exception{
        var f=fundingFixture("operations-main");var bank=f.f().bank();fund(f,"1000.17");
        String path="/api/v1/admin/tenants/"+bank.id()+"/account-operations";
        mvc.perform(get(path+"/"+f.account())).andExpect(status().isUnauthorized());mvc.perform(get(path+"/"+f.account()).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(admin(post(path)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        var kind=com.cbs.accounts.OperationModels.Kind.HOLD;var hold=postOperation(f,operationChange(f,kind,"700",null,null));
        var p=operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position();assertThat(p.posted()).isEqualTo("1000.17");assertThat(p.held()).isEqualTo("700");assertThat(p.available()).isEqualTo("300.17");assertThat(p.withdrawable()).isEqualTo("290.17");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"290.18",null,null)),"INSUFFICIENT_AVAILABLE_BALANCE");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,kind,"0.001",null,null)),"JOURNAL_AMOUNT_INVALID");
        var policy=new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),"WITHDRAWAL","290.17","300","Set approved withdrawal caps");
        var limitRequest=approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),policy);
        assertCode(()->approvals.decide(ADMIN,limitRequest.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own policy"),"SELF_APPROVAL_FORBIDDEN");
        approveHttp(limitRequest.id());
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"290.18",null,null)),"TRANSACTION_LIMIT_EXCEEDED");
        var input=operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"290.17",null,null);UUID key=UUID.randomUUID();var request=approvals.operation(ADMIN,key,bank.id(),input);
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");approveHttp(request.id());
        assertCode(()->com.cbs.access.TransactionLimits.check(jdbc,bank.id(),f.f().product().currency(),"WITHDRAWAL",input.businessDate(),new java.math.BigDecimal("9.84")),"DAILY_LIMIT_EXCEEDED");
        assertCode(()->journals.deliver(request.id()),"FUNDING_MANAGED");assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"1","PENDING")),"ACCOUNT_OPERATION_PENDING");
        assertCode(()->approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),f.account(),com.cbs.accounts.AccountModels.State.FROZEN)),"ACCOUNT_OPERATION_PENDING");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("DAY_CLOSE_PENDING_DELIVERIES");
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{operations.deliver(request.id());t.setRollbackOnly();});
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("710");
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.of(()->{operations.retry(CHECKER,bank.id(),request.id());return true;},()->{operations.retry(CHECKER,bank.id(),request.id());return true;}));for(var result:results)result.get();}
        assertThat(approvals.operation(ADMIN,key,bank.id(),input).id()).isEqualTo(request.id());
        var release=operationChange(f,com.cbs.accounts.OperationModels.Kind.RELEASE,"700",null,hold);postOperation(f,release);
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().held()).isEqualTo("0");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.RELEASE,"700",null,hold)),"HOLD_NOT_ACTIVE");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("710");assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("DAY_CLOSE_UNRECONCILED");
        try(var c=TestDatabases.bank("operations");var st=c.createStatement()){
            try(var r=st.executeQuery("SELECT count(*) FROM bank.customer_entry")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.customer_entry")).isInstanceOf(SQLException.class);
        }
    }
    @Test void operationsTransferAndReverseFundingWithAtomicRecovery()throws Exception{
        var f=fundingFixture("operations-transfer");var bank=f.f().bank();fund(f,"1000");var reversible=fund(f,"200");
        var opening=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approveHttp(opening.id());accounts.deliver(opening.id());
        var target=accounts.list(ADMIN,bank.id(),"",null,25,0).items().stream().filter(x->!x.id().equals(f.account())).findFirst().orElseThrow().id();
        var lr=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,"CUSTOMER_002",f.f().product().liabilityAccountId(),"Second customer",true));approveHttp(lr.id());ledger.deliver(lr.id());var liability=ledger.list(ADMIN,bank.id(),"CUSTOMER_002",null,null,25,0).items().getFirst().definition();var second=new FundingFixture(f.f(),target,f.source(),liability);fund(second,"100");
        var input=operationChange(f,com.cbs.accounts.OperationModels.Kind.TRANSFER,"300.11",target,null);var r=approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(r.id());
        try(var c=TestDatabases.bank("optransfer");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_operation BEFORE INSERT ON bank.customer_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        operations.deliver(r.id());assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).items().getFirst().failureCode()).isEqualTo("OPERATION_DATABASE_UNAVAILABLE");
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("1200");assertThat(operations.dashboard(ADMIN,bank.id(),target,25,0).position().posted()).isEqualTo("100");
        try(var c=TestDatabases.bank("optransfer");var st=c.createStatement()){st.execute("DROP TRIGGER fail_operation ON bank.customer_entry");}
        operations.retry(CHECKER,bank.id(),r.id());assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("899.89");assertThat(operations.dashboard(ADMIN,bank.id(),target,25,0).position().posted()).isEqualTo("400.11");
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.FUNDING_REVERSAL,"200",null,reversible));
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("699.89");
        var date=reference.get(ADMIN,bank.id()).businessDate();var statement=statements.read(ADMIN,bank.id(),f.account(),date,date,2,2);assertThat(statement.openingBalance()).isEqualTo("0");assertThat(statement.closingBalance()).isEqualTo("699.89");assertThat(statement.total()).isEqualTo(4);assertThat(statement.items()).hasSize(2);assertThat(statement.items().getLast().balance()).isEqualTo("699.89");
        String exportPath="/api/v1/admin/tenants/"+bank.id()+"/accounts/"+f.account()+"/statement/export?from="+date+"&to="+date;
        mvc.perform(get(exportPath)).andExpect(status().isUnauthorized());
        mvc.perform(admin(get(exportPath))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(org.hamcrest.Matchers.containsString("699.89")));
        var empty=statements.read(ADMIN,bank.id(),f.account(),date.plusDays(1),date.plusDays(1),25,0);assertThat(empty.items()).isEmpty();assertThat(empty.openingBalance()).isEqualTo("699.89");assertThat(empty.closingBalance()).isEqualTo("699.89");
        assertCode(()->statements.read(ADMIN,bank.id(),f.account(),date,date.plusDays(367),25,0),"STATEMENT_DATE_RANGE");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.FUNDING_REVERSAL,"200",null,reversible)),"JOURNAL_ALREADY_REVERSED");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.TRANSFER,"1",f.account(),null)),"OPERATION_FIELDS_INVALID");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("DAY_CLOSE_UNRECONCILED");
        for(String op:List.of("WITHDRAWAL_REVERSAL","TRANSFER_REVERSAL"))approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"INR",op,"1000","5000","Fictional reversal cap")).id());
        var held=postOperation(second,operationChange(second,com.cbs.accounts.OperationModels.Kind.HOLD,"200",null,null));
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(second,com.cbs.accounts.OperationModels.Kind.TRANSFER_REVERSAL,"300.11",f.account(),r.id())),"INSUFFICIENT_AVAILABLE_BALANCE");
        postOperation(second,operationChange(second,com.cbs.accounts.OperationModels.Kind.RELEASE,"200",null,held));
        var undo=approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(second,com.cbs.accounts.OperationModels.Kind.TRANSFER_REVERSAL,"300.11",f.account(),r.id()));approveHttp(undo.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{operations.deliver(undo.id());t.setRollbackOnly();});operations.deliver(undo.id());operations.deliver(undo.id());
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("1000");
        assertThat(operations.dashboard(ADMIN,bank.id(),target,25,0).position().posted()).isEqualTo("100");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(second,com.cbs.accounts.OperationModels.Kind.TRANSFER_REVERSAL,"300.11",f.account(),r.id())),"JOURNAL_ALREADY_REVERSED");
        var payout=postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"100",null,null));
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL_REVERSAL,"99",null,payout)),"OPERATION_REVERSAL_INVALID");
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL_REVERSAL,"100",null,payout));
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("1000");
    }

    private Tenant chartBank(String binding) {
        Tenant bank=register(binding);
        // Chart definitions live in the control store; no bank database is accessed by these tests.
        jdbc.update("UPDATE platform.tenant SET status='ACTIVE' WHERE id=?",bank.id());
        return service.get(ADMIN,bank.id());
    }
    private com.cbs.accounting.ChartModels.Change chartChange(UUID bank,UUID id,String code,String name,
            com.cbs.accounting.ChartModels.Category category,com.cbs.accounting.ChartModels.Kind kind,UUID parent,boolean active) {
        return new com.cbs.accounting.ChartModels.Change(service.get(ADMIN,bank).version(),id,code,name,"",category,
                com.cbs.accounting.ChartModels.Balance.DEBIT,kind,parent,active,"Configure chart account");
    }

    private com.cbs.reference.ReferenceModels.Change settingsChange(com.cbs.reference.ReferenceModels.Settings s) {
        return new com.cbs.reference.ReferenceModels.Change(s.version(),s.currencies(),s.languages(),s.defaultLanguage(),s.businessTimezone(),s.businessDate(),s.weekendDays(),s.holidays(),"Review bank settings");
    }
    @Autowired com.cbs.access.BankDuties duties;
    private record DutyUser(Actor actor,UUID id){String subject(){return actor.subject();}}
    private DutyUser dutyUser(String subject){var staff=access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput(subject,subject,subject+"@example.test","Fictional duty segregation test"));return new DutyUser(new Actor(ADMIN.issuer(),subject,true,UUID.randomUUID()),staff.id());}
    private com.cbs.access.BankDuties.Grant dutyGrant(UUID bank,DutyUser staff,boolean active,List<com.cbs.access.BankDuties.Duty> submit,List<com.cbs.access.BankDuties.Duty> approve,boolean audit,Instant expires){
        return new com.cbs.access.BankDuties.Grant(service.get(ADMIN,bank).version(),staff.id(),active,submit,approve,audit,expires,"Fictional duty assignment");
    }
    private void grantDuties(Actor maker,Actor checker,UUID bank,com.cbs.access.BankDuties.Grant grant){
        var request=approvals.bankDuty(maker,UUID.randomUUID(),bank,grant);
        assertThat(approvals.decide(checker,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verified duty assignment").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.APPROVED);
    }
    @Test void bankDutiesSegregatePlatformAdministratorsWhenEnforced()throws Exception{
        var bid=provision(register("bank-duties")).id();
        var APPROVE=com.cbs.approvals.ApprovalModels.Decision.APPROVE;var APPROVED=com.cbs.approvals.ApprovalModels.Status.APPROVED;var INBOX=com.cbs.approvals.ApprovalModels.Scope.INBOX;
        var ACCESS=List.of(com.cbs.access.BankDuties.Duty.ACCESS_ADMINISTRATION);var CONFIG=List.of(com.cbs.access.BankDuties.Duty.BANK_CONFIGURATION);List<com.cbs.access.BankDuties.Duty> none=List.of();
        DutyUser accessA=dutyUser("duty-access-a"),accessB=dutyUser("duty-access-b"),maker=dutyUser("duty-config-maker"),checker=dutyUser("duty-config-checker"),auditor=dutyUser("duty-auditor"),outsider=dutyUser("duty-outsider"),temporary=dutyUser("duty-temporary"),mixed=dutyUser("duty-mixed");
        java.util.function.Supplier<com.cbs.access.TransactionLimits.Change> limit=()->new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bid).version(),"INR","FUNDING","1000","5000","Fictional duty-test funding limit");
        java.util.function.Function<Boolean,com.cbs.access.BankDuties.Policy> policy=on->new com.cbs.access.BankDuties.Policy(service.get(ADMIN,bid).version(),on,on?"Enforce duty segregation for the pilot":"Switch duty segregation off");

        // Not enforced: platform administrators keep today's behaviour, and enforcement needs two access administrators.
        assertThat(approvals.decide(CHECKER,approvals.limits(ADMIN,UUID.randomUUID(),bid,limit.get()).id(),APPROVE,"Before enforcement").status()).isEqualTo(APPROVED);
        assertCode(()->approvals.bankDutyPolicy(ADMIN,UUID.randomUUID(),bid,policy.apply(true)),"BANK_DUTY_LOCKOUT");
        assertCode(()->approvals.bankDuty(ADMIN,UUID.randomUUID(),bid,dutyGrant(bid,maker,true,List.of(com.cbs.access.BankDuties.Duty.FINANCE,com.cbs.access.BankDuties.Duty.FINANCE),none,false,null)),"BANK_DUTY_GRANT_INVALID");
        assertCode(()->approvals.bankDuty(ADMIN,UUID.randomUUID(),bid,dutyGrant(bid,maker,true,CONFIG,none,false,Instant.now().minusSeconds(5))),"BANK_DUTY_GRANT_INVALID");
        assertCode(()->approvals.bankDuty(ADMIN,UUID.randomUUID(),bid,dutyGrant(bid,maker,true,none,none,false,null)),"BANK_DUTY_GRANT_INVALID");
        var own=approvals.bankDuty(ADMIN,UUID.randomUUID(),bid,dutyGrant(bid,accessA,true,ACCESS,ACCESS,false,null));
        assertCode(()->approvals.decide(accessA.actor(),own.id(),APPROVE,"Own duties"),"BANK_DUTY_SELF_GRANT_APPROVAL");
        assertThat(approvals.decide(CHECKER,own.id(),APPROVE,"Independent duty review").status()).isEqualTo(APPROVED);
        grantDuties(ADMIN,CHECKER,bid,dutyGrant(bid,accessB,true,ACCESS,ACCESS,false,null));
        grantDuties(ADMIN,CHECKER,bid,dutyGrant(bid,maker,true,CONFIG,none,false,null));
        grantDuties(ADMIN,CHECKER,bid,dutyGrant(bid,checker,true,none,CONFIG,false,null));
        grantDuties(ADMIN,CHECKER,bid,dutyGrant(bid,auditor,true,none,none,true,null));

        // Enforcement is itself maker-checker, here through HTTP.
        var enable=mvc.perform(post("/api/v1/admin/tenants/"+bid+"/bank-duties/policy").header("Authorization","Bearer "+TestIdentity.token(accessA.subject(),true))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(policy.apply(true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        UUID enableId=json.readValue(enable.getResponse().getContentAsString(),com.cbs.approvals.ApprovalModels.Request.class).id();
        assertThat(approvals.decide(accessB.actor(),enableId,APPROVE,"Segregation approved").status()).isEqualTo(APPROVED);
        assertThat(duties.enforced(bid)).isTrue();

        // Platform administration alone no longer reaches this bank's work; submit and approve are separate duties.
        assertCode(()->approvals.limits(ADMIN,UUID.randomUUID(),bid,limit.get()),"BANK_DUTY_REQUIRED");
        assertCode(()->approvals.limits(checker.actor(),UUID.randomUUID(),bid,limit.get()),"BANK_DUTY_REQUIRED");
        var pending=approvals.limits(maker.actor(),UUID.randomUUID(),bid,limit.get());
        assertCode(()->approvals.decide(CHECKER,pending.id(),APPROVE,"No duty"),"BANK_DUTY_REQUIRED");
        assertCode(()->approvals.decide(auditor.actor(),pending.id(),APPROVE,"Audit only"),"BANK_DUTY_APPROVAL_REQUIRED");
        assertCode(()->approvals.details(outsider.actor(),pending.id()),"BANK_DUTY_REQUIRED");
        assertThat(approvals.list(outsider.actor(),INBOX,null,bid,100,0).items().stream().map(r->r.id()).toList()).doesNotContain(pending.id());
        assertThat(approvals.list(auditor.actor(),INBOX,null,bid,100,0).items().stream().map(r->r.id()).toList()).contains(pending.id());
        assertThat(approvals.list(checker.actor(),INBOX,null,bid,100,0).items().stream().map(r->r.id()).toList()).contains(pending.id());
        assertThat(approvals.decide(checker.actor(),pending.id(),APPROVE,"Independent configuration review").status()).isEqualTo(APPROVED);

        // Platform-wide actions are not bank duties.
        var platformStaff=access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("duty-platform-"+UUID.randomUUID(),"Platform staff",null,"Platform action test"));
        var rename=approvals.staff(ADMIN,UUID.randomUUID(),platformStaff.id(),new com.cbs.access.AccessModels.StaffUpdate(platformStaff.version(),"Platform staff renamed",null,true,"Platform-wide change"));
        assertThat(approvals.decide(CHECKER,rename.id(),APPROVE,"Platform review").status()).isEqualTo(APPROVED);

        // At least two access administrators must remain while enforced.
        assertCode(()->approvals.bankDuty(accessA.actor(),UUID.randomUUID(),bid,dutyGrant(bid,accessB,false,none,none,false,null)),"BANK_DUTY_LOCKOUT");

        // A time-limited (break-glass) duty ends by itself; the maker is rechecked when the request is approved.
        Instant expires=Instant.now().plusSeconds(4);
        grantDuties(accessA.actor(),accessB.actor(),bid,dutyGrant(bid,temporary,true,CONFIG,none,false,expires));
        var late=approvals.limits(temporary.actor(),UUID.randomUUID(),bid,limit.get());
        Thread.sleep(Math.max(0,java.time.Duration.between(Instant.now(),expires).toMillis()+250));
        assertCode(()->approvals.decide(checker.actor(),late.id(),APPROVE,"After expiry"),"BANK_DUTY_MAKER_REVOKED");
        assertCode(()->approvals.limits(temporary.actor(),UUID.randomUUID(),bid,limit.get()),"BANK_DUTY_REQUIRED");
        assertThat(approvals.decide(temporary.actor(),late.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Duty expired").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.CANCELLED);

        // Access review: one consolidated view, conflicts flagged, attestation bound to the exact snapshot.
        grantDuties(accessA.actor(),accessB.actor(),bid,dutyGrant(bid,mixed,true,List.of(com.cbs.access.BankDuties.Duty.ACCESS_ADMINISTRATION,com.cbs.access.BankDuties.Duty.FINANCE),none,false,null));
        mvc.perform(get("/api/v1/admin/tenants/"+bid+"/bank-duties").header("Authorization","Bearer "+TestIdentity.token(auditor.subject(),true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enforced").value(true)).andExpect(jsonPath("$.snapshotHash").isString());
        assertCode(()->duties.report(outsider.actor(),bid),"BANK_DUTY_REQUIRED");
        var adminView=duties.report(accessA.actor(),bid);var auditView=duties.report(auditor.actor(),bid);
        assertThat(auditView.get("snapshotHash")).isEqualTo(adminView.get("snapshotHash"));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)auditView.get("items");
        assertThat(items).extracting(i->i.get("subject")).contains(accessA.subject(),maker.subject(),auditor.subject(),mixed.subject()).doesNotContain(outsider.subject());
        assertThat(items.stream().filter(i->i.get("subject").equals(mixed.subject())).findFirst().orElseThrow().get("findings").toString()).contains("CONFLICT_ACCESS_AND_TRANSACTIONS");
        String hash=(String)auditView.get("snapshotHash");
        assertCode(()->duties.attest(accessA.actor(),bid,new com.cbs.access.BankDuties.Review(hash,"Self review")),"BANK_DUTY_AUDIT_REQUIRED");
        var review=duties.attest(auditor.actor(),bid,new com.cbs.access.BankDuties.Review(hash,"Fictional quarterly access review"));
        assertThat(((Number)review.get("findings")).intValue()).isPositive();
        grantDuties(accessA.actor(),accessB.actor(),bid,dutyGrant(bid,maker,false,CONFIG,none,false,null));
        assertCode(()->duties.attest(auditor.actor(),bid,new com.cbs.access.BankDuties.Review(hash,"Stale view")),"ACCESS_REVIEW_STALE");
        assertThat((List<?>)duties.report(auditor.actor(),bid).get("reviews")).hasSize(1);

        // History, reviews and the switch are protected by the database.
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.bank_duty_event WHERE tenant_id=?",bid)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.access_review SET statement='Edited' WHERE tenant_id=?",bid)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.bank_duty_policy SET enforced=false WHERE tenant_id=?",bid)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.bank_duty SET payload=jsonb_set(payload,'{approve}','[\"FINANCE\"]') WHERE tenant_id=? AND staff_id=?",bid,maker.id())).isInstanceOf(DataAccessException.class);

        // Lockout recovery: with one access administrator left, administrators may still switch enforcement off (maker-checker).
        var disable=approvals.staff(ADMIN,UUID.randomUUID(),accessB.id(),new com.cbs.access.AccessModels.StaffUpdate(access.staff(accessB.id(),false).version(),"duty-access-b",accessB.subject()+"@example.test",false,"Leaver"));
        assertThat(approvals.decide(CHECKER,disable.id(),APPROVE,"Leaver processed").status()).isEqualTo(APPROVED);
        assertThat(duties.lockedOut(bid)).isTrue();
        var recovery=approvals.bankDutyPolicy(ADMIN,UUID.randomUUID(),bid,policy.apply(false));
        assertThat(approvals.list(CHECKER,INBOX,null,bid,100,0).items().stream().map(r->r.id()).toList()).contains(recovery.id());
        assertThat(approvals.decide(CHECKER,recovery.id(),APPROVE,"Lockout recovery").status()).isEqualTo(APPROVED);
        assertThat(duties.enforced(bid)).isFalse();
        assertThat(approvals.decide(CHECKER,approvals.limits(ADMIN,UUID.randomUUID(),bid,limit.get()).id(),APPROVE,"After recovery").status()).isEqualTo(APPROVED);
    }
    private com.cbs.access.AccessModels.Staff approvalStaff() {
        return access.create(ADMIN,new com.cbs.access.AccessModels.StaffInput("approval-"+UUID.randomUUID(),"Approval test staff",null,"Test onboarding"));
    }
    private com.cbs.access.AccessModels.StaffUpdate approvalUpdate(com.cbs.access.AccessModels.Staff staff,String name) {
        return new com.cbs.access.AccessModels.StaffUpdate(staff.version(),name,staff.email(),true,"Correct staff profile");
    }
    private Tenant register(String reference) { return service.register(ADMIN, UUID.randomUUID(), request(reference)).tenant(); }
    private Tenant provision(Tenant tenant) { return service.provision(ADMIN, tenant.id(), new ChangeRequest(tenant.version(), "Provision bank database")); }
    private static RegisterRequest request(String reference) {
        String code = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        if((reference.startsWith("payments-")||reference.startsWith("fx-")))return new RegisterRequest(code,"Fictional Kenya sandbox bank","en",reference,new LegalEntityInput("Fictional Kenya Bank","KE","KES","Africa/Nairobi"));
        return new RegisterRequest(code, "Example bank", "en-IN", reference,
                new LegalEntityInput("Example Bank Limited", "IN", "INR", "Asia/Kolkata"));
    }
    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestIdentity.token("administrator", true));
    }
    private Tenant invokeTenant(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = mvc.perform(admin(request).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body)))
                .andReturn().getResponse();
        if(response.getStatus()==202) {
            var pending=json.readValue(response.getContentAsString(),com.cbs.approvals.ApprovalModels.Request.class);
            assertThat(pending.status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.PENDING);
            approveHttp(pending.id());
            return service.get(ADMIN,pending.targetId());
        }
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readValue(response.getContentAsString(), Tenant.class);
    }
    private void approveHttp(UUID id) throws Exception {
        mvc.perform(post("/api/v1/admin/approvals/"+id+"/approve").header("Authorization","Bearer "+TestIdentity.token("checker",true))
                .contentType("application/json").content("{\"reason\":\"Verified supporting information\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }
    private org.springframework.test.web.servlet.ResultActions bankGet(UUID id, String subject, boolean operational) throws Exception {
        return mvc.perform(get("/api/v1/banks/" + id + (operational ? "/operational-context" : ""))
                .header("Authorization", "Bearer " + TestIdentity.token(subject, false)));
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(TenantException.class, exception -> assertThat(exception.code()).isEqualTo(code));
    }

    @Autowired com.cbs.cash.CashService cash;
    @Autowired com.cbs.cash.BankCashStore bankCash;
    private com.cbs.cash.CashModels.Change cashChange(Tenant bank,UUID branch,com.cbs.cash.CashModels.Kind kind,UUID location,UUID destination,UUID ledger,String amount,List<com.cbs.cash.CashModels.Count> counts){
        return new com.cbs.cash.CashModels.Change(service.get(ADMIN,bank.id()).version(),kind,branch,location,destination,ledger,null,null,null,null,amount,reference.get(ADMIN,bank.id()).businessDate(),"CASH-"+UUID.randomUUID(),counts,"Verified fictional physical cash evidence");
    }
    private UUID cashLocation(Tenant bank,UUID branch,com.cbs.accounting.LedgerModels.Definition source,String code,com.cbs.cash.CashModels.Type type)throws Exception{
        var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,code,source.chartAccountId(),code,true));approveHttp(request.id());ledger.deliver(request.id());
        var account=ledger.list(ADMIN,bank.id(),code,null,null,25,0).items().getFirst().definition();
        var input=new com.cbs.cash.CashModels.Change(service.get(ADMIN,bank.id()).version(),com.cbs.cash.CashModels.Kind.REGISTER,branch,null,null,account.id(),code,code,account.currency(),type,null,reference.get(ADMIN,bank.id()).businessDate(),code,null,"Register fictional cash custody");
        var registration=approvals.cash(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(registration.id());cash.deliver(registration.id());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,registration.id())).isNull();return registration.id();
    }
    private UUID postCash(Actor maker,Actor checker,Tenant bank,com.cbs.cash.CashModels.Change change){
        var request=approvals.cash(maker,UUID.randomUUID(),bank.id(),change);
        assertThat(approvals.decide(checker,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verified cash evidence").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.APPROVED);
        cash.deliver(request.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,request.id())).isNull();return request.id();
    }
    @Test void cashCustodyEnforcesApprovalsCountsLimitsAndAtomicPosting()throws Exception{
        var f=fundingFixture("cash-main");var bank=f.f().bank();
        var branch=branchService.create(ADMIN,bank.id(),new com.cbs.access.AccessModels.BranchInput(bank.legalEntities().getFirst().id(),"CASH","Cash branch","Open branch"));
        var teller=branchStaff(bank.id(),branch.id(),"cash-teller",com.cbs.access.AccessModels.BranchRole.TELLER);
        var manager=branchStaff(bank.id(),branch.id(),"cash-manager",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var checker=branchStaff(bank.id(),branch.id(),"cash-checker",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var owner=new Actor(TestIdentity.ISSUER,teller.subject(),false,UUID.randomUUID());var maker=new Actor(TestIdentity.ISSUER,manager.subject(),false,UUID.randomUUID());var reviewer=new Actor(TestIdentity.ISSUER,checker.subject(),false,UUID.randomUUID());
        var assignment=approvals.assignment(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Assignment(service.get(ADMIN,bank.id()).version(),f.account(),branch.id(),"Assign teller customer"));approveHttp(assignment.id());
        var vault=cashLocation(bank,branch.id(),f.source(),"VAULT",com.cbs.cash.CashModels.Type.VAULT);
        var till=cashLocation(bank,branch.id(),f.source(),"TILL",com.cbs.cash.CashModels.Type.TILL);
        var receive=com.cbs.cash.CashModels.Kind.RECEIVE;var transfer=com.cbs.cash.CashModels.Kind.TRANSFER;
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),receive,vault,null,f.source().id(),"1000",null)),"TRANSACTION_LIMIT_REQUIRED");
        for(String operation:List.of("CASH_RECEIVE","CASH_DISPATCH","CASH_TRANSFER","CASH_DEPOSIT","CASH_WITHDRAWAL")){
            var r=approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"INR",operation,"2000","5000","Fictional cash limits"));approveHttp(r.id());
            for(UUID staff:java.util.Arrays.asList(null,teller.id(),manager.id())){
                var policy=approvals.branchLimit(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,bank.id()).version(),branch.id(),staff,"INR",operation,"2000","5000","Fictional scoped cash limits"));approveHttp(policy.id());
            }
        }
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),receive,vault,null,f.source().id(),"1000",null)),"BRANCH_PERMISSION_DENIED");
        var incoming=cashChange(bank,branch.id(),receive,vault,null,f.source().id(),"1000",null);var key=UUID.randomUUID();var request=approvals.cash(maker,key,bank.id(),incoming);
        assertCode(()->approvals.decide(maker,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own approval"),"SELF_APPROVAL_FORBIDDEN");
        assertCode(()->approvals.decide(owner,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Teller approval"),"BRANCH_PERMISSION_DENIED");
        approvals.decide(reviewer,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Cash counted independently");
        assertCode(()->journals.deliver(request.id()),"FUNDING_MANAGED");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_cash BEFORE INSERT ON bank.cash_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        cash.deliver(request.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,request.id())).isEqualTo("CASH_DATABASE_UNAVAILABLE");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),vault)).balance())).isZero();
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){try(var rs=st.executeQuery("SELECT count(*) FROM bank.journal")){rs.next();assertThat(rs.getInt(1)).isZero();}st.execute("DROP TRIGGER fail_cash ON bank.cash_entry");}
        cash.deliver(request.id());assertThat(approvals.cash(maker,key,bank.id(),incoming).id()).isEqualTo(request.id());
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),vault)).balance())).isEqualByComparingTo("1000");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),transfer,vault,till,null,"100",null)),"CASH_SESSION_REQUIRED");
        postCash(owner,reviewer,bank,cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.OPEN,till,null,null,null,List.of()));
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_SESSION_OPEN");
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),transfer,vault,till,null,"100",null)),"CASH_CUSTODIAN_REQUIRED");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),transfer,vault,till,null,"2001",null)),"TRANSACTION_LIMIT_EXCEEDED");
        var movement=approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),transfer,vault,till,null,"100",null));approvals.decide(reviewer,movement.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Counted transfer");
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{cash.deliver(movement.id());t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.cash_delivery WHERE request_id=?",Boolean.class,movement.id())).isFalse();
        cash.deliver(movement.id());cash.deliver(movement.id());
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("100");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),vault)).balance())).isEqualByComparingTo("900");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),transfer,till,vault,null,"101",null)),"CASH_INSUFFICIENT");
        for(UUID staff:java.util.Arrays.asList(null,teller.id())){var policy=approvals.branchLimit(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,bank.id()).version(),branch.id(),staff,"INR","WITHDRAWAL","2000","5000","Fictional customer withdrawal limits"));approveHttp(policy.id());}
        var depositChange=customerCashChange(bank,branch.id(),till,com.cbs.cash.CashModels.Kind.DEPOSIT,f.account(),f.liability().id(),"200");
        var depositResponse=mvc.perform(post("/api/v1/banks/"+bank.id()+"/branches/"+branch.id()+"/operations/cash").header("Authorization","Bearer "+TestIdentity.token(teller.subject(),false)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(depositChange))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var depositRequest=json.readValue(depositResponse,com.cbs.approvals.ApprovalModels.Request.class);approvals.decide(reviewer,depositRequest.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Cash deposit checked");
        assertCode(()->funding.deliver(depositRequest.id()),"CASH_MANAGED");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_customer_cash BEFORE INSERT ON bank.cash_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        cash.deliver(depositRequest.id());assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("0");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("100");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("DROP TRIGGER fail_customer_cash ON bank.cash_entry");}
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{cash.deliver(depositRequest.id());t.setRollbackOnly();});cash.deliver(depositRequest.id());
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("200");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("300");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),customerCashChange(bank,branch.id(),till,com.cbs.cash.CashModels.Kind.WITHDRAWAL,f.account(),null,"201")),"INSUFFICIENT_AVAILABLE_BALANCE");
        var withdrawal=postCash(owner,reviewer,bank,customerCashChange(bank,branch.id(),till,com.cbs.cash.CashModels.Kind.WITHDRAWAL,f.account(),null,"100"));
        assertCode(()->operations.deliver(withdrawal),"CASH_MANAGED");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("100");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("200");

        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.CLOSE,till,null,null,null,List.of(new com.cbs.cash.CashModels.Count("50",1)))),"CASH_COUNT_MISMATCH");
        String exceptionsPath="/api/v1/banks/"+bank.id()+"/branches/"+branch.id()+"/operations/cash/exceptions";
        var incidentKey=UUID.randomUUID();
        var discrepancy=new com.cbs.cash.CashExceptionInput(service.get(ADMIN,bank.id()).version(),till,incoming.businessDate(),List.of(new com.cbs.cash.CashModels.Count("50",3)),"Fictional shortage investigation");
        mvc.perform(post(exceptionsPath).header("Authorization","Bearer "+TestIdentity.token(manager.subject(),false)).header("Idempotency-Key",incidentKey).contentType("application/json").content(json.writeValueAsString(discrepancy))).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(incidentKey.toString()));
        assertThat(cash.reportException(maker,bank.id(),branch.id(),incidentKey,discrepancy)).isEqualTo(incidentKey);
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_EXCEPTION_OPEN");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashChange(bank,branch.id(),transfer,vault,till,null,"1",null)),"CASH_EXCEPTION_OPEN");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),customerCashChange(bank,branch.id(),till,com.cbs.cash.CashModels.Kind.WITHDRAWAL,f.account(),null,"1")),"CASH_EXCEPTION_OPEN");
        var badRecount=new com.cbs.cash.CashExceptionInput(service.get(ADMIN,bank.id()).version(),till,incoming.businessDate(),List.of(new com.cbs.cash.CashModels.Count("50",3)),"Still short");
        assertCode(()->cash.resolveException(reviewer,bank.id(),branch.id(),incidentKey,badRecount),"CASH_COUNT_MISMATCH");
        var recount=new com.cbs.cash.CashExceptionInput(service.get(ADMIN,bank.id()).version(),till,incoming.businessDate(),List.of(new com.cbs.cash.CashModels.Count("50",4)),"Independent recount found the missing note");
        assertCode(()->cash.resolveException(maker,bank.id(),branch.id(),incidentKey,recount),"SELF_APPROVAL_FORBIDDEN");
        assertCode(()->cash.resolveException(owner,bank.id(),branch.id(),incidentKey,recount),"BRANCH_PERMISSION_DENIED");
        mvc.perform(get(exceptionsPath).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(post(exceptionsPath+"/"+incidentKey+"/resolve").header("Authorization","Bearer "+TestIdentity.token(checker.subject(),false)).contentType("application/json").content(json.writeValueAsString(recount))).andExpect(status().isOk());
        assertThat(cash.resolveException(reviewer,bank.id(),branch.id(),incidentKey,recount)).isEqualTo(incidentKey);
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CASH_EXCEPTION_OPEN");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("100");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("200");
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.cash_exception WHERE id=?",incidentKey)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform.cash_exception_resolution WHERE case_id=?",incidentKey)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var close=postCash(owner,reviewer,bank,cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.CLOSE,till,null,null,null,List.of(new com.cbs.cash.CashModels.Count("50",4))));
        assertThat(bankCash.position(bank,cash.location(bank.id(),till)).sessionOpen()).isFalse();assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CASH_SESSION_OPEN");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_COUNT_REQUIRED");
        postCash(maker,reviewer,bank,cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.COUNT,vault,null,null,null,List.of(new com.cbs.cash.CashModels.Count("100",9))));
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CASH_COUNT_REQUIRED");
        postCash(maker,reviewer,bank,cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.DISPATCH,vault,null,f.source().id(),"100",null));
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_COUNT_REQUIRED");
        postCash(maker,reviewer,bank,cashChange(bank,branch.id(),com.cbs.cash.CashModels.Kind.COUNT,vault,null,null,null,List.of(new com.cbs.cash.CashModels.Count("100",8))));
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CASH_COUNT_REQUIRED");
        var cashLedger=cash.location(bank.id(),vault).ledgerId();
        var bypass=new com.cbs.accounting.JournalModels.Entry(UUID.randomUUID(),bank.id(),"CASH-BYPASS","Unreceipted cash",incoming.businessDate(),bank.legalEntities().getFirst().id(),"INR",List.of(new com.cbs.accounting.JournalModels.Line(cashLedger,com.cbs.accounting.JournalModels.Side.DEBIT,"1"),new com.cbs.accounting.JournalModels.Line(f.source().id(),com.cbs.accounting.JournalModels.Side.CREDIT,"1")),null);
        assertCode(()->bankJournals.post(bank,new com.cbs.accounting.JournalModels.Dispatch(bypass,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),UUID.randomUUID())),"JOURNAL_DATABASE_UNAVAILABLE");
        String path="/api/v1/banks/"+bank.id()+"/branches/"+branch.id()+"/operations/cash";
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token(teller.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(path+"/"+close).header("Authorization","Bearer "+TestIdentity.token(teller.subject(),false))).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.cash_entry")).isInstanceOf(SQLException.class);
            try(var rs=st.executeQuery("SELECT count(*) FROM bank.cash_entry")){rs.next();assertThat(rs.getInt(1)).isEqualTo(6);}
        }
        verifyCashAdjustments(bank,branch.id(),vault,f.source().id(),owner,maker,reviewer);
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("100");
        verifyCashReversals(f,branch.id(),vault,till,owner,maker,reviewer);
        verifyInterbranchCash(f,branch.id(),vault,maker,reviewer);
        verifyDenominationInventory(f,branch.id(),vault,maker,reviewer);
    }
    @Autowired com.cbs.servicing.ServicingService servicing;

    @Test void complianceCasesRestrictPostingAndRequireIndependentCurrentEvidence()throws Exception {
        var f=fundingFixture("compliance-main");var bank=f.f().bank();fund(f,"1000");
        var queuedAccount=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approveHttp(queuedAccount.id());
        var queued=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"10","QUEUED-BEFORE-CASE"));approveHttp(queued.id());
        String path="/api/v1/admin/tenants/"+bank.id()+"/compliance";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        var input=new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.compliance.ComplianceModels.Category.KYC_REVIEW,"EVIDENCE/1","Fictional review only");
        UUID key=UUID.randomUUID();var opened=mvc.perform(admin(post(path)).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isCreated()).andExpect(jsonPath("$.case.status").value("OPEN")).andReturn().getResponse().getContentAsString();
        UUID caseId=UUID.fromString(json.readTree(opened).get("case").get("id").asText());
        assertThat(((Map<?,?>)compliance.open(ADMIN,bank.id(),key,input).get("case")).get("id")).isEqualTo(caseId);
        assertCode(()->compliance.open(ADMIN,bank.id(),key,new com.cbs.compliance.ComplianceModels.Open(input.expectedVersion(),input.customerId(),input.category(),"DIFFERENT","Changed")),"IDEMPOTENCY_CONFLICT");
        mvc.perform(admin(get(path+"/"+UUID.randomUUID()))).andExpect(status().isNotFound());
        assertCode(()->customers.requireEligible(service.get(ADMIN,bank.id()),f.f().customer()),"CUSTOMER_COMPLIANCE_RESTRICTED");
        accounts.deliver(queuedAccount.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.account_delivery WHERE request_id=?",String.class,queuedAccount.id())).isEqualTo("CUSTOMER_COMPLIANCE_RESTRICTED");
        funding.deliver(queued.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.funding_delivery WHERE request_id=?",String.class,queued.id())).isEqualTo("CUSTOMER_COMPLIANCE_RESTRICTED");
        var proposed=approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),caseId,com.cbs.compliance.ComplianceModels.Decision.CLEARED));
        assertCode(()->approvals.decide(ADMIN,proposed.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own decision"),"SELF_APPROVAL_FORBIDDEN");
        var detail=compliance.detail(ADMIN,bank.id(),caseId);var c=(Map<?,?>)detail.get("case");
        var note=new com.cbs.compliance.ComplianceModels.Note(((Number)detail.get("version")).longValue(),((Number)c.get("version")).longValue(),"EVIDENCE/2","New evidence after proposal");
        UUID noteKey=UUID.randomUUID();compliance.note(ADMIN,bank.id(),caseId,noteKey,note);compliance.note(ADMIN,bank.id(),caseId,noteKey,note);
        assertThat(approvals.decide(CHECKER,proposed.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Stale evidence").status()).isEqualTo(com.cbs.approvals.ApprovalModels.Status.STALE);
        var decision=approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),caseId,com.cbs.compliance.ComplianceModels.Decision.CLEARED));approveHttp(decision.id());
        customers.requireEligible(service.get(ADMIN,bank.id()),f.f().customer());
        accounts.deliver(queuedAccount.id());assertThat(jdbc.queryForObject("SELECT completed FROM platform.account_delivery WHERE request_id=?",Boolean.class,queuedAccount.id())).isTrue();
        // A committed bank receipt is acknowledged even if a new restriction arrives before retry.
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{funding.deliver(queued.id());t.setRollbackOnly();});
        var next=compliance.open(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.compliance.ComplianceModels.Category.SCREENING_ALERT,"SCREEN/2","Fictional alert"));
        UUID nextId=(UUID)((Map<?,?>)next.get("case")).get("id");funding.deliver(queued.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.funding_delivery WHERE request_id=?",Boolean.class,queued.id())).isTrue();
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("1010");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"10",null,null)),"CUSTOMER_COMPLIANCE_RESTRICTED");
        var restricted=approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),nextId,com.cbs.compliance.ComplianceModels.Decision.RESTRICTED));approveHttp(restricted.id());
        assertCode(()->customers.requireEligible(service.get(ADMIN,bank.id()),f.f().customer()),"CUSTOMER_COMPLIANCE_RESTRICTED");
        var clear=approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),nextId,com.cbs.compliance.ComplianceModels.Decision.CLEARED));approveHttp(clear.id());
        customers.requireEligible(service.get(ADMIN,bank.id()),f.f().customer());
        var profile=customers.detail(ADMIN,bank.id(),f.f().customer()).definition().profile();
        var changed=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,f.f().customer(),profile,""));approveHttp(changed.id());customers.deliver(changed.id());
        assertCode(()->customers.requireEligible(service.get(ADMIN,bank.id()),f.f().customer()),"CUSTOMER_COMPLIANCE_RESTRICTED");
        assertThat(((Number)compliance.list(ADMIN,bank.id(),"REVIEW_DUE",25,0).get("total")).longValue()).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.compliance_event SET subject='tampered' WHERE case_id=?",caseId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE platform.compliance_case SET status='OPEN' WHERE id=?",caseId)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.compliance_event WHERE case_id=? AND action='NOTE'",Integer.class,caseId)).isEqualTo(1);
        var other=register("compliance-other");assertCode(()->compliance.detail(ADMIN,other.id(),caseId),"COMPLIANCE_CASE_NOT_FOUND");
    }
    private com.cbs.compliance.ComplianceModels.Review complianceReview(UUID bank,UUID id,com.cbs.compliance.ComplianceModels.Decision decision){
        var detail=compliance.detail(ADMIN,bank,id);var c=(Map<?,?>)detail.get("case");
        return new com.cbs.compliance.ComplianceModels.Review(((Number)detail.get("version")).longValue(),id,((Number)c.get("version")).longValue(),((Number)c.get("customer_version")).longValue(),decision,com.cbs.compliance.ComplianceModels.Risk.MEDIUM,"KYC/VERIFIED","SCREEN/MANUAL",decision==com.cbs.compliance.ComplianceModels.Decision.CLEARED?java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30):null,"Fictional independent review evidence");
    }
    @Test void servicingPostsInterestFeesTaxesAndStatementsExactlyOnce()throws Exception{
        var f=fundingFixture("servicing-main");var bank=f.f().bank();fund(f,"1000");
        var expense=adjustmentLedger(bank,"SVC_EXP",com.cbs.accounting.ChartModels.Category.EXPENSE);
        var payable=adjustmentLedger(bank,"SVC_PAY",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var income=adjustmentLedger(bank,"SVC_FEE",com.cbs.accounting.ChartModels.Category.INCOME);
        var tax=adjustmentLedger(bank,"SVC_TAX",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var date=reference.get(ADMIN,bank.id()).businessDate();
        var policy=new com.cbs.servicing.ServicingModels.Policy(service.get(ADMIN,bank.id()).version(),f.f().product().id(),f.f().product().revision(),bank.legalEntities().getFirst().id(),date,"36.5",365,"0.50","10","20","HALF_EVEN",expense,payable,income,tax,"Fictional rates and tax for tests only");
        var configuration=approvals.servicingPolicy(ADMIN,UUID.randomUUID(),bank.id(),policy);
        assertCode(()->approvals.decide(ADMIN,configuration.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own policy"),"SELF_APPROVAL_FORBIDDEN");approveHttp(configuration.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("SERVICING_REQUIRED");
        // An applicable servicing policy no longer blocks closure by itself; the funded balance does.
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).blockers()).contains("FUNDING_BALANCE_NOT_ZERO").doesNotContain("ACCOUNT_CLOSURE_SERVICING_REQUIRED");
        // Approved policy corrections retain history and supersede the same effective date.
        java.util.function.Function<String,com.cbs.servicing.ServicingModels.Policy> withFee=fee->new com.cbs.servicing.ServicingModels.Policy(service.get(ADMIN,bank.id()).version(),policy.productId(),policy.productRevision(),policy.legalEntityId(),date,policy.annualRatePercent(),365,fee,"10","20","HALF_EVEN",expense,payable,income,tax,"Correct fictional daily fee");
        var expensive=approvals.servicingPolicy(ADMIN,UUID.randomUUID(),bank.id(),withFee.apply("2000"));approveHttp(expensive.id());
        // An unaffordable daily fee now excludes only that account, visibly, instead of blocking the whole run.
        var excessive=servicing.preview(ADMIN,bank.id(),new com.cbs.servicing.ServicingModels.Run(service.get(ADMIN,bank.id()).version(),date,"Preview excessive fee"));
        assertThat(excessive.items()).isEmpty();assertThat(excessive.exclusions()).singleElement().satisfies(x->{assertThat(x.accountId()).isEqualTo(f.account());assertThat(x.reason()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE");});
        var corrected=approvals.servicingPolicy(ADMIN,UUID.randomUUID(),bank.id(),withFee.apply("0.50"));approveHttp(corrected.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.servicing_policy WHERE tenant_id=?",Integer.class,bank.id())).isEqualTo(3);
        var input=new com.cbs.servicing.ServicingModels.Run(service.get(ADMIN,bank.id()).version(),date,"Close fictional daily processing");
        var preview=servicing.preview(ADMIN,bank.id(),input);assertThat(preview.items()).hasSize(1);var calculation=preview.items().getFirst().calculation();
        var expected=new java.math.BigDecimal("1000").add(new java.math.BigDecimal(calculation.netChange()));
        var key=UUID.randomUUID();var run=approvals.servicingRun(ADMIN,key,bank.id(),input);
        approveHttp(run.id());assertThat(approvals.servicingRun(ADMIN,key,bank.id(),input).id()).isEqualTo(run.id());
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"10","AFTER-SERVICING")),"SERVICING_DAY_FINALIZED");
        try(var c=TestDatabases.bank("servicing");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_service BEFORE INSERT ON bank.servicing_event FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        servicing.deliver(run.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.servicing_run WHERE request_id=?",String.class,run.id())).isEqualTo("SERVICING_DATABASE_UNAVAILABLE");
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted()).isEqualTo("1000");
        try(var c=TestDatabases.bank("servicing");var st=c.createStatement()){st.execute("DROP TRIGGER fail_service ON bank.servicing_event");}
        var hold=compliance.open(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.compliance.ComplianceModels.Category.KYC_REVIEW,"SVC/CASE","Restriction before servicing delivery"));
        UUID holdId=(UUID)((Map<?,?>)hold.get("case")).get("id");servicing.deliver(run.id());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.servicing_run WHERE request_id=?",String.class,run.id())).isEqualTo("CUSTOMER_COMPLIANCE_RESTRICTED");
        var release=approvals.compliance(ADMIN,UUID.randomUUID(),bank.id(),complianceReview(bank.id(),holdId,com.cbs.compliance.ComplianceModels.Decision.CLEARED));approveHttp(release.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{servicing.deliver(run.id());t.setRollbackOnly();});
        compliance.open(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.compliance.ComplianceModels.Category.TRANSACTION_REVIEW,"SVC/ACK","Restriction after committed servicing"));
        servicing.deliver(run.id());servicing.deliver(run.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.servicing_run WHERE request_id=?",Boolean.class,run.id())).isTrue();
        assertThat(new java.math.BigDecimal(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().posted())).isEqualByComparingTo(expected);
        assertThat(operations.dashboard(ADMIN,bank.id(),f.account(),25,0).position().reconciled()).isTrue();
        assertThat(new java.math.BigDecimal(statements.read(ADMIN,bank.id(),f.account(),date,date,25,0).closingBalance())).isEqualByComparingTo(expected);
        var journal=jdbc.queryForObject("SELECT request_id FROM platform.servicing_posting WHERE run_id=?",UUID.class,run.id());assertCode(()->journals.deliver(journal),"SERVICING_MANAGED");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("SERVICING_REQUIRED");
        var day=days.dashboard(ADMIN,bank.id(),25,0);var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Close serviced day"));approveHttp(closing.id());days.deliver(closing.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).businessDate()).isEqualTo(day.nextBusinessDate());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("SERVICING_REQUIRED");
        try(var c=TestDatabases.bank("servicing");var st=c.createStatement()){
            try(var rs=st.executeQuery("SELECT count(*) FROM bank.servicing_event")){rs.next();assertThat(rs.getInt(1)).isEqualTo(1);}
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.servicing_event")).isInstanceOf(SQLException.class);
        }
    }
    /** Opens, delivers and funds one more account for the fixture's customer and product. */
    private UUID extraFundedAccount(FundingFixture f,String code,String amount)throws Exception{
        var bank=f.f().bank();
        var opening=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),null,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING));approveHttp(opening.id());accounts.deliver(opening.id());
        UUID account=jdbc.queryForObject("SELECT id FROM platform.account_registry WHERE tenant_id=? AND request_id=?",UUID.class,bank.id(),opening.id());
        var liability=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,code,f.f().product().liabilityAccountId(),"Customer liability "+code,true));approveHttp(liability.id());ledger.deliver(liability.id());
        var ledgerId=ledger.list(ADMIN,bank.id(),code,null,null,25,0).items().getFirst().definition().id();
        com.cbs.approvals.ApprovalModels.Request r;try{r=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounts.FundingModels.Change(service.get(ADMIN,bank.id()).version(),account,f.source().id(),ledgerId,amount,reference.get(ADMIN,bank.id()).businessDate(),"F-"+code,"Verified fictional receipt"));}catch(TenantException e){throw new AssertionError("funding "+e.code());}approveHttp(r.id());funding.deliver(r.id());
        return account;
    }
    @Test void servicingPostsInResumableChunksAndExcludesOnlyIneligibleAccounts()throws Exception{
        var f=fundingFixture("servicing-chunks");var bank=f.f().bank();fund(f,"1000");
        var eligible=new java.util.ArrayList<UUID>(List.of(f.account()));
        for(int i=2;i<=5;i++)eligible.add(extraFundedAccount(f,"CUSTOMER_00"+i,String.valueOf(1000*i)));
        UUID low=extraFundedAccount(f,"CUSTOMER_006","100");
        // Leave just above the product minimum, too little for the day's fee and fee tax.
        var drain=approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,bank.id()).version(),com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,low,null,f.source().id(),null,"89.80",reference.get(ADMIN,bank.id()).businessDate(),"W-LOW","Fictional withdrawal"));approveHttp(drain.id());operations.deliver(drain.id());UUID frozen=extraFundedAccount(f,"CUSTOMER_007","500");
        var freeze=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),accountChange(f.f(),frozen,com.cbs.accounts.AccountModels.State.FROZEN));approveHttp(freeze.id());accounts.deliver(freeze.id());
        var expense=adjustmentLedger(bank,"CH_EXP",com.cbs.accounting.ChartModels.Category.EXPENSE);var payable=adjustmentLedger(bank,"CH_PAY",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var income=adjustmentLedger(bank,"CH_FEE",com.cbs.accounting.ChartModels.Category.INCOME);var tax=adjustmentLedger(bank,"CH_TAX",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var date=reference.get(ADMIN,bank.id()).businessDate();
        approveHttp(approvals.servicingPolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.servicing.ServicingModels.Policy(service.get(ADMIN,bank.id()).version(),f.f().product().id(),f.f().product().revision(),bank.legalEntities().getFirst().id(),date,"3.65",365,"0.50","10","20","HALF_EVEN",expense,payable,income,tax,"Fictional chunking policy")).id());
        var input=new com.cbs.servicing.ServicingModels.Run(service.get(ADMIN,bank.id()).version(),date,"Fictional chunked daily processing");
        var preview=servicing.preview(ADMIN,bank.id(),input);
        assertThat(preview.accountCount()).isEqualTo(5);assertThat(preview.chunkSize()).isEqualTo(2);assertThat(preview.chunkCount()).isEqualTo(3);
        assertThat(preview.items()).extracting(i->i.account().id()).containsExactlyInAnyOrderElementsOf(eligible);
        assertThat(preview.exclusions()).extracting(x->x.accountId()+":"+x.reason()).containsExactlyInAnyOrder(low+":INSUFFICIENT_AVAILABLE_BALANCE",frozen+":ACCOUNT_FROZEN");
        var net=preview.items().stream().map(i->new java.math.BigDecimal(i.calculation().netChange())).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        assertThat(preview.totals()).singleElement().satisfies(t->{assertThat(t.accounts()).isEqualTo(5);assertThat(new java.math.BigDecimal(t.netChange())).isEqualByComparingTo(net);});
        var expected=new java.util.HashMap<UUID,java.math.BigDecimal>();for(var i:preview.items())expected.put(i.account().id(),new java.math.BigDecimal(i.openingBalance()).add(new java.math.BigDecimal(i.calculation().netChange())));
        var run=approvals.servicingRun(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(run.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.servicing_chunk WHERE run_id=?",Integer.class,run.id())).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT reason FROM platform.servicing_exclusion WHERE run_id=? ORDER BY reason",String.class,run.id())).containsExactly("ACCOUNT_FROZEN","INSUFFICIENT_AVAILABLE_BALANCE");
        // Make the second chunk fail inside the bank: the first stays posted, the day stays sealed.
        var second=jdbc.queryForObject("SELECT (payload->'items'->0->'account'->>'id')::uuid FROM platform.servicing_chunk WHERE run_id=? AND chunk_no=1",UUID.class,run.id());
        try(var c=TestDatabases.bank("chunks");var st=c.createStatement()){st.execute("CREATE FUNCTION bank.fail_chunk() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.account_id='"+second+"' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");st.execute("CREATE TRIGGER fail_chunk BEFORE INSERT ON bank.servicing_event FOR EACH ROW EXECUTE FUNCTION bank.fail_chunk()");}
        servicing.deliver(run.id());
        assertThat(jdbc.queryForList("SELECT completed FROM platform.servicing_chunk WHERE run_id=? ORDER BY chunk_no",Boolean.class,run.id())).containsExactly(true,false,false);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.servicing_run WHERE request_id=?",String.class,run.id())).isEqualTo("SERVICING_DATABASE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.servicing_run WHERE request_id=?",Boolean.class,run.id())).isFalse();
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"10","DURING-CHUNKS")),"SERVICING_DAY_FINALIZED");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("SERVICING_REQUIRED");
        assertThat(accounts.closure(ADMIN,bank.id(),f.account()).blockers()).contains("ACCOUNT_CLOSURE_SERVICING_REQUIRED");
        try(var c=TestDatabases.bank("chunks");var st=c.createStatement()){st.execute("DROP TRIGGER fail_chunk ON bank.servicing_event");st.execute("DROP FUNCTION bank.fail_chunk()");}
        // Lost acknowledgement: bank commits, platform rolls back; the retry acknowledges from receipts.
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{servicing.deliver(run.id());t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.servicing_run WHERE request_id=?",Boolean.class,run.id())).isFalse();
        servicing.deliver(run.id());servicing.deliver(run.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.servicing_run WHERE request_id=?",Boolean.class,run.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.journal_delivery d JOIN platform.servicing_posting p ON p.request_id=d.request_id WHERE p.run_id=? AND NOT d.completed",Integer.class,run.id())).isZero();
        for(var e:expected.entrySet()){var p=operations.dashboard(ADMIN,bank.id(),e.getKey(),25,0).position();assertThat(new java.math.BigDecimal(p.posted())).isEqualByComparingTo(e.getValue());assertThat(p.reconciled()).isTrue();}
        assertThat(new java.math.BigDecimal(operations.dashboard(ADMIN,bank.id(),low,25,0).position().posted())).isEqualByComparingTo("10.20");
        assertThat(new java.math.BigDecimal(operations.dashboard(ADMIN,bank.id(),frozen,25,0).position().posted())).isEqualByComparingTo("500");
        try(var c=TestDatabases.bank("chunks");var st=c.createStatement()){
            try(var r=st.executeQuery("SELECT (SELECT count(*) FROM bank.servicing_event),(SELECT count(*) FROM bank.servicing_chunk),(SELECT count(*) FROM bank.servicing_item),(SELECT count(*) FROM bank.servicing_run)")){r.next();assertThat(r.getInt(1)).isEqualTo(5);assertThat(r.getInt(2)).isEqualTo(3);assertThat(r.getInt(3)).isEqualTo(5);assertThat(r.getInt(4)).isEqualTo(1);}
            assertThatThrownBy(()->st.executeUpdate("UPDATE bank.servicing_item SET chunk_no=0")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->st.executeUpdate("INSERT INTO bank.journal SELECT (jsonb_populate_record(null::bank.journal,to_jsonb(j)||jsonb_build_object('id',gen_random_uuid(),'reference','TAMPER-'||gen_random_uuid(),'reversal_of',null))).* FROM bank.journal j WHERE j.reference LIKE 'SVC-%' LIMIT 1")).isInstanceOf(SQLException.class).hasMessageContaining("finalized");
        }
        var day=days.dashboard(ADMIN,bank.id(),25,0);assertThat(day.blockers()).doesNotContain("SERVICING_REQUIRED");
        var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Close chunked day"));approveHttp(closing.id());days.deliver(closing.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).businessDate()).isEqualTo(day.nextBusinessDate());
    }
    /**
     * Volume check beyond the former 1,000-account cap. Opt-in (slow): CBS_VOLUME_TESTS=true,
     * optional CBS_VOLUME_ACCOUNTS (default 1050). Every account is opened and funded through the
     * normal approval workflow; the run's postings are reconciled account by account and in total.
     */
    @Test @EnabledIfEnvironmentVariable(named="CBS_VOLUME_TESTS",matches="true")
    void servicingVolumeBeyondFormerCapReconcilesEveryAccount()throws Exception{
        int n=Integer.parseInt(System.getenv().getOrDefault("CBS_VOLUME_ACCOUNTS","1050"));
        var f=fundingFixture("servicing-volume");var bank=f.f().bank();fund(f,"1000");
        long started=System.nanoTime();
        for(int i=2;i<=n;i++)extraFundedAccount(f,"VOL_"+i,String.valueOf(100+i)+"."+String.format("%02d",i%100));
        long opened=System.nanoTime();
        var expense=adjustmentLedger(bank,"VOL_EXP",com.cbs.accounting.ChartModels.Category.EXPENSE);var payable=adjustmentLedger(bank,"VOL_PAY",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var income=adjustmentLedger(bank,"VOL_FEE",com.cbs.accounting.ChartModels.Category.INCOME);var tax=adjustmentLedger(bank,"VOL_TAX",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var date=reference.get(ADMIN,bank.id()).businessDate();
        approveHttp(approvals.servicingPolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.servicing.ServicingModels.Policy(service.get(ADMIN,bank.id()).version(),f.f().product().id(),f.f().product().revision(),bank.legalEntities().getFirst().id(),date,"7.3",365,"0.05","15","16","HALF_EVEN",expense,payable,income,tax,"Fictional volume policy")).id());
        var input=new com.cbs.servicing.ServicingModels.Run(service.get(ADMIN,bank.id()).version(),date,"Fictional volume run");
        long t0=System.nanoTime();var preview=servicing.preview(ADMIN,bank.id(),input);long t1=System.nanoTime();
        assertThat(preview.accountCount()).isEqualTo(n);assertThat(preview.exclusions()).isEmpty();assertThat(preview.itemsTruncated()).isEqualTo(n>200);
        var run=approvals.servicingRun(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(run.id());long t2=System.nanoTime();
        servicing.deliver(run.id());long t3=System.nanoTime();
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.servicing_run WHERE request_id=?",Boolean.class,run.id())).isTrue();
        var expectedNet=new java.math.BigDecimal(preview.totals().getFirst().netChange());
        try(var c=TestDatabases.bank("volume");var st=c.createStatement()){
            try(var r=st.executeQuery("SELECT count(*),sum(net_amount) FROM bank.servicing_event")){r.next();assertThat(r.getInt(1)).isEqualTo(n);assertThat(r.getBigDecimal(2)).isEqualByComparingTo(expectedNet);}
            try(var r=st.executeQuery("SELECT count(*) FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.posted<>coalesce(l.credit,0)-coalesce(l.debit,0)")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=st.executeQuery("SELECT count(*) FROM bank.servicing_event e WHERE e.opening_balance+e.net_amount<>(SELECT posted FROM bank.customer_position p WHERE p.account_id=e.account_id)")){r.next();assertThat(r.getInt(1)).isZero();}
        }
        System.out.printf("VOLUME accounts=%d chunks=%d open+fund=%.1fs preview=%.1fs approve=%.1fs deliver=%.1fs%n",n,preview.chunkCount(),(opened-started)/1e9,(t1-t0)/1e9,(t2-t1)/1e9,(t3-t2)/1e9);
    }
    @Test void statementsStreamAsCsvAndPdfAndAlertsFollowApprovedPreferences()throws Exception{
        var f=fundingFixture("statement-alerts");var bank=f.f().bank();
                java.util.function.Function<com.cbs.notifications.NotificationModels.Preference,com.cbs.approvals.ApprovalModels.Request> submit=p->approvals.notificationPreference(ADMIN,UUID.randomUUID(),bank.id(),p);
        java.util.function.BiFunction<String,String,com.cbs.notifications.NotificationModels.Preference> pref=(channel,destination)->new com.cbs.notifications.NotificationModels.Preference(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.notifications.NotificationModels.Channel.valueOf(channel),destination,"sw",java.util.EnumSet.allOf(com.cbs.notifications.NotificationModels.Event.class),"CONSENT/FORM-7","Customer signed alert consent");
        // No preference: postings create no alert.
        fund(f,"1000");assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification WHERE tenant_id=?",Integer.class,bank.id())).isZero();
        assertCode(()->submit.apply(pref.apply("SMS","0712345678")),"NOTIFICATION_DESTINATION_INVALID");
        assertCode(()->submit.apply(pref.apply("EMAIL","not-an-email")),"NOTIFICATION_DESTINATION_INVALID");
        assertCode(()->submit.apply(pref.apply("NONE","+254712345678")),"NOTIFICATION_DESTINATION_INVALID");
        var request=submit.apply(pref.apply("SMS","+254712345678"));
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");
        assertThat(notifications.preference(ADMIN,bank.id(),f.f().customer())).isNull();
        approveHttp(request.id());
        assertThat(notifications.preference(ADMIN,bank.id(),f.f().customer()).destination()).isEqualTo("+254712345678");
        // Credit and debit alerts are recorded with the posting, once, in the customer's language and without balances.
        var funded=fund(f,"250.75");funding.deliver(funded);
        var withdrawal=postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"40.5",null,null));
        postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.HOLD,"1",null,null));
        var rows=jdbc.queryForList("SELECT event,source_id,body,status,destination FROM platform.notification WHERE tenant_id=? ORDER BY created_at",bank.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("event","CREDIT").containsEntry("source_id",funded).containsEntry("status","PENDING");
        assertThat((String)rows.get(0).get("body")).contains("250.75","zimeingizwa","Kumb.").doesNotContain("1250.75");
        assertThat(rows.get(1)).containsEntry("event","DEBIT").containsEntry("source_id",withdrawal);assertThat((String)rows.get(1).get("body")).contains("40.5","zimetolewa");
        var accountNumber=accounts.detail(ADMIN,bank.id(),f.account()).definition().accountNumber();
        assertThat((String)rows.get(0).get("body")).contains(accountNumber.substring(accountNumber.length()-4)).doesNotContain(accountNumber.length()>4?accountNumber:"#");
        // The test sender delivers; the list masks destinations.
        // The dispatcher serves every bank; check this bank's alerts only.
        notifications.dispatch();notifications.dispatch();assertThat(notifications.list(ADMIN,bank.id(),"PENDING",null,25,0).total()).isZero();
        var page=notifications.list(ADMIN,bank.id(),"SENT",null,25,0);assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).allSatisfy(n->{assertThat(n.destination()).isEqualTo("+254******678");assertThat(n.provider()).isEqualTo("TEST");assertThat(n.providerReference()).startsWith("TEST-");});
        // A failing destination is retried, then marked failed; an administrator can queue it again.
        approveHttp(submit.apply(new com.cbs.notifications.NotificationModels.Preference(service.get(ADMIN,bank.id()).version(),f.f().customer(),com.cbs.notifications.NotificationModels.Channel.SMS,"+254700000000","en",java.util.EnumSet.of(com.cbs.notifications.NotificationModels.Event.CREDIT),"CONSENT/FORM-8","Changed phone")).id());
        fund(f,"10");postOperation(f,operationChange(f,com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,"5",null,null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.notification WHERE tenant_id=? AND status='PENDING'",Integer.class,bank.id())).isEqualTo(1);
        for(int i=0;i<5;i++)notifications.dispatch();
        var failed=notifications.list(ADMIN,bank.id(),"FAILED",null,25,0).items();assertThat(failed).singleElement().satisfies(n->{assertThat(n.attempts()).isEqualTo(5);assertThat(n.failureCode()).isEqualTo("TEST_PROVIDER_REJECTED");assertThat(n.body()).contains("credited");});
        notifications.retry(ADMIN,bank.id(),failed.getFirst().id());assertCode(()->notifications.retry(ADMIN,bank.id(),failed.getFirst().id()),"NOTIFICATION_NOT_RETRYABLE");
        assertThat(notifications.list(ADMIN,bank.id(),"PENDING",null,25,0).total()).isEqualTo(1);
        String base="/api/v1/admin/tenants/"+bank.id();
        mvc.perform(get(base+"/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(admin(get(base+"/notifications?status=SENT"))).andExpect(status().isOk()).andExpect(jsonPath("$.providerConfigured").value(true)).andExpect(jsonPath("$.items[0].destination").value("+254******678"));
        // Statements: whole-range CSV and a PDF with exact balances; bad inputs are rejected before any bytes are sent.
        var date=reference.get(ADMIN,bank.id()).businessDate();var closing=statements.read(ADMIN,bank.id(),f.account(),date,date,25,0);
        String path=base+"/accounts/"+f.account()+"/statement";
        mvc.perform(get(path+"/pdf?from="+date+"&to="+date)).andExpect(status().isUnauthorized());
        var csv=mvc.perform(admin(get(path+"/export?from="+date+"&to="+date))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv.lines().count()).isEqualTo(5+closing.total());assertThat(csv).contains(closing.closingBalance());
        var pdf=mvc.perform(admin(get(path+"/pdf?from="+date+"&to="+date+"&language=fr"))).andExpect(status().isOk()).andExpect(header().string("Content-Type","application/pdf")).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf)){var text=new org.apache.pdfbox.text.PDFTextStripper().getText(doc);assertThat(text).contains("Relevé de compte",accountNumber,closing.closingBalance(),"Page 1 sur");}
        mvc.perform(admin(get(path+"/pdf?from="+date+"&to="+date+"&language=de"))).andExpect(status().isBadRequest());
        mvc.perform(admin(get(path+"/pdf?from="+date+"&to="+date.plusDays(400)))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT change_details->>'format' FROM platform.tenant_audit WHERE tenant_id=? AND action='ACCOUNT_STATEMENT_EXPORTED'",String.class,bank.id())).contains("CSV","PDF");
    }
    private UUID onboard(Tenant bank,com.cbs.customers.CustomerModels.Profile profile)throws Exception{
        var r=approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,profile,""));approveHttp(r.id());customers.deliver(r.id());
        return jdbc.queryForObject("SELECT id FROM platform.customer_registry WHERE tenant_id=? AND request_id=?",UUID.class,bank.id(),r.id());
    }
    private com.cbs.customers.CustomerModels.Profile person(String name,String doc,java.time.LocalDate born){
        return new com.cbs.customers.CustomerModels.Profile(name,born,"KE","KE","Fictional test address","+254700000001","demo@example.test",com.cbs.customers.CustomerModels.DocumentType.NATIONAL_ID,"KE",doc,null,"DOC/"+doc,com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/"+doc);
    }
    /** Opens and funds an account for any customer of the fixture's bank. */
    private UUID accountFor(FundingFixture f,UUID customer,String code,String amount)throws Exception{
        var bank=f.f().bank();
        var opening=approvals.account(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounts.AccountModels.Change(service.get(ADMIN,bank.id()).version(),null,customer,f.f().product().id(),f.f().product().revision(),bank.legalEntities().getFirst().id(),"Account "+code,com.cbs.accounts.AccountModels.State.AWAITING_FUNDING,"Open fictional account"));approveHttp(opening.id());accounts.deliver(opening.id());
        UUID account=jdbc.queryForObject("SELECT id FROM platform.account_registry WHERE tenant_id=? AND request_id=?",UUID.class,bank.id(),opening.id());
        var liability=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,code,f.f().product().liabilityAccountId(),"Customer liability "+code,true));approveHttp(liability.id());ledger.deliver(liability.id());
        var ledgerId=ledger.list(ADMIN,bank.id(),code,null,null,25,0).items().getFirst().definition().id();
        var r=approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounts.FundingModels.Change(service.get(ADMIN,bank.id()).version(),account,f.source().id(),ledgerId,amount,reference.get(ADMIN,bank.id()).businessDate(),"F-"+code,"Verified fictional receipt"));approveHttp(r.id());funding.deliver(r.id());
        return account;
    }
    private com.cbs.accounts.OperationModels.Change debit(FundingFixture f,UUID account,String amount,List<UUID> signers){
        return new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,f.f().bank().id()).version(),com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,account,null,f.source().id(),null,amount,reference.get(ADMIN,f.f().bank().id()).businessDate(),"W-"+UUID.randomUUID(),"Fictional withdrawal slip",signers);
    }
    private com.cbs.mandates.MandateModels.Mandate mandate(Tenant bank,UUID account,com.cbs.mandates.MandateModels.SigningRule rule,com.cbs.mandates.MandateModels.AccountParty... parties){
        return new com.cbs.mandates.MandateModels.Mandate(service.get(ADMIN,bank.id()).version(),account,rule,List.of(parties),"Signed mandate form");
    }
    @Test void jointMinorAndOrganizationAccountsEnforceApprovedMandates()throws Exception{
        var f=fundingFixture("customer-mandates");var bank=f.f().bank();UUID holder=f.f().customer();
        approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),f.f().product().currency(),"FUNDING","999999999","999999999","Fictional limit")).id());
        var R=com.cbs.mandates.MandateModels.AccountRole.class;var day=reference.get(ADMIN,bank.id()).businessDate();
        UUID joint=onboard(bank,person("Second Holder","JH-0002",java.time.LocalDate.of(1985,3,3)));
        UUID other=onboard(bank,person("Unrelated Person","UP-0003",java.time.LocalDate.of(1980,4,4)));
        UUID minor=onboard(bank,person("Young Saver","MN-0004",day.minusYears(9)));
        // Organizations use a registration certificate; the type cannot be mixed up or changed later.
        var orgProfile=new com.cbs.customers.CustomerModels.Profile("Fictional Traders Ltd",java.time.LocalDate.of(2012,6,1),"KE","KE","Fictional business park","+254700000009","accounts@example.test",com.cbs.customers.CustomerModels.DocumentType.REGISTRATION_CERTIFICATE,"KE","PVT-12345",null,"DOC/PVT-12345",com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/PVT",com.cbs.customers.CustomerModels.CustomerType.ORGANIZATION);
        assertCode(()->approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,null,new com.cbs.customers.CustomerModels.Profile("Fictional Traders Ltd",java.time.LocalDate.of(2012,6,1),"KE","KE","Park","+254700000009","a@example.test",com.cbs.customers.CustomerModels.DocumentType.NATIONAL_ID,"KE","PVT-99999",null,"DOC/X",com.cbs.customers.CustomerModels.State.ACTIVE,"REVIEW/X",com.cbs.customers.CustomerModels.CustomerType.ORGANIZATION),"")),"CUSTOMER_TYPE_DOCUMENT_MISMATCH");
        UUID org=onboard(bank,orgProfile);
        assertCode(()->approvals.customer(ADMIN,UUID.randomUUID(),bank.id(),customerChange(bank,org,person("Fictional Traders Ltd","PVT-12345",java.time.LocalDate.of(2012,6,1)),"")),"CUSTOMER_TYPE_IMMUTABLE");

        // Joint account, both holders must sign.
        UUID account=f.account();fund(f,"1000");
        postOperation(f,debit(f,account,"10",null));
        assertCode(()->approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,account,com.cbs.mandates.MandateModels.SigningRule.ANY_TWO)),"MANDATE_RULE_UNSATISFIABLE");
        assertCode(()->approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,account,com.cbs.mandates.MandateModels.SigningRule.ANY_ONE,new com.cbs.mandates.MandateModels.AccountParty(joint,com.cbs.mandates.MandateModels.AccountRole.GUARDIAN))),"MANDATE_ROLE_INVALID");
        assertCode(()->approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,account,com.cbs.mandates.MandateModels.SigningRule.ANY_ONE,new com.cbs.mandates.MandateModels.AccountParty(minor,com.cbs.mandates.MandateModels.AccountRole.JOINT_HOLDER))),"PARTY_MUST_BE_ADULT_INDIVIDUAL");
        var jm=approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,account,com.cbs.mandates.MandateModels.SigningRule.ANY_TWO,new com.cbs.mandates.MandateModels.AccountParty(joint,com.cbs.mandates.MandateModels.AccountRole.JOINT_HOLDER)));
        assertCode(()->approvals.decide(ADMIN,jm.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");approveHttp(jm.id());
        var view=mandates.view(ADMIN,bank.id(),account);assertThat(view.required()).isTrue();assertThat(view.signaturesNeeded()).isEqualTo(2);assertThat(view.signers()).extracting(s->s.customerId()).containsExactlyInAnyOrder(holder,joint);
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,account,"10",null)),"MANDATE_SIGNATURES_REQUIRED");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,account,"10",List.of(holder))),"MANDATE_SIGNATURES_REQUIRED");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,account,"10",List.of(holder,other))),"MANDATE_SIGNATORY_INVALID");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,account,"10",List.of(holder,holder))),"MANDATE_SIGNATORY_INVALID");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),(new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,bank.id()).version(),com.cbs.accounts.OperationModels.Kind.HOLD,account,null,null,null,"1",day,"H-1","Hold",List.of(holder)))),"OPERATION_FIELDS_INVALID");
        postOperation(f,debit(f,account,"25.5",List.of(holder,joint)));
        assertThat(operations.dashboard(ADMIN,bank.id(),account,25,0).position().posted()).isEqualTo("964.5");
        // Statements name both holders; each opted-in holder is alerted.
        approveHttp(approvals.notificationPreference(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.notifications.NotificationModels.Preference(service.get(ADMIN,bank.id()).version(),joint,com.cbs.notifications.NotificationModels.Channel.EMAIL,"second@example.test","en",java.util.EnumSet.of(com.cbs.notifications.NotificationModels.Event.DEBIT),"CONSENT/J","Joint holder consent")).id());
        var alerted=postOperation(f,debit(f,account,"4.5",List.of(holder,joint)));
        assertThat(jdbc.queryForList("SELECT customer_id FROM platform.notification WHERE source_id=?",UUID.class,alerted)).containsExactly(joint);
        var csv=mvc.perform(admin(get("/api/v1/admin/tenants/"+bank.id()+"/accounts/"+account+"/statement/export?from="+day+"&to="+day))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Account holders","Account Example; Second Holder");

        // A minor's account is operated only by an approved guardian.
        UUID minorAccount=accountFor(f,minor,"MINOR_01","500");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,minorAccount,"5",null)),"MANDATE_REQUIRED");
        assertThat(mandates.view(ADMIN,bank.id(),minorAccount).primaryMinor()).isTrue();
        assertCode(()->approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,minorAccount,com.cbs.mandates.MandateModels.SigningRule.ANY_ONE,new com.cbs.mandates.MandateModels.AccountParty(holder,com.cbs.mandates.MandateModels.AccountRole.JOINT_HOLDER))),"MANDATE_ROLE_INVALID");
        approveHttp(approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,minorAccount,com.cbs.mandates.MandateModels.SigningRule.ANY_ONE,new com.cbs.mandates.MandateModels.AccountParty(holder,com.cbs.mandates.MandateModels.AccountRole.GUARDIAN))).id());
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,minorAccount,"5",List.of(minor))),"MANDATE_SIGNATORY_INVALID");
        postOperation(f,debit(f,minorAccount,"5",List.of(holder)));

        // An organization needs recorded directors before it can transact, and only they may sign.
        assertCode(()->accountFor(f,org,"ORG_01","700"),"ORGANIZATION_PARTIES_REQUIRED");
        java.util.function.Function<List<com.cbs.mandates.MandateModels.Party>,com.cbs.mandates.MandateModels.Parties> parties=list->new com.cbs.mandates.MandateModels.Parties(service.get(ADMIN,bank.id()).version(),org,list,"Board resolution and share register");
        var D=com.cbs.mandates.MandateModels.PartyRole.DIRECTOR;var B=com.cbs.mandates.MandateModels.PartyRole.BENEFICIAL_OWNER;
        assertCode(()->approvals.customerParties(ADMIN,UUID.randomUUID(),bank.id(),parties.apply(List.of(new com.cbs.mandates.MandateModels.Party(joint,B,"60")))),"ORGANIZATION_PARTIES_REQUIRED");
        assertCode(()->approvals.customerParties(ADMIN,UUID.randomUUID(),bank.id(),parties.apply(List.of(new com.cbs.mandates.MandateModels.Party(holder,D,null),new com.cbs.mandates.MandateModels.Party(joint,B,null)))),"PARTY_OWNERSHIP_INVALID");
        assertCode(()->approvals.customerParties(ADMIN,UUID.randomUUID(),bank.id(),parties.apply(List.of(new com.cbs.mandates.MandateModels.Party(holder,D,null),new com.cbs.mandates.MandateModels.Party(joint,B,"60"),new com.cbs.mandates.MandateModels.Party(other,B,"50")))),"PARTY_OWNERSHIP_INVALID");
        assertCode(()->approvals.customerParties(ADMIN,UUID.randomUUID(),bank.id(),parties.apply(List.of(new com.cbs.mandates.MandateModels.Party(minor,D,null)))),"PARTY_MUST_BE_ADULT_INDIVIDUAL");
        assertCode(()->approvals.customerParties(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.mandates.MandateModels.Parties(service.get(ADMIN,bank.id()).version(),holder,List.of(new com.cbs.mandates.MandateModels.Party(joint,D,null)),"Wrong type")),"CUSTOMER_NOT_ORGANIZATION");
        approveHttp(approvals.customerParties(ADMIN,UUID.randomUUID(),bank.id(),parties.apply(List.of(new com.cbs.mandates.MandateModels.Party(holder,D,null),new com.cbs.mandates.MandateModels.Party(joint,B,"60")))).id());
        assertThat(mandates.parties(ADMIN,bank.id(),org)).extracting(p->p.role()+":"+p.ownershipPercent()).containsExactlyInAnyOrder("DIRECTOR:null","BENEFICIAL_OWNER:60");
        UUID orgAccount=accountFor(f,org,"ORG_01","700");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,orgAccount,"5",List.of(holder))),"MANDATE_REQUIRED");
        assertCode(()->approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,orgAccount,com.cbs.mandates.MandateModels.SigningRule.ANY_ONE,new com.cbs.mandates.MandateModels.AccountParty(other,com.cbs.mandates.MandateModels.AccountRole.SIGNATORY))),"MANDATE_SIGNATORY_NOT_AUTHORIZED");
        approveHttp(approvals.accountMandate(ADMIN,UUID.randomUUID(),bank.id(),mandate(bank,orgAccount,com.cbs.mandates.MandateModels.SigningRule.ANY_ONE,new com.cbs.mandates.MandateModels.AccountParty(holder,com.cbs.mandates.MandateModels.AccountRole.SIGNATORY))).id());
        postOperation(f,debit(f,orgAccount,"7",List.of(holder)));
        // A compliance restriction on a beneficial owner restricts the organization; on a signer, that signature.
        compliance.open(ADMIN,bank.id(),UUID.randomUUID(),new com.cbs.compliance.ComplianceModels.Open(service.get(ADMIN,bank.id()).version(),joint,com.cbs.compliance.ComplianceModels.Category.KYC_REVIEW,"KYC/JOINT","Fictional restriction"));
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,orgAccount,"1",List.of(holder))),"ORGANIZATION_PARTY_RESTRICTED");
        assertCode(()->approvals.operation(ADMIN,UUID.randomUUID(),bank.id(),debit(f,account,"1",List.of(holder,joint))),"MANDATE_SIGNATORY_INELIGIBLE");
    }
    private void manualJournal(Tenant bank,UUID debit,UUID credit,String amount)throws Exception{
        var input=new com.cbs.accounting.JournalModels.Change(service.get(ADMIN,bank.id()).version(),"PL-"+UUID.randomUUID(),"Fictional profit and loss entry",reference.get(ADMIN,bank.id()).businessDate(),bank.legalEntities().getFirst().id(),"INR",
            List.of(new com.cbs.accounting.JournalModels.Line(debit,com.cbs.accounting.JournalModels.Side.DEBIT,amount),new com.cbs.accounting.JournalModels.Line(credit,com.cbs.accounting.JournalModels.Side.CREDIT,amount)),null,"Fictional entry");
        var r=approvals.journal(ADMIN,UUID.randomUUID(),bank.id(),input);approveHttp(r.id());journals.deliver(r.id());
    }
    private java.math.BigDecimal net(String database,UUID ledger)throws Exception{
        try(var c=TestDatabases.bank(database);var st=c.createStatement();var r=st.executeQuery("SELECT coalesce(debit,0)-coalesce(credit,0) FROM bank.ledger_balance WHERE ledger_id='"+ledger+"'")){return r.next()?r.getBigDecimal(1):java.math.BigDecimal.ZERO;}
    }
    private void closeDay(Tenant bank)throws Exception{
        var day=days.dashboard(ADMIN,bank.id(),25,0);assertThat(day.blockers()).isEmpty();
        var closing=approvals.closeDay(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.DayCloseModels.Change(day.version(),day.businessDate(),day.nextBusinessDate(),"Close fictional day"));approveHttp(closing.id());days.deliver(closing.id());
    }
    @Test void monthAndYearEndClosesSealTheDayAndMoveProfitToRetainedEarnings()throws Exception{
        var f=fundingFixture("period-close");var bank=f.f().bank();fund(f,"1000");
        var income=adjustmentLedger(bank,"PC_FEES",com.cbs.accounting.ChartModels.Category.INCOME);var expense=adjustmentLedger(bank,"PC_COSTS",com.cbs.accounting.ChartModels.Category.EXPENSE);
        var retained=adjustmentLedger(bank,"PC_RETAINED",com.cbs.accounting.ChartModels.Category.EQUITY);var otherEquity=adjustmentLedger(bank,"PC_OTHER",com.cbs.accounting.ChartModels.Category.LIABILITY);
        var entity=bank.legalEntities().getFirst().id();UUID cash=f.source().id();
        java.util.function.Function<String,com.cbs.accounting.PeriodCloseModels.Run> run=reason->new com.cbs.accounting.PeriodCloseModels.Run(service.get(ADMIN,bank.id()).version(),reference.get(ADMIN,bank.id()).businessDate(),reason);
        // Without a policy, closing is optional and not offered.
        assertCode(()->periodCloses.preview(ADMIN,bank.id(),run.apply("Preview")),"PERIOD_CLOSE_POLICY_REQUIRED");
        assertCode(()->approvals.periodClosePolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.PeriodCloseModels.Policy(service.get(ADMIN,bank.id()).version(),12,List.of(new com.cbs.accounting.PeriodCloseModels.RetainedEarnings(entity,"INR",otherEquity)),"Wrong ledger")),"PERIOD_CLOSE_LEDGER_INVALID");
        var policy=approvals.periodClosePolicy(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.accounting.PeriodCloseModels.Policy(service.get(ADMIN,bank.id()).version(),12,List.of(new com.cbs.accounting.PeriodCloseModels.RetainedEarnings(entity,"INR",retained)),"Board-approved accounting policy"));
        assertCode(()->approvals.decide(ADMIN,policy.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");approveHttp(policy.id());
        // Mid-month: no close needed or allowed.
        assertCode(()->periodCloses.preview(ADMIN,bank.id(),run.apply("Too early")),"PERIOD_CLOSE_NOT_PERIOD_END");
        // Month end (30 November): the day cannot close until the month is closed; no journals are posted.
        jdbc.update("UPDATE platform.bank_settings SET business_date=DATE '2026-11-30' WHERE tenant_id=?",bank.id());
        manualJournal(bank,cash,income,"300");manualJournal(bank,expense,cash,"120.40");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).containsExactly("PERIOD_CLOSE_REQUIRED");
        var november=periodCloses.preview(ADMIN,bank.id(),run.apply("Review November"));
        assertThat(november.type()).isEqualTo(com.cbs.accounting.PeriodCloseModels.Type.MONTH);assertThat(november.journals()).isEmpty();
        assertThat(november.totals()).singleElement().satisfies(t->{assertThat(t.debits()).isEqualTo(t.credits());assertThat(t.income()).isEqualTo("300");assertThat(t.expense()).isEqualTo("120.4");assertThat(t.netResult()).isEqualTo("179.6");});
        var monthClose=approvals.periodClose(ADMIN,UUID.randomUUID(),bank.id(),run.apply("Close November"));approveHttp(monthClose.id());
        // Approval seals the day before posting completes.
        assertCode(()->approvals.funding(ADMIN,UUID.randomUUID(),bank.id(),fundingChange(f,"5","AFTER-CLOSE")),"PERIOD_CLOSE_FINALIZED");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("PERIOD_CLOSE_REQUIRED");
        periodCloses.deliver(monthClose.id());periodCloses.deliver(monthClose.id());
        assertThat(net("periods",income)).isEqualByComparingTo("-300");
        closeDay(bank);
        // Fiscal year end (31 December): closing journals move the result into retained earnings.
        jdbc.update("UPDATE platform.bank_settings SET business_date=DATE '2026-12-31' WHERE tenant_id=?",bank.id());
        manualJournal(bank,cash,income,"50");manualJournal(bank,expense,cash,"9.6");
        var december=periodCloses.preview(ADMIN,bank.id(),run.apply("Review year"));
        assertThat(december.type()).isEqualTo(com.cbs.accounting.PeriodCloseModels.Type.YEAR);
        assertThat(december.journals()).singleElement().satisfies(j->{assertThat(j.lines()).extracting(l->l.ledgerId()+":"+l.side()+":"+l.amount()).containsExactlyInAnyOrder(income+":DEBIT:350",expense+":CREDIT:130",retained+":CREDIT:220");});
        var yearClose=approvals.periodClose(ADMIN,UUID.randomUUID(),bank.id(),run.apply("Close the year"));approveHttp(yearClose.id());
        // A posting that sneaks in at the bank after approval is caught, not swept silently.
        try(var c=TestDatabases.bank("periods");var st=c.createStatement()){st.execute("CREATE TRIGGER block_close BEFORE INSERT ON bank.period_close FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        periodCloses.deliver(yearClose.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.period_close WHERE request_id=?",String.class,yearClose.id())).isEqualTo("PERIOD_CLOSE_DATABASE_UNAVAILABLE");
        try(var c=TestDatabases.bank("periods");var st=c.createStatement()){st.execute("DROP TRIGGER block_close ON bank.period_close");}
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{periodCloses.deliver(yearClose.id());t.setRollbackOnly();});
        periodCloses.deliver(yearClose.id());periodCloses.deliver(yearClose.id());
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.period_close WHERE request_id=?",Boolean.class,yearClose.id())).isTrue();
        assertThat(net("periods",income)).isEqualByComparingTo("0");assertThat(net("periods",expense)).isEqualByComparingTo("0");assertThat(net("periods",retained)).isEqualByComparingTo("-220");
        try(var c=TestDatabases.bank("periods");var st=c.createStatement()){
            try(var r=st.executeQuery("SELECT count(*) FROM bank.journal WHERE reference LIKE 'YEC-%'")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            assertThatThrownBy(()->st.executeUpdate("INSERT INTO bank.journal SELECT (jsonb_populate_record(null::bank.journal,to_jsonb(j)||jsonb_build_object('id',gen_random_uuid(),'reference','LATE-'||gen_random_uuid(),'reversal_of',null))).* FROM bank.journal j WHERE j.reference LIKE 'PL-%' AND j.business_date=DATE '2026-12-31' LIMIT 1")).isInstanceOf(SQLException.class).hasMessageContaining("Period is closed");
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.period_close")).isInstanceOf(SQLException.class);
        }
        // After the year-end close the income statement still shows the year; the balance sheet carries it in retained earnings.
        var year=financialStatements.incomeStatement(ADMIN,bank.id(),entity,"INR",java.time.LocalDate.of(2026,1,1),java.time.LocalDate.of(2026,12,31),null,null);
        assertThat(year.totals()).containsEntry("income","350").containsEntry("expense","130").containsEntry("netResult","220");
        var sheet=financialStatements.balanceSheet(ADMIN,bank.id(),entity,"INR",java.time.LocalDate.of(2026,12,31),java.time.LocalDate.of(2026,11,30));
        assertThat(sheet.balanced()).isTrue();
        assertThat(sheet.rows()).anyMatch(r->r.name().startsWith("Current period result")&&r.amount().equals("0")&&r.comparative().equals("179.6"));
        assertThat(sheet.rows()).anyMatch(r->r.code().equals("PC_RETAINED")&&r.amount().equals("220")&&r.comparative().equals("0"));
        closeDay(bank);
        assertThat(reference.get(ADMIN,bank.id()).businessDate().getYear()).isEqualTo(2027);
    }
    @Test void financialStatementsDefinitionsAndDashboardReconcileToTheLedger()throws Exception{
        var f=fundingFixture("reporting");var bank=f.f().bank();fund(f,"1000");UUID entity=bank.legalEntities().getFirst().id();UUID cash=f.source().id();
        var income=adjustmentLedger(bank,"RP_FEES",com.cbs.accounting.ChartModels.Category.INCOME);var expense=adjustmentLedger(bank,"RP_COSTS",com.cbs.accounting.ChartModels.Category.EXPENSE);var capital=adjustmentLedger(bank,"RP_CAPITAL",com.cbs.accounting.ChartModels.Category.EQUITY);
        manualJournal(bank,cash,income,"300");manualJournal(bank,expense,cash,"120.4");manualJournal(bank,cash,capital,"500");
        var today=reference.get(ADMIN,bank.id()).businessDate();
        var sheet=financialStatements.balanceSheet(ADMIN,bank.id(),entity,"INR",today,java.time.LocalDate.of(2020,1,1));
        assertThat(sheet.balanced()).isTrue();assertThat(sheet.totals()).containsEntry("assets","1679.6").containsEntry("liabilities","1000").containsEntry("equity","679.6");
        assertThat(sheet.rows()).anyMatch(r->r.name().startsWith("Current period result")&&r.amount().equals("179.6")&&r.comparative().equals("0"));
        assertThat(sheet.rows()).noneMatch(r->r.code().equals("RP_FEES"));
        var pnl=financialStatements.incomeStatement(ADMIN,bank.id(),entity,"INR",today,today,null,null);
        assertThat(pnl.totals()).containsEntry("income","300").containsEntry("expense","120.4").containsEntry("netResult","179.6");
        assertThat(pnl.rows()).anyMatch(r->r.code().equals("RP_FEES")&&r.amount().equals("300")).anyMatch(r->r.code().equals("RP_COSTS")&&r.amount().equals("120.4"));
        assertCode(()->financialStatements.balanceSheet(ADMIN,bank.id(),UUID.randomUUID(),"INR",today,null),"LEGAL_ENTITY_NOT_IN_BANK");
        assertCode(()->financialStatements.incomeStatement(ADMIN,bank.id(),entity,"INR",today.plusDays(1),today,null,null),"REPORT_FILTER_INVALID");
        String base="/api/v1/admin/tenants/"+bank.id()+"/reporting";String q="?legalEntityId="+entity+"&currency=INR&asOf="+today;
        mvc.perform(get(base+"/balance-sheet"+q)).andExpect(status().isUnauthorized());
        mvc.perform(get(base+"/balance-sheet"+q).header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        var csv=mvc.perform(admin(get(base+"/balance-sheet"+q+"&format=csv"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Balance sheet","\"1679.6\"","Total liabilities and equity");
        var pdf=mvc.perform(admin(get(base+"/income-statement?legalEntityId="+entity+"&currency=INR&from="+today+"&to="+today+"&format=pdf"))).andExpect(status().isOk()).andExpect(header().string("Content-Type","application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf)){assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc)).contains("Income statement","300","120.4","Net profit","179.6","Page 1 of 1");}
        mvc.perform(admin(get(base+"/balance-sheet"+q+"&format=xml"))).andExpect(status().isBadRequest());
        // Approved definitions turn a template into configuration.
        UUID depositsChart=f.f().product().liabilityAccountId();
        UUID feesChart=jdbc.queryForObject("SELECT chart_account_id FROM platform.ledger_registry WHERE id=?",UUID.class,income);UUID costsChart=jdbc.queryForObject("SELECT chart_account_id FROM platform.ledger_registry WHERE id=?",UUID.class,expense);
        java.util.function.Function<List<com.cbs.reporting.ReportModels.Line>,com.cbs.reporting.ReportModels.Definition> def=lines->new com.cbs.reporting.ReportModels.Definition(service.get(ADMIN,bank.id()).version(),"MGMT_SUMMARY","Management summary",com.cbs.reporting.ReportModels.Basis.BALANCE,lines,"Fictional template");
        assertCode(()->approvals.reportDefinition(ADMIN,UUID.randomUUID(),bank.id(),def.apply(List.of(new com.cbs.reporting.ReportModels.Line("A1","Unknown",List.of(UUID.randomUUID()),1)))),"REPORT_DEFINITION_ACCOUNT_UNKNOWN");
        assertCode(()->approvals.reportDefinition(ADMIN,UUID.randomUUID(),bank.id(),def.apply(List.of(new com.cbs.reporting.ReportModels.Line("A1","x",List.of(feesChart),1),new com.cbs.reporting.ReportModels.Line("A1","y",List.of(feesChart),1)))),"REPORT_DEFINITION_INVALID");
        var request=approvals.reportDefinition(ADMIN,UUID.randomUUID(),bank.id(),def.apply(List.of(
            new com.cbs.reporting.ReportModels.Line("10","Customer deposits",List.of(depositsChart),1),
            new com.cbs.reporting.ReportModels.Line("20","Fee income",List.of(feesChart),1),
            new com.cbs.reporting.ReportModels.Line("21","Less operating costs",List.of(costsChart),-1))));
        assertCode(()->approvals.decide(ADMIN,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own"),"SELF_APPROVAL_FORBIDDEN");approveHttp(request.id());
        var result=reportDefinitions.run(ADMIN,bank.id(),"MGMT_SUMMARY",entity,"INR",null,today);
        assertThat(result.lines()).containsExactly(List.of("10","Customer deposits","1000"),List.of("20","Fee income","300"),List.of("21","Less operating costs","-120.4"));
        assertCode(()->reportDefinitions.run(ADMIN,bank.id(),"NOPE",entity,"INR",null,today),"REPORT_DEFINITION_NOT_FOUND");
        mvc.perform(admin(get(base+"/definitions/MGMT_SUMMARY/run?legalEntityId="+entity+"&currency=INR&to="+today+"&format=csv"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"-120.4\"")));
        // Dashboard figures come from the same ledger.
        var board=managementDashboard.dashboard(ADMIN,bank.id());
        @SuppressWarnings("unchecked") var deposits=(Map<String,Object>)board.get("deposits");
        assertThat((List<?>)deposits.get("totals")).singleElement().satisfies(r->{@SuppressWarnings("unchecked") var m=(Map<String,Object>)r;assertThat(m).containsEntry("currency","INR").containsEntry("balance","1000").containsEntry("accounts",1);});
        assertThat((List<?>)deposits.get("topDepositors")).singleElement().satisfies(r->{@SuppressWarnings("unchecked") var m=(Map<String,Object>)r;assertThat(m).containsEntry("balance","1000").containsEntry("shareOfDeposits","100");});
        assertThat(board).containsKeys("loans","cash","work");
        mvc.perform(admin(get(base+"/dashboard"))).andExpect(status().isOk()).andExpect(jsonPath("$.deposits.totals[0].balance").value("1000"));
    }
    private void verifyDenominationInventory(FundingFixture f,UUID branch,UUID vault,Actor maker,Actor reviewer)throws Exception{
        var bank=f.f().bank();
        var catalogue=new com.cbs.cash.DenominationService.Change(service.get(ADMIN,bank.id()).version(),"INR",List.of(new com.cbs.cash.DenominationService.Denomination("1","COIN"),new com.cbs.cash.DenominationService.Denomination("10","NOTE"),new com.cbs.cash.DenominationService.Denomination("100","NOTE")),"Fictional inventory catalogue");
        var configuration=approvals.denominations(ADMIN,UUID.randomUUID(),bank.id(),catalogue);approveHttp(configuration.id());
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_INVENTORY_REQUIRED");
        String balance=bankCash.position(bank,cash.location(bank.id(),vault)).balance();long coins=new java.math.BigDecimal(balance).longValueExact();
        postCash(maker,reviewer,bank,cashChange(bank,branch,com.cbs.cash.CashModels.Kind.INVENTORY,vault,null,null,null,List.of(new com.cbs.cash.CashModels.Count("1",coins))));
        assertThat(bankCash.position(bank,cash.location(bank.id(),vault)).inventoryEnabled()).isTrue();
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),cashChange(bank,branch,com.cbs.cash.CashModels.Kind.DISPATCH,vault,null,f.source().id(),"10",List.of(new com.cbs.cash.CashModels.Count("10",1)))),"CASH_DENOMINATION_INSUFFICIENT");
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),cashChange(bank,branch,com.cbs.cash.CashModels.Kind.RECEIVE,vault,null,f.source().id(),"7",List.of(new com.cbs.cash.CashModels.Count("7",1)))),"CASH_DENOMINATION_INVALID");
        var incoming=postCash(maker,reviewer,bank,cashChange(bank,branch,com.cbs.cash.CashModels.Kind.RECEIVE,vault,null,f.source().id(),"100",List.of(new com.cbs.cash.CashModels.Count("100",1))));
        assertThat(bankCash.position(bank,cash.location(bank.id(),vault)).inventory()).contains(new com.cbs.cash.CashModels.Count("100",1));
        postCash(maker,reviewer,bank,cashChange(bank,branch,com.cbs.cash.CashModels.Kind.DISPATCH,vault,null,f.source().id(),"100",List.of(new com.cbs.cash.CashModels.Count("100",1))));
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,incoming)),"CASH_DENOMINATION_INSUFFICIENT");
        var printKey=UUID.randomUUID();var receipt=cash.receipt(maker,bank.id(),branch,incoming,printKey);assertThat(receipt.get("copiesGenerated")).isEqualTo(1L);
        assertThat(cash.receipt(maker,bank.id(),branch,incoming,printKey).get("number")).isEqualTo(receipt.get("number"));
        assertThat(cash.receipt(maker,bank.id(),branch,incoming,UUID.randomUUID()).get("copiesGenerated")).isEqualTo(2L);
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.cash_note_entry")).isInstanceOf(SQLException.class);}
    }
    private com.cbs.cash.CashModels.Change transitChange(Tenant bank,UUID branch,UUID vault,UUID ledger,String amount,UUID dispatch,UUID destination,boolean returned){
        var date=reference.get(ADMIN,bank.id()).businessDate();
        return new com.cbs.cash.CashModels.Change(service.get(ADMIN,bank.id()).version(),dispatch==null?com.cbs.cash.CashModels.Kind.DISPATCH:com.cbs.cash.CashModels.Kind.RECEIVE,branch,vault,null,ledger,null,null,null,null,amount,date,"TRANSIT-"+UUID.randomUUID(),dispatch==null?null:List.of(new com.cbs.cash.CashModels.Count(amount,1)),"Fictional shipment evidence",null,null,null,null,new com.cbs.cash.CashModels.Transit(dispatch,destination,dispatch==null?date:null,returned));
    }
    private void verifyInterbranchCash(FundingFixture f,UUID sourceBranch,UUID vault,Actor sender,Actor sourceChecker)throws Exception{
        var bank=f.f().bank();
        var destination=branchService.create(ADMIN,bank.id(),new com.cbs.access.AccessModels.BranchInput(bank.legalEntities().getFirst().id(),"DEST","Destination branch","Fictional transfer route"));
        var staff=branchStaff(bank.id(),destination.id(),"transit-receiver",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var checkStaff=branchStaff(bank.id(),destination.id(),"transit-checker",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var receiver=new Actor(TestIdentity.ISSUER,staff.subject(),false,UUID.randomUUID());var checker=new Actor(TestIdentity.ISSUER,checkStaff.subject(),false,UUID.randomUUID());
        var target=cashLocation(bank,destination.id(),f.source(),"DEST_VAULT",com.cbs.cash.CashModels.Type.VAULT);
        var ledgerRequest=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,"TRANSIT",f.source().chartAccountId(),"Cash in transit",true));approveHttp(ledgerRequest.id());ledger.deliver(ledgerRequest.id());
        var transitLedger=ledger.list(ADMIN,bank.id(),"TRANSIT",null,null,25,0).items().getFirst().definition().id();
        for(UUID scope:java.util.Arrays.asList(null,staff.id())){var limit=approvals.branchLimit(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,bank.id()).version(),destination.id(),scope,"INR","CASH_RECEIVE","2000","5000","Fictional receipt limit"));approveHttp(limit.id());}
        assertThat(cash.transitDestinations(sender,bank.id(),sourceBranch)).anyMatch(x->target.equals(x.get("id")));
        assertCode(()->cash.transit(receiver,bank.id(),sourceBranch),"BRANCH_ACCESS_DENIED");
        assertCode(()->approvals.cash(sender,UUID.randomUUID(),bank.id(),transitChange(bank,sourceBranch,vault,transitLedger,"100",null,vault,false)),"CASH_SCOPE_MISMATCH");
        assertCode(()->approvals.cash(sender,UUID.randomUUID(),bank.id(),transitChange(bank,sourceBranch,vault,f.source().id(),"100",null,target,false)),"CASH_LEDGER_USED");
        var cancelled=approvals.cash(sender,UUID.randomUUID(),bank.id(),transitChange(bank,sourceBranch,vault,transitLedger,"100",null,target,false));
        approvals.decide(sender,cancelled.id(),com.cbs.approvals.ApprovalModels.Decision.CANCEL,"Shipment not sent");
        assertThat(cash.transit(sender,bank.id(),sourceBranch)).isEmpty();
        var request=approvals.cash(sender,UUID.randomUUID(),bank.id(),transitChange(bank,sourceBranch,vault,transitLedger,"100",null,target,false));
        assertCode(()->approvals.decide(receiver,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Wrong branch"),"BRANCH_ACCESS_DENIED");
        approvals.decide(sourceChecker,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verified sealed shipment");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{cash.deliver(request.id());tx.setRollbackOnly();});
        cash.deliver(request.id());cash.deliver(request.id());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,request.id())).isNull();
        assertThat(bankCash.position(bank,cash.location(bank.id(),target)).balance()).isEqualTo("0");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_IN_TRANSIT_OUTSTANDING");
        var bypass=new com.cbs.accounting.JournalModels.Entry(UUID.randomUUID(),bank.id(),"TRANSIT-BYPASS", "Attempt bypass",reference.get(ADMIN,bank.id()).businessDate(),bank.legalEntities().getFirst().id(),"INR",List.of(new com.cbs.accounting.JournalModels.Line(transitLedger,com.cbs.accounting.JournalModels.Side.CREDIT,"1"),new com.cbs.accounting.JournalModels.Line(f.source().id(),com.cbs.accounting.JournalModels.Side.DEBIT,"1")),null);
        assertCode(()->bankJournals.post(bank,new com.cbs.accounting.JournalModels.Dispatch(bypass,ADMIN.subject(),CHECKER.issuer(),CHECKER.subject(),UUID.randomUUID())),"JOURNAL_DATABASE_UNAVAILABLE");
        assertThat(cash.transit(receiver,bank.id(),destination.id()).getFirst().get("outstanding").toString()).isEqualTo("100");
        assertCode(()->approvals.cash(sender,UUID.randomUUID(),bank.id(),cashReverse(bank,sourceBranch,request.id())),"CASH_REVERSAL_INVALID");
        assertCode(()->approvals.cash(sender,UUID.randomUUID(),bank.id(),cashChange(bank,sourceBranch,com.cbs.cash.CashModels.Kind.RECEIVE,vault,null,transitLedger,"1",null)),"CASH_TRANSIT_MANAGED");
        assertCode(()->approvals.cash(receiver,UUID.randomUUID(),bank.id(),transitChange(bank,destination.id(),target,transitLedger,"101",request.id(),null,false)),"CASH_TRANSIT_AMOUNT_EXCEEDED");
        assertCode(()->approvals.cash(sender,UUID.randomUUID(),bank.id(),transitChange(bank,sourceBranch,vault,transitLedger,"20",request.id(),null,false)),"CASH_SCOPE_MISMATCH");
        var receipt=approvals.cash(receiver,UUID.randomUUID(),bank.id(),transitChange(bank,destination.id(),target,transitLedger,"20",request.id(),null,false));
        assertCode(()->approvals.decide(receiver,receipt.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own receipt"),"SELF_APPROVAL_FORBIDDEN");
        approvals.decide(checker,receipt.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Physical cash counted");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_transit BEFORE INSERT ON bank.cash_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        cash.deliver(receipt.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,receipt.id())).isEqualTo("CASH_DATABASE_UNAVAILABLE");
        assertThat(bankCash.position(bank,cash.location(bank.id(),target)).balance()).isEqualTo("0");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("DROP TRIGGER fail_transit ON bank.cash_entry");}
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{cash.deliver(receipt.id());tx.setRollbackOnly();});cash.deliver(receipt.id());cash.deliver(receipt.id());
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),target)).balance())).isEqualByComparingTo("20");
        postCash(sender,sourceChecker,bank,transitChange(bank,sourceBranch,vault,transitLedger,"30",request.id(),null,true));
        postCash(receiver,checker,bank,transitChange(bank,destination.id(),target,transitLedger,"50",request.id(),null,false));
        var shipment=cash.transit(receiver,bank.id(),destination.id()).getFirst();
        assertThat(new java.math.BigDecimal(shipment.get("outstanding").toString())).isZero();
        assertThat(new java.math.BigDecimal(shipment.get("received").toString())).isEqualByComparingTo("70");
        assertThat(new java.math.BigDecimal(shipment.get("returned").toString())).isEqualByComparingTo("30");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CASH_IN_TRANSIT_OUTSTANDING");
        assertCode(()->approvals.cash(receiver,UUID.randomUUID(),bank.id(),transitChange(bank,destination.id(),target,transitLedger,"1",request.id(),null,false)),"CASH_TRANSIT_AMOUNT_EXCEEDED");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){
            try(var rs=st.executeQuery("SELECT count(*),sum(amount) FROM bank.cash_transit_receipt")){rs.next();assertThat(rs.getInt(1)).isEqualTo(3);assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("100");}
            assertThatThrownBy(()->st.executeUpdate("DELETE FROM bank.cash_transit_receipt")).isInstanceOf(SQLException.class);
        }
        mvc.perform(get("/api/v1/banks/"+bank.id()+"/branches/"+destination.id()+"/operations/cash/transit").header("Authorization","Bearer "+TestIdentity.token(staff.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$[0].dispatch_id").value(request.id().toString()));
    }
    private UUID adjustmentLedger(Tenant bank,String code,com.cbs.accounting.ChartModels.Category category)throws Exception{
        var chartInput=new com.cbs.accounting.ChartModels.Change(service.get(ADMIN,bank.id()).version(),null,code,code,"Fictional adjustment mapping",category,category==com.cbs.accounting.ChartModels.Category.EXPENSE?com.cbs.accounting.ChartModels.Balance.DEBIT:com.cbs.accounting.ChartModels.Balance.CREDIT,com.cbs.accounting.ChartModels.Kind.POSTING,null,true,"Test adjustment ledger");
        approveHttp(approvals.chart(ADMIN,UUID.randomUUID(),bank.id(),chartInput).id());
        var posting=chart.list(ADMIN,bank.id(),code,null,null,null,25,0).items().getFirst();
        var request=approvals.ledger(ADMIN,UUID.randomUUID(),bank.id(),ledgerChange(bank,null,code,posting.id(),code,true));approveHttp(request.id());ledger.deliver(request.id());
        return ledger.list(ADMIN,bank.id(),code,null,null,25,0).items().getFirst().definition().id();
    }
    private com.cbs.cash.CashModels.Change adjustmentChange(Tenant bank,UUID branch,UUID location,UUID incident,UUID counter,com.cbs.cash.CashModels.Kind kind,String amount,List<com.cbs.cash.CashModels.Count> counts){
        return new com.cbs.cash.CashModels.Change(service.get(ADMIN,bank.id()).version(),kind,branch,location,null,counter,null,null,null,null,amount,reference.get(ADMIN,bank.id()).businessDate(),"ADJUST-"+UUID.randomUUID(),counts,"Fictional independently investigated adjustment",null,null,incident);
    }
    private void verifyCashAdjustments(Tenant bank,UUID branch,UUID vault,UUID wrongLedger,Actor reporter,Actor maker,Actor reviewer)throws Exception{
        var expense=adjustmentLedger(bank,"CASH_LOSS",com.cbs.accounting.ChartModels.Category.EXPENSE);
        var income=adjustmentLedger(bank,"CASH_GAIN",com.cbs.accounting.ChartModels.Category.INCOME);
        var shortage=com.cbs.cash.CashModels.Kind.SHORTAGE;var overage=com.cbs.cash.CashModels.Kind.OVERAGE;
        var physical=List.of(new com.cbs.cash.CashModels.Count("50",15));var incident=UUID.randomUUID();
        cash.reportException(reporter,bank.id(),branch,incident,new com.cbs.cash.CashExceptionInput(service.get(ADMIN,bank.id()).version(),vault,reference.get(ADMIN,bank.id()).businessDate(),physical,"Fictional shortage of 50"));
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),adjustmentChange(bank,branch,vault,incident,wrongLedger,shortage,"50",physical)),"CASH_ADJUSTMENT_LEDGER_INVALID");
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),adjustmentChange(bank,branch,vault,incident,expense,shortage,"49",physical)),"CASH_ADJUSTMENT_MISMATCH");
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),adjustmentChange(bank,branch,vault,incident,expense,shortage,"50",physical)),"TRANSACTION_LIMIT_REQUIRED");
        for(String op:List.of("CASH_SHORTAGE","CASH_OVERAGE")){
            approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"INR",op,"100","200","Fictional adjustment caps")).id());
            UUID staff=jdbc.queryForObject("SELECT id FROM platform.staff_identity WHERE issuer=? AND subject=?",UUID.class,maker.issuer(),maker.subject());
            for(UUID scope:java.util.Arrays.asList(null,staff))approveHttp(approvals.branchLimit(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,bank.id()).version(),branch,scope,"INR",op,"100","200","Fictional branch adjustment caps")).id());
        }
        var input=adjustmentChange(bank,branch,vault,incident,expense,shortage,"50",physical);var key=UUID.randomUUID();
        assertCode(()->approvals.cash(reporter,key,bank.id(),input),"BRANCH_PERMISSION_DENIED");
        String path="/api/v1/banks/"+bank.id()+"/branches/"+branch+"/operations/cash";
        mvc.perform(get(path+"/adjustment-ledgers?locationId="+vault).header("Authorization","Bearer "+TestIdentity.token(maker.subject(),false))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        var response=mvc.perform(post(path).header("Authorization","Bearer "+TestIdentity.token(maker.subject(),false)).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var request=json.readValue(response,com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(approvals.cash(maker,key,bank.id(),input).id()).isEqualTo(request.id());
        assertCode(()->approvals.decide(maker,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Own adjustment"),"SELF_APPROVAL_FORBIDDEN");
        approvals.decide(reviewer,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent investigation");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).contains("CASH_EXCEPTION_OPEN","CASH_DELIVERY_PENDING");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_adjustment BEFORE INSERT ON bank.cash_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        cash.deliver(request.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,request.id())).isEqualTo("CASH_DATABASE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.cash_exception_resolution WHERE case_id=?",Integer.class,incident)).isZero();
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),vault)).balance())).isEqualByComparingTo("800");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("DROP TRIGGER fail_adjustment ON bank.cash_entry");}
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{cash.deliver(request.id());t.setRollbackOnly();});cash.deliver(request.id());cash.deliver(request.id());
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),vault)).balance())).isEqualByComparingTo("750");
        assertThat(jdbc.queryForObject("SELECT adjustment_request_id FROM platform.cash_exception_resolution WHERE case_id=?",UUID.class,incident)).isEqualTo(request.id());
        assertCode(()->approvals.cash(maker,UUID.randomUUID(),bank.id(),adjustmentChange(bank,branch,vault,incident,expense,shortage,"50",physical)),"CASH_ADJUSTMENT_UNAVAILABLE");
        assertThat(days.dashboard(ADMIN,bank.id(),25,0).blockers()).doesNotContain("CASH_EXCEPTION_OPEN").contains("CASH_COUNT_REQUIRED");
        var independent=branchStaff(bank.id(),branch,"cash-adjustment-checker",com.cbs.access.AccessModels.BranchRole.BRANCH_MANAGER);
        var finalChecker=new Actor(TestIdentity.ISSUER,independent.subject(),false,UUID.randomUUID());
        var extra=List.of(new com.cbs.cash.CashModels.Count("25",31));var overCase=UUID.randomUUID();
        cash.reportException(reviewer,bank.id(),branch,overCase,new com.cbs.cash.CashExceptionInput(service.get(ADMIN,bank.id()).version(),vault,reference.get(ADMIN,bank.id()).businessDate(),extra,"Fictional overage of 25"));
        var gain=approvals.cash(maker,UUID.randomUUID(),bank.id(),adjustmentChange(bank,branch,vault,overCase,income,overage,"25",extra));
        assertCode(()->approvals.decide(reviewer,gain.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Reporter cannot review"),"SELF_APPROVAL_FORBIDDEN");
        approvals.decide(finalChecker,gain.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent overage review");cash.deliver(gain.id());
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),vault)).balance())).isEqualByComparingTo("775");
        assertThat(bankCash.position(bank,cash.location(bank.id(),vault)).reconciled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT completed FROM platform.cash_delivery WHERE request_id=?",Boolean.class,gain.id())).isTrue();
    }
    private com.cbs.cash.CashModels.Change cashReverse(Tenant bank,UUID branch,UUID original){
        var old=json.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.cash_delivery WHERE request_id=?",String.class,original),com.cbs.cash.CashModels.Entry.class);
        return new com.cbs.cash.CashModels.Change(service.get(ADMIN,bank.id()).version(),com.cbs.cash.CashModels.Kind.REVERSAL,branch,old.location().id(),old.destination()==null?null:old.destination().id(),null,null,null,null,null,old.change().amount(),reference.get(ADMIN,bank.id()).businessDate(),"REV-"+UUID.randomUUID(),null,"Reverse verified fictional transaction",null,null,null,original);
    }
    private void verifyCashReversals(FundingFixture f,UUID branch,UUID vault,UUID till,Actor owner,Actor manager,Actor reviewer)throws Exception{
        var bank=f.f().bank();var originals=new java.util.HashMap<String,UUID>();
        for(String kind:List.of("RECEIVE","DISPATCH","TRANSFER","DEPOSIT","WITHDRAWAL"))originals.put(kind,jdbc.queryForObject("SELECT request_id FROM platform.cash_delivery WHERE tenant_id=? AND payload->'change'->>'kind'=? AND completed",UUID.class,bank.id(),kind));
        for(String operation:List.of("CASH_REVERSAL","WITHDRAWAL_REVERSAL","FUNDING_REVERSAL")){
            approveHttp(approvals.limits(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.TransactionLimits.Change(service.get(ADMIN,bank.id()).version(),"INR",operation,"2000","5000","Fictional reversal limits")).id());
            for(String subject:java.util.Arrays.asList(null,owner.subject(),manager.subject())){
                UUID staff=subject==null?null:jdbc.queryForObject("SELECT id FROM platform.staff_identity WHERE issuer=? AND subject=?",UUID.class,owner.issuer(),subject);
                approveHttp(approvals.branchLimit(ADMIN,UUID.randomUUID(),bank.id(),new com.cbs.access.BranchOperations.Limit(service.get(ADMIN,bank.id()).version(),branch,staff,"INR",operation,"2000","5000","Fictional scoped reversal limit")).id());
            }
        }
        assertCode(()->approvals.cash(manager,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("RECEIVE"))),"CASH_INSUFFICIENT");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("DISPATCH"))),"BRANCH_PERMISSION_DENIED");
        var dispatchReverse=approvals.cash(manager,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("DISPATCH")));
        assertCode(()->approvals.decide(manager,dispatchReverse.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"SELF_APPROVAL_FORBIDDEN");
        approvals.decide(reviewer,dispatchReverse.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Independent dispatch reversal");cash.deliver(dispatchReverse.id());
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("WITHDRAWAL"))),"CASH_SESSION_REQUIRED");
        postCash(owner,reviewer,bank,cashChange(bank,branch,com.cbs.cash.CashModels.Kind.OPEN,till,null,null,null,List.of(new com.cbs.cash.CashModels.Count("50",4))));
        postCash(owner,reviewer,bank,cashReverse(bank,branch,originals.get("TRANSFER")));
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("100");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("DEPOSIT"))),"INSUFFICIENT_AVAILABLE_BALANCE");
        var direct=new com.cbs.accounts.OperationModels.Change(service.get(ADMIN,bank.id()).version(),com.cbs.accounts.OperationModels.Kind.WITHDRAWAL_REVERSAL,f.account(),null,null,originals.get("WITHDRAWAL"),"100",reference.get(ADMIN,bank.id()).businessDate(),"BYPASS-CASH","Must use cash route");
        assertCode(()->approvals.operation(owner,UUID.randomUUID(),bank.id(),direct),"CASH_MANAGED");
        var reversedWithdrawal=postCash(owner,reviewer,bank,cashReverse(bank,branch,originals.get("WITHDRAWAL")));
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("200");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,reversedWithdrawal)),"CASH_REVERSAL_INVALID");
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("WITHDRAWAL"))),"JOURNAL_ALREADY_REVERSED");
        // Preserve the customer's contractual minimum balance when reversing a deposit.
        fund(f,"10");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isEqualByComparingTo("200");
        postCash(owner,reviewer,bank,cashChange(bank,branch,com.cbs.cash.CashModels.Kind.TRANSFER,till,vault,null,"200",null));
        assertCode(()->approvals.cash(owner,UUID.randomUUID(),bank.id(),cashReverse(bank,branch,originals.get("DEPOSIT"))),"CASH_INSUFFICIENT");
        postCash(owner,reviewer,bank,cashChange(bank,branch,com.cbs.cash.CashModels.Kind.TRANSFER,vault,till,null,"200",null));
        String path="/api/v1/banks/"+bank.id()+"/branches/"+branch+"/operations/cash";
        mvc.perform(get(path+"/reversible").header("Authorization","Bearer "+TestIdentity.token("operator",false))).andExpect(status().isForbidden());
        mvc.perform(get(path+"/reversible").header("Authorization","Bearer "+TestIdentity.token(owner.subject(),false))).andExpect(status().isOk());
        var input=cashReverse(bank,branch,originals.get("DEPOSIT"));var key=UUID.randomUUID();
        var response=mvc.perform(post(path).header("Authorization","Bearer "+TestIdentity.token(owner.subject(),false)).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var request=json.readValue(response,com.cbs.approvals.ApprovalModels.Request.class);
        assertThat(request.proposedValues().get("cashDelta")).isEqualTo("-200");
        assertThat(request.proposedValues().get("customerOperation")).isEqualTo("FUNDING_REVERSAL");
        assertCode(()->approvals.decide(owner,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Self"),"BRANCH_PERMISSION_DENIED");
        approvals.decide(reviewer,request.id(),com.cbs.approvals.ApprovalModels.Decision.APPROVE,"Verify original cash receipt");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("CREATE TRIGGER fail_reversal BEFORE INSERT ON bank.cash_entry FOR EACH ROW EXECUTE FUNCTION bank.reject_ledger_event_change()");}
        cash.deliver(request.id());assertThat(jdbc.queryForObject("SELECT failure_code FROM platform.cash_delivery WHERE request_id=?",String.class,request.id())).isEqualTo("CASH_DATABASE_UNAVAILABLE");
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("210");
        try(var c=TestDatabases.bank("cash");var st=c.createStatement()){st.execute("DROP TRIGGER fail_reversal ON bank.cash_entry");}
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{cash.deliver(request.id());t.setRollbackOnly();});cash.deliver(request.id());cash.deliver(request.id());
        assertThat(approvals.cash(owner,key,bank.id(),input).id()).isEqualTo(request.id());
        assertThat(funding.dashboard(ADMIN,bank.id(),f.account(),25,0).balance().postedBalance()).isEqualTo("10");
        assertThat(new java.math.BigDecimal(bankCash.position(bank,cash.location(bank.id(),till)).balance())).isZero();
        assertThat(bankCash.position(bank,cash.location(bank.id(),till)).reconciled()).isTrue();
        assertCode(()->operations.deliver(request.id()),"CASH_MANAGED");
    }
    private com.cbs.cash.CashModels.Change customerCashChange(Tenant bank,UUID branch,UUID till,com.cbs.cash.CashModels.Kind kind,UUID account,UUID liability,String amount){
        return new com.cbs.cash.CashModels.Change(service.get(ADMIN,bank.id()).version(),kind,branch,till,null,null,null,null,null,null,amount,reference.get(ADMIN,bank.id()).businessDate(),"CUSTOMER-CASH-"+UUID.randomUUID(),null,"Fictional customer cash receipt",account,liability);
    }
}
