package com.cbs.credit;

import static com.cbs.contracts.ContractModels.*;
import static com.cbs.credit.CreditBureauModels.*;
import com.cbs.access.AccessService;
import com.cbs.access.LendingAccess;
import com.cbs.contracts.ContractModels;
import com.cbs.contracts.LoanArrears;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.Status;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class CreditBureauService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final LendingAccess lending;
    private final ReferenceDataService reference;
    private final TransactionTemplate tx;

    public CreditBureauService(JdbcTemplate jdbc, TenantRepository tenants, LendingAccess lending,
                               ReferenceDataService reference, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.lending = lending;
        this.reference = reference;
        this.tx = new TransactionTemplate(manager);
    }

    private static TenantException error(String code, String message) {
        return new TenantException(409, code, message);
    }

    public InquiryResponse inquire(Actor actor, UUID bankId, InquiryRequest req) {
        return tx.execute(status -> {
            var bank = tenants.get(bankId, true);
            if (bank.status() != Status.ACTIVE) throw error("ACCOUNT_BANK_NOT_ACTIVE", "Bank is not active");

            if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=?)",
                    Boolean.class, bankId, req.customerId())) {
                throw error("CUSTOMER_NOT_FOUND", "Customer not found");
            }

            // Derive deterministic score based on national ID hash (range 300 - 850)
            int score = deriveScore(req.nationalId());
            RiskGrade grade = score >= 750 ? RiskGrade.LOW
                    : score >= 650 ? RiskGrade.MEDIUM
                    : score >= 550 ? RiskGrade.HIGH
                    : RiskGrade.VERY_HIGH;

            int openAccounts = (score % 4) + 1;
            int delinquent = score < 600 ? 1 : 0;
            BigDecimal totalExposure = BigDecimal.valueOf((score * 1250L) + 50000).setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal delinquentAmount = delinquent > 0 ? BigDecimal.valueOf(25000).setScale(2, RoundingMode.HALF_EVEN) : BigDecimal.ZERO;

            Map<String, Object> payload = Map.of(
                    "provider", req.provider().name(),
                    "nationalId", req.nationalId(),
                    "score", score,
                    "riskGrade", grade.name(),
                    "openAccounts", openAccounts,
                    "delinquentAccounts", delinquent,
                    "totalExposure", totalExposure.toPlainString(),
                    "delinquentAmount", delinquentAmount.toPlainString(),
                    "inquiryReason", req.reason(),
                    "inquiryDate", LocalDate.now().toString()
            );

            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.update("INSERT INTO platform.credit_bureau_inquiry(id,tenant_id,customer_id,national_id,provider,score,risk_grade,open_accounts,delinquent_accounts,total_exposure,delinquent_amount,report_payload,inquired_by,inquired_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                    id, bankId, req.customerId(), req.nationalId().trim(), req.provider().name(), score, grade.name(),
                    openAccounts, delinquent, totalExposure, delinquentAmount, JSON.writeValueAsString(payload), actor.subject(), Timestamp.from(now));

            tenants.audit(actor, bankId, "CREDIT_BUREAU_INQUIRY", bank.version(), bank.status(), bank.status(),
                    "CRB inquiry via " + req.provider() + " for national ID " + req.nationalId(), null, null,
                    Map.of("inquiryId", id, "customerId", req.customerId(), "score", score, "riskGrade", grade.name()));

            return new InquiryResponse(id, req.customerId(), req.nationalId(), req.provider(), score, grade,
                    openAccounts, delinquent, totalExposure.toPlainString(), delinquentAmount.toPlainString(),
                    JSON.writeValueAsString(payload), now, actor.subject());
        });
    }

    public List<InquiryResponse> listInquiries(Actor actor, UUID bankId, UUID customerId) {
        AccessService.admin(actor);
        return jdbc.query("SELECT id,customer_id,national_id,provider,score,risk_grade,open_accounts,delinquent_accounts,total_exposure,delinquent_amount,report_payload,inquired_at,inquired_by FROM platform.credit_bureau_inquiry WHERE tenant_id=? AND customer_id=? ORDER BY inquired_at DESC",
                (rs, rowNum) -> new InquiryResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("national_id"),
                        BureauProvider.valueOf(rs.getString("provider")),
                        rs.getInt("score"),
                        RiskGrade.valueOf(rs.getString("risk_grade")),
                        rs.getInt("open_accounts"),
                        rs.getInt("delinquent_accounts"),
                        rs.getBigDecimal("total_exposure").toPlainString(),
                        rs.getBigDecimal("delinquent_amount").toPlainString(),
                        rs.getString("report_payload"),
                        rs.getTimestamp("inquired_at").toInstant(),
                        rs.getString("inquired_by")
                ), bankId, customerId);
    }

    public CrbReport generateReport(Actor actor, UUID bankId, LocalDate asOf) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        LocalDate reportDate = asOf != null ? asOf : reference.read(bank).businessDate();

        var rows = jdbc.queryForList("SELECT c.id, c.account_id, c.payload, c.position, c.status, a.payload->'terms'->>'currency' as currency, a.customer_id, cust.payload->'profile'->>'fullName' as full_name, cust.payload->'profile'->>'identityNumber' as national_id FROM platform.contract c JOIN platform.account_registry a ON a.id=c.account_id JOIN platform.customer_registry cust ON cust.id=a.customer_id WHERE c.tenant_id=? AND c.kind='LOAN' ORDER BY c.created_at",
                bankId);

        List<CrbLoanRecord> records = new ArrayList<>();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalOverdue = BigDecimal.ZERO;

        for (var r : rows) {
            UUID contractId = (UUID) r.get("id");
            UUID accountId = (UUID) r.get("account_id");
            UUID customerId = (UUID) r.get("customer_id");
            String currency = (String) r.get("currency");
            String fullName = Objects.toString(r.get("full_name"), "Unknown");
            String nationalId = Objects.toString(r.get("national_id"), "N/A");
            String contractStatus = (String) r.get("status");

            var def = JSON.readValue(r.get("payload").toString(), Definition.class);
            var pos = JSON.readValue(r.get("position").toString(), Position.class);

            var events = jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at, id",
                    (rs, n) -> JSON.readValue(rs.getString(1), Posting.class), bankId, contractId);

            long dpd = 0;
            BigDecimal overdueAmount = BigDecimal.ZERO;
            LocalDate lastPaymentDate = null;
            BigDecimal lastPaymentAmount = BigDecimal.ZERO;

            if (!events.isEmpty()) {
                var report = LoanArrears.calculate(events, reportDate);
                dpd = report.daysPastDue();
                overdueAmount = new BigDecimal(report.overdue().total());

                for (var p : events) {
                    if (p.kind() == Kind.REPAY && !p.businessDate().isAfter(reportDate)) {
                        lastPaymentDate = p.businessDate();
                        lastPaymentAmount = new BigDecimal(p.customerMovement()).abs();
                    }
                }
            }

            BigDecimal currentPrincipal = new BigDecimal(pos.principal());
            BigDecimal currentInterest = new BigDecimal(pos.interest());
            BigDecimal balance = currentPrincipal.add(currentInterest);

            String assetClassification;
            if ("WRITTEN_OFF".equals(contractStatus) || dpd > 180) {
                assetClassification = "LOSS";
            } else if (dpd > 90) {
                assetClassification = "DOUBTFUL";
            } else if (dpd > 30) {
                assetClassification = "SUBSTANDARD";
            } else if (dpd > 0) {
                assetClassification = "WATCH";
            } else {
                assetClassification = "PERFORMING";
            }

            totalOutstanding = totalOutstanding.add(balance);
            totalOverdue = totalOverdue.add(overdueAmount);

            records.add(new CrbLoanRecord(
                    def.terms().agreementReference(),
                    accountId,
                    customerId,
                    nationalId,
                    fullName,
                    currency,
                    def.terms().principal(),
                    pos.principal(),
                    pos.interest(),
                    balance.toPlainString(),
                    overdueAmount.toPlainString(),
                    dpd,
                    assetClassification,
                    lastPaymentDate,
                    lastPaymentAmount.toPlainString()
            ));
        }

        return new CrbReport(bankId, reportDate, records, records.size(),
                totalOutstanding.toPlainString(), totalOverdue.toPlainString());
    }

    private static int deriveScore(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            int val = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
            return 300 + (val % 551); // 300 to 850
        } catch (Exception e) {
            return 650;
        }
    }
}
