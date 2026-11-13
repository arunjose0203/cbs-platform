package com.cbs.reporting;

import com.cbs.access.AccessService;
import com.cbs.contracts.ContractModels;
import com.cbs.contracts.LoanArrears;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operational and regulatory reports (Round 14 / F09, F19):
 * 1. Deposit Register: customer accounts, products, posted balances, branches.
 * 2. Loan Register: loan contracts, arrears ageing, borrower info, non-performing classification.
 * 3. Daily Cash Report: vaults, teller tills, balances, physical count statuses.
 * 4. Tax Remittance Report: interest paid, 15% withholding tax (WHT), fee income, 20% excise duty.
 * 5. Exception Ageing: unresolved cash discrepancies, payment exceptions, compliance cases (0-7d, 8-30d, 31-60d, 61+d).
 * 6. Loan Provisions & Staging: IFRS 9 / CBK Stage 1 (Normal), Stage 2 (Watch), Stage 3 (NPL).
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class OperationalReportService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BigDecimal WHT_RATE = new BigDecimal("0.15");     // 15% KRA Withholding Tax on resident interest
    private static final BigDecimal EXCISE_RATE = new BigDecimal("0.20");  // 20% KRA Excise Duty on financial fees

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final BankDatabaseProvisioner databases;
    private final com.cbs.reference.ReferenceDataService reference;

    public OperationalReportService(JdbcTemplate jdbc, TenantRepository tenants, BankDatabaseProvisioner databases, com.cbs.reference.ReferenceDataService reference) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.databases = databases;
        this.reference = reference;
    }

    public record ReportResult(String reportName, String bankName, LocalDate businessDate, Instant generatedAt,
                               Map<String, Object> summary, List<Map<String, Object>> items, TableOutput.Table table) {}

    static record AccRow(UUID id, UUID customerId, String accountNumber, String customerNumber, String productCode, String currency, String state, String branchName, UUID branchId) {}
    static record LocRow(UUID id, String code, String kind, String currency, String branchName, UUID branchId) {}
    static record Discrepancy(String type, String reference, LocalDate createdDate, String description) {}
    static record StageTotals(BigDecimal gross, BigDecimal allowance, int count) {
        StageTotals add(BigDecimal g, BigDecimal a) {
            return new StageTotals(gross.add(g), allowance.add(a), count + 1);
        }
    }

    /** 1. Deposit Register */
    public ReportResult depositRegister(Actor actor, UUID bankId, String currencyFilter, UUID branchFilter) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var date = reference.read(bank).businessDate();
        var posted = new HashMap<UUID, BigDecimal>();
        var names = new HashMap<UUID, String>();

        try (var c = databases.openOwned(bank.id(), bank.databaseBinding().databaseReference())) {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            if (exists(c, "bank.customer_position")) {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT account_id,posted FROM bank.customer_position")) {
                    while (r.next()) posted.put(r.getObject(1, UUID.class), r.getBigDecimal(2));
                }
            }
            if (exists(c, "bank.customer")) {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,payload->'profile'->>'fullName' FROM bank.customer")) {
                    while (r.next()) names.put(r.getObject(1, UUID.class), r.getString(2));
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new TenantException(503, "REPORT_DATABASE_UNAVAILABLE", "Deposit register could not be read");
        }

        var accounts = jdbc.query(
            "SELECT a.id, a.customer_id, a.account_number, c.customer_number, d.payload->'terms'->>'code', d.payload->'terms'->>'currency', a.state, coalesce(b.display_name,'Unassigned'), b.id " +
            "FROM platform.account_registry a " +
            "JOIN platform.account_delivery d ON d.request_id = a.request_id " +
            "JOIN platform.customer_registry c ON c.id = a.customer_id " +
            "LEFT JOIN platform.account_branch ab ON ab.account_id = a.id " +
            "LEFT JOIN platform.branch b ON b.id = ab.branch_id " +
            "WHERE a.tenant_id = ? AND a.state <> 'CLOSED' ORDER BY a.account_number",
            (r, n) -> new AccRow(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getObject(9, UUID.class)),
            bankId
        );

        var items = new ArrayList<Map<String, Object>>();
        var tableRows = new ArrayList<List<String>>();
        var totalBalances = new HashMap<String, BigDecimal>();
        int totalAccounts = 0;

        for (var a : accounts) {
            if (currencyFilter != null && !currencyFilter.isBlank() && !currencyFilter.equalsIgnoreCase(a.currency())) continue;
            if (branchFilter != null && !branchFilter.equals(a.branchId())) continue;

            var balance = posted.getOrDefault(a.id(), BigDecimal.ZERO);
            totalBalances.merge(a.currency(), balance, BigDecimal::add);
            totalAccounts++;

            String custName = names.getOrDefault(a.customerId(), "");
            var item = new LinkedHashMap<String, Object>();
            item.put("accountNumber", a.accountNumber());
            item.put("customerNumber", a.customerNumber());
            item.put("customerName", custName);
            item.put("productCode", a.productCode());
            item.put("currency", a.currency());
            item.put("balance", text(balance));
            item.put("state", a.state());
            item.put("branch", a.branchName());
            items.add(item);

            tableRows.add(List.of(a.accountNumber(), a.customerNumber(), custName, a.productCode(), a.currency(), text(balance), a.state(), a.branchName()));
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalAccounts", totalAccounts);
        var balMap = new LinkedHashMap<String, String>();
        totalBalances.forEach((k, v) -> balMap.put(k, text(v)));
        summary.put("totalBalances", balMap);

        var table = new TableOutput.Table(
            "Deposit Register — " + bank.displayName(),
            List.of("As at " + date + " · Total accounts: " + totalAccounts),
            List.of("Account Number", "Customer No", "Customer Name", "Product", "Currency", "Balance", "Status", "Branch"),
            Set.of(5),
            tableRows,
            Set.of(),
            Instant.now()
        );

        audit(actor, bankId, "DEPOSIT_REGISTER_VIEWED", bank.version(), Map.of("count", totalAccounts));
        return new ReportResult("deposit-register", bank.displayName(), date, Instant.now(), summary, items, table);
    }

    /** 2. Loan Register */
    public ReportResult loanRegister(Actor actor, UUID bankId, String currencyFilter) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var date = reference.read(bank).businessDate();
        var contracts = jdbc.queryForList("SELECT id FROM platform.contract WHERE tenant_id=? AND kind='LOAN' AND status='ACTIVE' ORDER BY id", UUID.class, bankId);

        var names = new HashMap<UUID, String>();
        try (var c = databases.openOwned(bank.id(), bank.databaseBinding().databaseReference())) {
            c.setReadOnly(true);
            if (exists(c, "bank.customer")) {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,payload->'profile'->>'fullName' FROM bank.customer")) {
                    while (r.next()) names.put(r.getObject(1, UUID.class), r.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new TenantException(503, "REPORT_DATABASE_UNAVAILABLE", "Loan register customer details unavailable");
        }

        var items = new ArrayList<Map<String, Object>>();
        var tableRows = new ArrayList<List<String>>();
        var totalOutstanding = new HashMap<String, BigDecimal>();
        var totalOverdue = new HashMap<String, BigDecimal>();
        int totalLoans = 0;
        int nonPerformingLoans = 0;

        for (var contractId : contracts) {
            var events = jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at,id",
                (r, n) -> JSON.readValue(r.getString(1), ContractModels.Posting.class), bankId, contractId);
            if (events.isEmpty()) continue;

            var first = events.getFirst();
            var terms = first.contract().terms();
            String currency = first.contract().account().terms().currency();
            if (currencyFilter != null && !currencyFilter.isBlank() && !currencyFilter.equalsIgnoreCase(currency)) continue;

            LoanArrears.Report report;
            try {
                report = LoanArrears.calculate(events, date);
            } catch (RuntimeException e) {
                continue;
            }

            BigDecimal out = new BigDecimal(report.outstanding().total());
            BigDecimal ovd = new BigDecimal(report.overdue().total());
            totalOutstanding.merge(currency, out, BigDecimal::add);
            totalOverdue.merge(currency, ovd, BigDecimal::add);
            totalLoans++;

            long dpd = report.daysPastDue();
            String bucket = LoanArrears.bucket(dpd);
            boolean isNpl = dpd > 90;
            if (isNpl) nonPerformingLoans++;

            String borrowerName = names.getOrDefault(first.contract().account().customerId(), "");
            var item = new LinkedHashMap<String, Object>();
            item.put("agreementReference", terms.agreementReference());
            item.put("borrowerName", borrowerName);
            item.put("currency", currency);
            item.put("principalAmount", terms.principal());
            item.put("outstanding", text(out));
            item.put("overdue", text(ovd));
            item.put("daysPastDue", dpd);
            item.put("bucket", bucket);
            item.put("ratePercent", terms.annualRatePercent());
            item.put("startDate", terms.startDate());
            item.put("maturityDate", terms.maturityDate());
            item.put("nonPerforming", isNpl);
            items.add(item);

            tableRows.add(List.of(terms.agreementReference(), borrowerName, currency, terms.principal(), text(out), text(ovd), String.valueOf(dpd), bucket, isNpl ? "NPL" : "PERFORMING"));
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalLoans", totalLoans);
        summary.put("nonPerformingLoans", nonPerformingLoans);
        var outMap = new LinkedHashMap<String, String>();
        totalOutstanding.forEach((k, v) -> outMap.put(k, text(v)));
        summary.put("totalOutstanding", outMap);
        var ovdMap = new LinkedHashMap<String, String>();
        totalOverdue.forEach((k, v) -> ovdMap.put(k, text(v)));
        summary.put("totalOverdue", ovdMap);

        var table = new TableOutput.Table(
            "Loan Register — " + bank.displayName(),
            List.of("As at " + date + " · Active loans: " + totalLoans + " (NPL: " + nonPerformingLoans + ")"),
            List.of("Agreement Ref", "Borrower", "Currency", "Principal", "Outstanding", "Overdue", "DPD", "Bucket", "Status"),
            Set.of(3, 4, 5),
            tableRows,
            Set.of(),
            Instant.now()
        );

        audit(actor, bankId, "LOAN_REGISTER_VIEWED", bank.version(), Map.of("count", totalLoans));
        return new ReportResult("loan-register", bank.displayName(), date, Instant.now(), summary, items, table);
    }

    /** 3. Teller and Cash Report */
    public ReportResult cashReport(Actor actor, UUID bankId, UUID branchFilter) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var date = reference.read(bank).businessDate();
        var cashPositions = new HashMap<UUID, BigDecimal>();

        try (var c = databases.openOwned(bank.id(), bank.databaseBinding().databaseReference())) {
            c.setReadOnly(true);
            if (exists(c, "bank.cash_position")) {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,balance FROM bank.cash_position")) {
                    while (r.next()) cashPositions.put(r.getObject(1, UUID.class), r.getBigDecimal(2));
                }
            }
        } catch (SQLException e) {
            throw new TenantException(503, "REPORT_DATABASE_UNAVAILABLE", "Cash positions unavailable");
        }

        var locations = jdbc.query(
            "SELECT l.id, l.code, l.kind, l.currency, coalesce(b.display_name,'Unassigned'), b.id " +
            "FROM platform.cash_location l " +
            "LEFT JOIN platform.branch b ON b.id = l.branch_id " +
            "WHERE l.tenant_id = ? AND l.applied ORDER BY b.display_name, l.code",
            (r, n) -> new LocRow(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getObject(6, UUID.class)),
            bankId
        );

        var items = new ArrayList<Map<String, Object>>();
        var tableRows = new ArrayList<List<String>>();
        var totalsByCurrency = new HashMap<String, BigDecimal>();

        for (var l : locations) {
            if (branchFilter != null && !branchFilter.equals(l.branchId())) continue;

            var bal = cashPositions.getOrDefault(l.id(), BigDecimal.ZERO);
            totalsByCurrency.merge(l.currency(), bal, BigDecimal::add);

            var item = new LinkedHashMap<String, Object>();
            item.put("code", l.code());
            item.put("kind", l.kind());
            item.put("currency", l.currency());
            item.put("branch", l.branchName());
            item.put("balance", text(bal));
            items.add(item);

            tableRows.add(List.of(l.code(), l.branchName(), l.kind(), l.currency(), text(bal)));
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalLocations", items.size());
        var totalsMap = new LinkedHashMap<String, String>();
        totalsByCurrency.forEach((k, v) -> totalsMap.put(k, text(v)));
        summary.put("totalCash", totalsMap);

        var table = new TableOutput.Table(
            "Daily Cash & Teller Custody Report — " + bank.displayName(),
            List.of("Business Date: " + date + " · Cash locations: " + items.size()),
            List.of("Location Code", "Branch", "Type", "Currency", "Book Balance"),
            Set.of(4),
            tableRows,
            Set.of(),
            Instant.now()
        );

        audit(actor, bankId, "CASH_REPORT_VIEWED", bank.version(), Map.of("count", items.size()));
        return new ReportResult("cash-report", bank.displayName(), date, Instant.now(), summary, items, table);
    }

    /** 4. Tax Remittance Report (Interest WHT and Fee Excise Duty) */
    public ReportResult taxRemittance(Actor actor, UUID bankId, LocalDate from, LocalDate to) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var date = reference.read(bank).businessDate();
        var start = from != null ? from : date.withDayOfMonth(1);
        var end = to != null ? to : date;

        if (start.isAfter(end)) throw new TenantException(400, "REPORT_FILTER_INVALID", "From date cannot be after to date");

        // Compute interest paid and WHT from completed servicing runs in period
        var runs = jdbc.query(
            "SELECT r.id, r.product_code, r.currency, coalesce((r.result->>'grossInterest')::numeric, 0), coalesce((r.result->>'tax')::numeric, 0), coalesce((r.result->>'fees')::numeric, 0), r.completed_at::date " +
            "FROM platform.servicing_run r WHERE r.tenant_id = ? AND r.completed_at::date BETWEEN ? AND ?",
            (r, n) -> Map.of(
                "runId", r.getObject(1, UUID.class).toString(),
                "productCode", r.getString(2),
                "currency", r.getString(3),
                "grossInterest", r.getBigDecimal(4),
                "taxDeducted", r.getBigDecimal(5),
                "feesAssessed", r.getBigDecimal(6),
                "date", r.getDate(7).toString()
            ),
            bankId, start, end
        );

        BigDecimal totalGrossInterest = BigDecimal.ZERO;
        BigDecimal totalWht = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal totalExcise = BigDecimal.ZERO;

        var items = new ArrayList<Map<String, Object>>();
        var tableRows = new ArrayList<List<String>>();

        for (var r : runs) {
            var gross = (BigDecimal) r.get("grossInterest");
            var tax = (BigDecimal) r.get("taxDeducted");
            var fee = (BigDecimal) r.get("feesAssessed");
            var excise = fee.multiply(EXCISE_RATE).setScale(2, RoundingMode.HALF_EVEN);

            totalGrossInterest = totalGrossInterest.add(gross);
            totalWht = totalWht.add(tax);
            totalFees = totalFees.add(fee);
            totalExcise = totalExcise.add(excise);

            var item = new LinkedHashMap<String, Object>();
            item.put("date", r.get("date"));
            item.put("productCode", r.get("productCode"));
            item.put("currency", r.get("currency"));
            item.put("grossInterest", text(gross));
            item.put("withholdingTax", text(tax));
            item.put("feeIncome", text(fee));
            item.put("exciseDuty", text(excise));
            items.add(item);

            tableRows.add(List.of((String) r.get("date"), (String) r.get("productCode"), (String) r.get("currency"), text(gross), text(tax), text(fee), text(excise)));
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("periodFrom", start);
        summary.put("periodTo", end);
        summary.put("whtStatutoryRate", "15%");
        summary.put("exciseStatutoryRate", "20%");
        summary.put("totalGrossInterest", text(totalGrossInterest));
        summary.put("totalWithholdingTaxRemittance", text(totalWht));
        summary.put("totalFeeIncome", text(totalFees));
        summary.put("totalExciseDutyRemittance", text(totalExcise));

        var table = new TableOutput.Table(
            "Tax Remittance Report (KRA WHT & Excise) — " + bank.displayName(),
            List.of("Period: " + start + " to " + end + " · WHT: 15% · Excise: 20%"),
            List.of("Date", "Product", "Currency", "Gross Interest", "WHT (15%)", "Fees Assessed", "Excise Duty (20%)"),
            Set.of(3, 4, 5, 6),
            tableRows,
            Set.of(),
            Instant.now()
        );

        audit(actor, bankId, "TAX_REMITTANCE_REPORT_VIEWED", bank.version(), Map.of("from", start.toString(), "to", end.toString()));
        return new ReportResult("tax-remittance", bank.displayName(), date, Instant.now(), summary, items, table);
    }

    /** 5. Exception Ageing Report */
    public ReportResult exceptionAgeing(Actor actor, UUID bankId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var date = reference.read(bank).businessDate();

        var list = new ArrayList<Discrepancy>();

        // Cash exceptions
        list.addAll(jdbc.query(
            "SELECT 'CASH_DISCREPANCY', e.id::text, e.created_at::date, 'Amount: ' || e.currency || ' ' || e.amount || ' (' || e.kind || ')' " +
            "FROM platform.cash_exception e WHERE e.tenant_id = ? AND NOT EXISTS(SELECT 1 FROM platform.cash_exception_resolution r WHERE r.case_id=e.id)",
            (r, n) -> new Discrepancy(r.getString(1), r.getString(2), r.getDate(3).toLocalDate(), r.getString(4)),
            bankId
        ));

        // Payment cases
        list.addAll(jdbc.query(
            "SELECT 'PAYMENT_EXCEPTION', p.id::text, p.created_at::date, 'Type: ' || p.exception_type || ', Amount: ' || p.amount || ' ' || p.currency " +
            "FROM platform.payment_case p WHERE p.tenant_id = ? AND p.state NOT IN ('RESOLVED','CANCELLED')",
            (r, n) -> new Discrepancy(r.getString(1), r.getString(2), r.getDate(3).toLocalDate(), r.getString(4)),
            bankId
        ));

        // Compliance cases
        list.addAll(jdbc.query(
            "SELECT 'COMPLIANCE_CASE', c.id::text, c.created_at::date, 'Customer: ' || c.customer_id || ', Status: ' || c.status " +
            "FROM platform.compliance_case c WHERE c.tenant_id = ? AND c.status <> 'CLEARED'",
            (r, n) -> new Discrepancy(r.getString(1), r.getString(2), r.getDate(3).toLocalDate(), r.getString(4)),
            bankId
        ));

        var buckets = new LinkedHashMap<String, Integer>();
        buckets.put("0_7_DAYS", 0);
        buckets.put("8_30_DAYS", 0);
        buckets.put("31_60_DAYS", 0);
        buckets.put("61_PLUS_DAYS", 0);

        var items = new ArrayList<Map<String, Object>>();
        var tableRows = new ArrayList<List<String>>();

        for (var d : list) {
            long days = Math.max(0, ChronoUnit.DAYS.between(d.createdDate(), date));
            String bKey = days <= 7 ? "0_7_DAYS" : days <= 30 ? "8_30_DAYS" : days <= 60 ? "31_60_DAYS" : "61_PLUS_DAYS";
            buckets.merge(bKey, 1, Integer::sum);

            var item = new LinkedHashMap<String, Object>();
            item.put("type", d.type());
            item.put("reference", d.reference());
            item.put("openedDate", d.createdDate().toString());
            item.put("ageDays", days);
            item.put("ageBucket", bKey);
            item.put("description", d.description());
            items.add(item);

            tableRows.add(List.of(d.type(), d.reference(), d.createdDate().toString(), String.valueOf(days), bKey, d.description()));
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalUnresolvedExceptions", list.size());
        summary.put("ageingBuckets", buckets);

        var table = new TableOutput.Table(
            "Exception Ageing & Unresolved Items — " + bank.displayName(),
            List.of("As at " + date + " · Total open items: " + list.size()),
            List.of("Type", "Reference", "Opened Date", "Age (Days)", "Bucket", "Details"),
            Set.of(3),
            tableRows,
            Set.of(),
            Instant.now()
        );

        audit(actor, bankId, "EXCEPTION_AGEING_VIEWED", bank.version(), Map.of("openCount", list.size()));
        return new ReportResult("exception-ageing", bank.displayName(), date, Instant.now(), summary, items, table);
    }

    /** 6. Loan Provisions & IFRS 9 / CBK Staging */
    public ReportResult loanProvisions(Actor actor, UUID bankId, String currencyFilter) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var date = reference.read(bank).businessDate();
        var contracts = jdbc.queryForList("SELECT id FROM platform.contract WHERE tenant_id=? AND kind='LOAN' AND status='ACTIVE' ORDER BY id", UUID.class, bankId);

        var stageMap = new LinkedHashMap<String, StageTotals>();
        stageMap.put("STAGE_1_PERFORMING", new StageTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0));
        stageMap.put("STAGE_2_UNDERPERFORMING", new StageTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0));
        stageMap.put("STAGE_3_NON_PERFORMING", new StageTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0));

        var items = new ArrayList<Map<String, Object>>();
        var tableRows = new ArrayList<List<String>>();

        for (var contractId : contracts) {
            var events = jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at,id",
                (r, n) -> JSON.readValue(r.getString(1), ContractModels.Posting.class), bankId, contractId);
            if (events.isEmpty()) continue;

            var first = events.getFirst();
            var terms = first.contract().terms();
            String currency = first.contract().account().terms().currency();
            if (currencyFilter != null && !currencyFilter.isBlank() && !currencyFilter.equalsIgnoreCase(currency)) continue;

            LoanArrears.Report report;
            try {
                report = LoanArrears.calculate(events, date);
            } catch (RuntimeException e) {
                continue;
            }

            BigDecimal outstanding = new BigDecimal(report.outstanding().total());
            long dpd = report.daysPastDue();

            // CBK & IFRS 9 Staging
            String stage;
            BigDecimal recAllowance;
            if (dpd <= 30) {
                stage = "STAGE_1_PERFORMING";
                recAllowance = outstanding.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_EVEN); // 1% general provision
            } else if (dpd <= 90) {
                stage = "STAGE_2_UNDERPERFORMING";
                recAllowance = outstanding.multiply(new BigDecimal("0.03")).setScale(2, RoundingMode.HALF_EVEN); // 3% watch provision
            } else {
                stage = "STAGE_3_NON_PERFORMING";
                recAllowance = outstanding.multiply(dpd <= 180 ? new BigDecimal("0.20") : new BigDecimal("1.00")).setScale(2, RoundingMode.HALF_EVEN);
            }

            stageMap.compute(stage, (k, v) -> v == null ? new StageTotals(outstanding, recAllowance, 1) : v.add(outstanding, recAllowance));

            var item = new LinkedHashMap<String, Object>();
            item.put("contractId", contractId.toString());
            item.put("agreementReference", terms.agreementReference());
            item.put("currency", currency);
            item.put("grossCarryingAmount", text(outstanding));
            item.put("daysPastDue", dpd);
            item.put("stage", stage);
            item.put("recommendedAllowance", text(recAllowance));
            item.put("netCarryingAmount", text(outstanding.subtract(recAllowance)));
            items.add(item);

            tableRows.add(List.of(terms.agreementReference(), currency, text(outstanding), String.valueOf(dpd), stage, text(recAllowance), text(outstanding.subtract(recAllowance))));
        }

        var summary = new LinkedHashMap<String, Object>();
        stageMap.forEach((st, stVal) -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("loansCount", stVal.count());
            m.put("grossCarryingAmount", text(stVal.gross()));
            m.put("allowance", text(stVal.allowance()));
            m.put("netCarryingAmount", text(stVal.gross().subtract(stVal.allowance())));
            BigDecimal coverage = stVal.gross().signum() == 0 ? BigDecimal.ZERO : stVal.allowance().multiply(BigDecimal.valueOf(100)).divide(stVal.gross(), 2, RoundingMode.HALF_EVEN);
            m.put("coveragePercent", text(coverage) + "%");
            summary.put(st, m);
        });

        var table = new TableOutput.Table(
            "Loan Provisions & IFRS 9 Staging Report — " + bank.displayName(),
            List.of("As at " + date + " · Regulatory provisioning rules applied"),
            List.of("Agreement Ref", "Currency", "Gross Balance", "DPD", "IFRS 9 Stage", "Allowance", "Net Carrying Amount"),
            Set.of(2, 5, 6),
            tableRows,
            Set.of(),
            Instant.now()
        );

        audit(actor, bankId, "LOAN_PROVISIONS_REPORT_VIEWED", bank.version(), Map.of("loans", items.size()));
        return new ReportResult("loan-provisions", bank.displayName(), date, Instant.now(), summary, items, table);
    }

    private static boolean exists(Connection c, String table) throws SQLException {
        try (var s = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getBoolean(1);
            }
        }
    }

    private void audit(Actor actor, UUID bankId, String action, long version, Map<String, Object> details) {
        tenants.audit(actor, bankId, action, version, com.cbs.tenancy.TenantModels.Status.ACTIVE, com.cbs.tenancy.TenantModels.Status.ACTIVE, action, null, null, details);
    }

    private static String text(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
