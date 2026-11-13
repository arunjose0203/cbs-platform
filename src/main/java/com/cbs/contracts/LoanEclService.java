package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import static com.cbs.contracts.LoanEclModels.*;
import com.cbs.access.AccessService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class LoanEclService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ReferenceDataService reference;

    public LoanEclService(JdbcTemplate jdbc, TenantRepository tenants, ReferenceDataService reference) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.reference = reference;
    }

    public EclReport evaluate(Actor actor, UUID bankId, LocalDate asOf) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        LocalDate date = asOf != null ? asOf : reference.read(bank).businessDate();

        var rows = jdbc.queryForList("SELECT id, account_id, payload, position, status FROM platform.contract WHERE tenant_id=? AND kind='LOAN' AND status='ACTIVE' ORDER BY created_at", bankId);

        List<EclAssessment> list = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalRequired = BigDecimal.ZERO;
        BigDecimal totalCurrent = BigDecimal.ZERO;

        for (var r : rows) {
            UUID contractId = (UUID) r.get("id");
            UUID accountId = (UUID) r.get("account_id");
            var def = JSON.readValue(r.get("payload").toString(), Definition.class);
            var pos = JSON.readValue(r.get("position").toString(), Position.class);

            int scale = scale(def);
            BigDecimal principal = new BigDecimal(pos.principal());
            BigDecimal interest = new BigDecimal(pos.interest());
            BigDecimal gross = principal.add(interest);

            var events = jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at, id",
                    (rs, n) -> JSON.readValue(rs.getString(1), Posting.class), bankId, contractId);

            long dpd = 0;
            if (!events.isEmpty()) {
                var arrears = LoanArrears.calculate(events, date);
                dpd = arrears.daysPastDue();
            }

            int stage;
            BigDecimal eclRate;
            if (dpd <= 30) {
                stage = 1;
                eclRate = new BigDecimal("0.01");
            } else if (dpd <= 90) {
                stage = 2;
                eclRate = new BigDecimal("0.03");
            } else {
                stage = 3;
                eclRate = new BigDecimal("0.20");
            }

            BigDecimal reqAllowance = gross.multiply(eclRate).setScale(scale, RoundingMode.HALF_EVEN);
            BigDecimal currAllowance = LoanServicingService.allowance(jdbc, contractId);
            BigDecimal netAdj = reqAllowance.subtract(currAllowance);
            boolean needsAdj = netAdj.signum() != 0;

            totalGross = totalGross.add(gross);
            totalRequired = totalRequired.add(reqAllowance);
            totalCurrent = totalCurrent.add(currAllowance);

            list.add(new EclAssessment(
                    contractId,
                    def.terms().agreementReference(),
                    accountId,
                    stage,
                    dpd,
                    gross.toPlainString(),
                    eclRate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%",
                    reqAllowance.toPlainString(),
                    currAllowance.toPlainString(),
                    netAdj.toPlainString(),
                    needsAdj
            ));
        }

        BigDecimal netTotalAdjustment = totalRequired.subtract(totalCurrent);
        return new EclReport(
                bankId,
                date,
                list,
                list.size(),
                totalGross.toPlainString(),
                totalRequired.toPlainString(),
                totalCurrent.toPlainString(),
                netTotalAdjustment.toPlainString()
        );
    }
}
