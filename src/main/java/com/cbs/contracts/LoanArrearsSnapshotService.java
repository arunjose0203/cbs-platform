package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.TenantRepository;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class LoanArrearsSnapshotService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SNAPSHOT_THRESHOLD = 500;

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ReferenceDataService reference;

    public record SnapshotRecord(UUID id, UUID contractId, LocalDate businessDate, UUID lastEventId, LoanArrears.Report report) {}

    public LoanArrearsSnapshotService(JdbcTemplate jdbc, TenantRepository tenants, ReferenceDataService reference) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.reference = reference;
    }

    public SnapshotRecord latestSnapshot(UUID bankId, UUID contractId, LocalDate asOf) {
        var rows = jdbc.queryForList("SELECT id, contract_id, business_date, last_event_id, snapshot_report::text FROM platform.loan_arrears_snapshot WHERE tenant_id=? AND contract_id=? AND business_date<=? ORDER BY business_date DESC, created_at DESC LIMIT 1",
                bankId, contractId, asOf);
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        return new SnapshotRecord(
                (UUID) r.get("id"),
                (UUID) r.get("contract_id"),
                r.get("business_date") instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r.get("business_date"),
                (UUID) r.get("last_event_id"),
                JSON.readValue((String) r.get("snapshot_report"), LoanArrears.Report.class)
        );
    }

    public void takeSnapshot(UUID bankId, UUID contractId, LocalDate businessDate, UUID lastEventId, LoanArrears.Report report) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.loan_arrears_snapshot(id,tenant_id,contract_id,business_date,last_event_id,snapshot_report) VALUES (?,?,?,?,?::uuid,?::jsonb)",
                id, bankId, contractId, businessDate, lastEventId, JSON.writeValueAsString(report));
    }

    public LoanArrears.Report calculateArrears(UUID bankId, UUID contractId, LocalDate asOf) {
        return calculateArrears(bankId, contractId, asOf, List.of());
    }

    public LoanArrears.Report calculateArrears(UUID bankId, UUID contractId, LocalDate asOf, List<LoanArrears.MoratoriumInterval> moratoria) {
        // Fetch all completed events without hard 10,000 failure
        var events = jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at,id",
                (r, n) -> JSON.readValue(r.getString(1), Posting.class), bankId, contractId);
        if (events.isEmpty()) return null;

        var report = LoanArrears.calculate(events, asOf, moratoria);

        // If event volume is large and no recent snapshot, save snapshot
        if (events.size() >= SNAPSHOT_THRESHOLD) {
            var latest = events.getLast();
            boolean snapshotExists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM platform.loan_arrears_snapshot WHERE tenant_id=? AND contract_id=? AND business_date=?)",
                    Boolean.class, bankId, contractId, asOf));
            if (!snapshotExists && report.reconciled()) {
                takeSnapshot(bankId, contractId, asOf, latest.id(), report);
            }
        }

        return report;
    }
}
