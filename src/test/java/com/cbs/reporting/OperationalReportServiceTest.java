package com.cbs.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.contracts.ContractModels;
import com.cbs.products.ProductModels;
import com.cbs.reference.ReferenceDataService;
import com.cbs.reference.ReferenceModels;
import com.cbs.tenancy.BankDatabaseProvisioner;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OperationalReportServiceTest {
    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private BankDatabaseProvisioner databases;
    private ReferenceDataService reference;
    private OperationalReportService service;

    private final UUID bankId = UUID.randomUUID();
    private final Actor adminActor = new Actor("test-issuer", "admin-user", true, UUID.randomUUID());
    private final Actor regularActor = new Actor("test-issuer", "regular-user", false, UUID.randomUUID());
    private final TenantModels.Binding binding = new TenantModels.Binding(
            "bank_ref_1", "V49", null, UUID.randomUUID(), Instant.now().plusSeconds(3600));
    private final TenantModels.Tenant bank = new TenantModels.Tenant(
            bankId, "TESTBANK", "Test Bank Plc", "en", TenantModels.Status.ACTIVE, 1L,
            Instant.EPOCH, Instant.EPOCH, List.of(), binding);
    private final LocalDate testDate = LocalDate.of(2026, 11, 14);

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        tenants = mock(TenantRepository.class);
        databases = mock(BankDatabaseProvisioner.class);
        reference = mock(ReferenceDataService.class);
        service = new OperationalReportService(jdbc, tenants, databases, reference);

        when(tenants.get(eq(bankId), anyBoolean())).thenReturn(bank);
        var refSettings = new ReferenceModels.Settings(
                bankId, 1L, List.of(), List.of("en"), "en", "Africa/Nairobi", testDate, List.of(6, 7), List.of());
        when(reference.read(any())).thenReturn(refSettings);
    }

    @Test
    void nonAdminIsForbidden() {
        assertThatThrownBy(() -> service.depositRegister(regularActor, bankId, null, null))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("ACCESS_DENIED"));
    }

    @Test
    void depositRegisterSummarizesBalancesAndGeneratesTable() throws Exception {
        UUID branchId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();
        UUID custId = UUID.randomUUID();

        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rsExists = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rsExists);
        when(rsExists.next()).thenReturn(true);
        when(rsExists.getBoolean(1)).thenReturn(true);

        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        ResultSet rsPos = mock(ResultSet.class);
        when(rsPos.next()).thenReturn(true, false);
        when(rsPos.getObject(1, UUID.class)).thenReturn(accId);
        when(rsPos.getBigDecimal(2)).thenReturn(new BigDecimal("15000.00"));

        ResultSet rsCust = mock(ResultSet.class);
        when(rsCust.next()).thenReturn(true, false);
        when(rsCust.getObject(1, UUID.class)).thenReturn(custId);
        when(rsCust.getString(2)).thenReturn("Jane Doe");

        when(stmt.executeQuery(contains("customer_position"))).thenReturn(rsPos);
        when(stmt.executeQuery(contains("SELECT id,payload->'profile'->>'fullName' FROM bank.customer"))).thenReturn(rsCust);

        when(databases.openOwned(eq(bankId), anyString())).thenReturn(conn);

        var acc = new OperationalReportService.AccRow(
                accId, custId, "1001-0001", "CUST-001", "SAV-01", "KES", "ACTIVE", "Nairobi Branch", branchId);
        when(jdbc.query(contains("FROM platform.account_registry"), any(RowMapper.class), eq(bankId)))
                .thenReturn(List.of(acc));

        var result = service.depositRegister(adminActor, bankId, "KES", null);

        assertThat(result.reportName()).isEqualTo("deposit-register");
        assertThat(result.bankName()).isEqualTo("Test Bank Plc");
        assertThat(result.businessDate()).isEqualTo(testDate);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().get("accountNumber")).isEqualTo("1001-0001");
        assertThat(result.items().getFirst().get("customerName")).isEqualTo("Jane Doe");
        assertThat(result.items().getFirst().get("balance")).isEqualTo("15000");
        assertThat(result.summary().get("totalAccounts")).isEqualTo(1);
        assertThat(result.table()).isNotNull();
        assertThat(result.table().columns()).contains("Account Number", "Customer Name", "Currency", "Balance");
    }

    @Test
    void cashReportSummarizesLocationsAndCurrencies() throws Exception {
        UUID branchId = UUID.randomUUID();
        UUID locId = UUID.randomUUID();

        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rsExists = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rsExists);
        when(rsExists.next()).thenReturn(true);
        when(rsExists.getBoolean(1)).thenReturn(true);

        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        ResultSet rsCash = mock(ResultSet.class);
        when(rsCash.next()).thenReturn(true, false);
        when(rsCash.getObject(1, UUID.class)).thenReturn(locId);
        when(rsCash.getBigDecimal(2)).thenReturn(new BigDecimal("50000.00"));
        when(stmt.executeQuery(contains("cash_position"))).thenReturn(rsCash);

        when(databases.openOwned(eq(bankId), anyString())).thenReturn(conn);

        var loc = new OperationalReportService.LocRow(
                locId, "TILL-01", "TELLER_TILL", "KES", "Nairobi Branch", branchId);
        when(jdbc.query(contains("FROM platform.cash_location"), any(RowMapper.class), eq(bankId)))
                .thenReturn(List.of(loc));

        var result = service.cashReport(adminActor, bankId, branchId);

        assertThat(result.reportName()).isEqualTo("cash-report");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().get("code")).isEqualTo("TILL-01");
        assertThat(result.items().getFirst().get("kind")).isEqualTo("TELLER_TILL");
        assertThat(result.items().getFirst().get("balance")).isEqualTo("50000");
        assertThat(result.table()).isNotNull();
        assertThat(result.table().columns()).contains("Location Code", "Branch", "Type", "Currency", "Book Balance");
    }

    @Test
    void taxRemittanceCalculatesWithholdingAndExciseDuty() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);

        Map<String, Object> run1 = Map.of(
                "runId", UUID.randomUUID().toString(),
                "productCode", "SAV-01",
                "currency", "KES",
                "grossInterest", new BigDecimal("1000.00"),
                "taxDeducted", new BigDecimal("150.00"),
                "feesAssessed", new BigDecimal("0.00"),
                "date", "2026-11-14"
        );
        Map<String, Object> run2 = Map.of(
                "runId", UUID.randomUUID().toString(),
                "productCode", "TX-01",
                "currency", "KES",
                "grossInterest", new BigDecimal("0.00"),
                "taxDeducted", new BigDecimal("0.00"),
                "feesAssessed", new BigDecimal("200.00"),
                "date", "2026-11-14"
        );

        when(jdbc.query(contains("FROM platform.servicing_run"), any(RowMapper.class), eq(bankId), eq(from), eq(to)))
                .thenReturn(List.of(run1, run2));

        var result = service.taxRemittance(adminActor, bankId, from, to);

        assertThat(result.reportName()).isEqualTo("tax-remittance");
        assertThat(result.items()).hasSize(2);
        assertThat(result.table()).isNotNull();
        assertThat(result.table().columns()).contains("Date", "Product", "Currency", "Gross Interest", "WHT (15%)", "Fees Assessed", "Excise Duty (20%)");
        var summary = result.summary();
        assertThat(summary.get("totalGrossInterest")).isEqualTo("1000");
        assertThat(summary.get("totalWithholdingTaxRemittance")).isEqualTo("150");
        assertThat(summary.get("totalExciseDutyRemittance")).isEqualTo("40");
    }

    @Test
    void exceptionAgeingBucketsDiscrepanciesProperly() {
        var d1 = new OperationalReportService.Discrepancy("CASH_DISCREPANCY", "DISC-001", LocalDate.of(2026, 11, 11), "Till short 500 KES");
        var d2 = new OperationalReportService.Discrepancy("PAYMENT_EXCEPTION", "PAY-999", LocalDate.of(2026, 10, 5), "Type: REVERSAL, Amount: 100 KES");

        when(jdbc.query(contains("FROM platform.cash_exception"), any(RowMapper.class), eq(bankId)))
                .thenReturn(List.of(d1));

        when(jdbc.query(contains("FROM platform.payment_case"), any(RowMapper.class), eq(bankId)))
                .thenReturn(List.of(d2));

        when(jdbc.query(contains("FROM platform.compliance_case"), any(RowMapper.class), eq(bankId)))
                .thenReturn(List.of());

        var result = service.exceptionAgeing(adminActor, bankId);

        assertThat(result.reportName()).isEqualTo("exception-ageing");
        assertThat(result.items()).hasSize(2);
        assertThat(result.summary().get("totalUnresolvedExceptions")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        var buckets = (Map<String, Object>) result.summary().get("ageingBuckets");
        assertThat(buckets.get("0_7_DAYS")).isEqualTo(1);
        assertThat(buckets.get("31_60_DAYS")).isEqualTo(1);
        assertThat(buckets.get("8_30_DAYS")).isEqualTo(0);
        assertThat(buckets.get("61_PLUS_DAYS")).isEqualTo(0);
        assertThat(result.table().columns()).contains("Type", "Reference", "Opened Date", "Age (Days)", "Bucket", "Details");
    }

    @Test
    void loanProvisionsClassifiesIFRS9Stages() {
        UUID contractId1 = UUID.randomUUID();

        when(jdbc.queryForList(contains("SELECT id FROM platform.contract"), eq(UUID.class), eq(bankId)))
                .thenReturn(List.of(contractId1));

        var acc = new com.cbs.accounts.AccountModels.Definition(
                UUID.randomUUID(), bankId, "LOAN-01", 1, UUID.randomUUID(), "CUST-01",
                UUID.randomUUID(), "Entity", "Personal Loan", com.cbs.accounts.AccountModels.State.ACTIVE,
                new ProductModels.Product(UUID.randomUUID(), "LN-01", "Loan", "Loan product",
                        ProductModels.Type.CURRENT, "KES", "0", "0", UUID.randomUUID(), "1000",
                        true, 1, Instant.now(), Instant.now()),
                Instant.now());

        var terms1 = new ContractModels.Open(1, ContractModels.Type.LOAN, acc.id(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "100000.00", "12.0", "0", 365, testDate.minusMonths(1), testDate.plusMonths(11), 12, "AGR-101", "Loan");

        var contractDef1 = new ContractModels.Definition(contractId1, bankId, acc, terms1);
        var initialPos1 = new ContractModels.Position("100000.00", "0.00", "0.00", testDate.minusMonths(1), false);
        var p1 = new ContractModels.Posting(UUID.randomUUID(), contractDef1, ContractModels.Kind.OPEN,
                testDate.minusMonths(1), initialPos1, initialPos1, "0", "0", null, "Open", null);

        when(jdbc.query(contains("FROM platform.contract_delivery"), any(RowMapper.class), eq(bankId), eq(contractId1)))
                .thenReturn(List.of(p1));

        var result = service.loanProvisions(adminActor, bankId, "KES");

        assertThat(result.reportName()).isEqualTo("loan-provisions");
        assertThat(result.items()).hasSize(1);
        var item1 = result.items().getFirst();
        assertThat(item1.get("agreementReference")).isEqualTo("AGR-101");
        assertThat(item1.get("stage")).isEqualTo("STAGE_1_PERFORMING");
        assertThat(item1.get("grossCarryingAmount")).isEqualTo("100000");
        assertThat(item1.get("recommendedAllowance")).isEqualTo("1000");
        assertThat(item1.get("netCarryingAmount")).isEqualTo("99000");
        assertThat(result.table().columns()).contains("Agreement Ref", "Currency", "Gross Balance", "DPD", "IFRS 9 Stage", "Allowance", "Net Carrying Amount");
    }
}
