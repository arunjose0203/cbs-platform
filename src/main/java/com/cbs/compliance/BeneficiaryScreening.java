package com.cbs.compliance;

import com.cbs.access.AccessService;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screens M-Pesa payout beneficiaries against the bank's active lists before submission, approval and
 * dispatch. A match that compliance has not dismissed refuses the payout (fail closed). The match is
 * recorded in its own transaction first, so it survives the refusal and reaches the compliance queue,
 * where it is escalated or, with approval, dismissed. A dismissal carries over for the same payee
 * number, name and list entry. Lists are re-checked at every stage, so a list activated after
 * submission still stops the payout.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BeneficiaryScreening {
    public enum Stage { SUBMIT, APPROVAL, DISPATCH }

    private final JdbcTemplate jdbc;
    private final ScreeningService screening;
    private final TenantRepository tenants;
    private final TransactionTemplate separate;
    private final TransactionTemplate tx;

    public BeneficiaryScreening(JdbcTemplate jdbc, ScreeningService screening, TenantRepository tenants, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.screening = screening; this.tenants = tenants;
        this.separate = new TransactionTemplate(manager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx = new TransactionTemplate(manager);
    }

    /** Refuses the payout while the beneficiary matches an active list entry that has not been dismissed. */
    public void require(UUID bank, UUID account, UUID customer, String beneficiary, String beneficiaryName, Stage stage) {
        String name = beneficiaryName.strip().replaceAll("\\s+", " ");
        String key = name.toLowerCase(Locale.ROOT);
        var blocking = new ArrayList<ScreeningService.NameMatch>();
        for (var m : screening.matchName(bank, name)) {
            boolean dismissed = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM platform.beneficiary_hit WHERE tenant_id=? AND beneficiary=? AND name_key=?
                      AND list_name=? AND entry_ref=? AND entry_hash=? AND status='DISMISSED')
                    """, Boolean.class, bank, beneficiary, key, m.listName(), m.match().entry().ref(), m.match().entry().hash()));
            if (!dismissed) blocking.add(m);
        }
        if (blocking.isEmpty()) return;
        separate.executeWithoutResult(t -> {
            for (var m : blocking) {
                jdbc.update("""
                        INSERT INTO platform.beneficiary_hit(id,tenant_id,beneficiary,beneficiary_name,name_key,account_id,customer_id,list_id,entry_ref,
                          list_name,entry_hash,score,matched_name,stage) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT (tenant_id,beneficiary,name_key,list_id,entry_ref) DO NOTHING
                        """, UUID.randomUUID(), bank, beneficiary, name, key, account, customer, m.listId(), m.match().entry().ref(),
                        m.listName(), m.match().entry().hash(), BigDecimal.valueOf(m.match().score()), m.match().matchedName(), stage.name());
            }
        });
        throw new TenantException(409, "PAYMENT_BENEFICIARY_SCREENING_MATCH",
                "The beneficiary matches a screening list; compliance must review the match before this payout");
    }

    public Map<String, Object> hits(Actor actor, UUID bankId, String status, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit, offset); tenants.get(bankId, false);
        if (!Set.of("OPEN", "DISMISSED", "ESCALATED", "ALL").contains(status)) throw ScreeningService.error(400, "INVALID_REQUEST", "Unknown status");
        String where = " WHERE h.tenant_id=?" + (status.equals("ALL") ? "" : " AND h.status=?");
        var args = new ArrayList<Object>(List.of(bankId));
        if (!status.equals("ALL")) args.add(status);
        long total = jdbc.queryForObject("SELECT count(*) FROM platform.beneficiary_hit h" + where, Long.class, args.toArray());
        args.add(limit); args.add(offset);
        var items = jdbc.queryForList("SELECT h.id,h.beneficiary,h.beneficiary_name,h.matched_name,h.list_name,h.entry_ref,h.score,h.stage,h.status,h.version,h.case_id,"
                + "h.account_id,h.customer_id,r.customer_number,h.created_at FROM platform.beneficiary_hit h JOIN platform.customer_registry r ON r.id=h.customer_id"
                + where + " ORDER BY h.created_at DESC,h.id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("version", tenants.get(bankId, false).version(), "items", items, "total", total, "limit", limit, "offset", offset);
    }

    public Map<String, Object> hit(Actor actor, UUID bankId, UUID id) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, false);
            var rows = jdbc.queryForList("SELECT h.*,h.decision::text AS decision_text,r.customer_number,e.entity_type,e.names::text AS entry_names,e.birth_date AS entry_birth_date,"
                    + "e.country AS entry_country,e.program AS entry_program FROM platform.beneficiary_hit h JOIN platform.customer_registry r ON r.id=h.customer_id "
                    + "JOIN platform.screening_entry e ON e.list_id=h.list_id AND e.entry_ref=h.entry_ref WHERE h.tenant_id=? AND h.id=?", bankId, id);
            if (rows.isEmpty()) throw ScreeningService.error(404, "BENEFICIARY_HIT_NOT_FOUND", "Beneficiary screening match not found");
            var hit = rows.getFirst();
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            hit.put("entry_names", json.readValue((String) hit.get("entry_names"), List.class));
            String decision = (String) hit.remove("decision_text");
            hit.put("decision", decision == null ? null : json.readValue(decision, Map.class));
            tenants.audit(actor, bankId, "BENEFICIARY_HIT_VIEWED", bank.version(), bank.status(), bank.status(), "Beneficiary screening evidence viewed", null, null, Map.of("hitId", id));
            return Map.of("version", bank.version(), "hit", hit);
        });
    }
}
