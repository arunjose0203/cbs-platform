package com.cbs.compliance;

import static com.cbs.compliance.AutomationModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Approved screening lists and customer name screening.
 *
 * <p>A list version is uploaded as an inert draft and becomes active only through an independently
 * approved activation, which supersedes the previous version of the same list and queues a re-screen
 * of every customer. Customers are also screened whenever an approved profile change is applied. A
 * possible match is an OPEN hit, which restricts the customer (and any organization the customer is a
 * director, owner or representative of) until it is escalated to a case or its dismissal is approved.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ScreeningService {
    static final int CHUNK = 500;
    private static final int MAX_ENTRIES = 100_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final TransactionTemplate tx;
    private final Map<UUID, NameMatcher.Index> indexes = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, NameMatcher.Index> eldest) { return size() > 8; }
    });

    public ScreeningService(JdbcTemplate jdbc, TenantRepository tenants, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenants = tenants; this.tx = new TransactionTemplate(manager);
    }

    private record Row(String ref, String type, List<String> names, LocalDate birthDate, String country, String program, String hash) { }

    // ---- Upload ---------------------------------------------------------------------------------

    public Map<String, Object> upload(Actor actor, UUID bankId, Upload input) {
        AccessService.admin(actor);
        var rows = parse(input.csv());
        String sha = sha256(input.csv());
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            if (bank.status() != Status.ACTIVE) { throw error(409, "COMPLIANCE_BANK_NOT_ACTIVE", "The bank is not active"); }
            var existing = jdbc.queryForList("SELECT id,category,source,threshold FROM platform.screening_list WHERE tenant_id=? AND name=? AND sha256=?", bankId, input.name(), sha);
            if (!existing.isEmpty()) {
                var e = existing.getFirst();
                if (!input.category().name().equals(e.get("category")) || !input.source().equals(e.get("source"))
                        || ((BigDecimal) e.get("threshold")).compareTo(input.threshold()) != 0) {
                    throw error(409, "SCREENING_LIST_DUPLICATE", "This file was already uploaded with different settings");
                }
                return list(bankId, (UUID) e.get("id"));
            }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO platform.screening_list(id,tenant_id,name,category,source,threshold,sha256,entry_count,issuer,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, bankId, input.name(), input.category().name(), input.source(), input.threshold(), sha, rows.size(), actor.issuer(), actor.subject());
            for (int from = 0; from < rows.size(); from += 1000) {
                var batch = rows.subList(from, Math.min(rows.size(), from + 1000)).stream().map(r -> new Object[] {
                        id, r.ref(), r.type(), JSON.writeValueAsString(r.names()), r.birthDate(), r.country(), r.program(), r.hash() }).toList();
                jdbc.batchUpdate("INSERT INTO platform.screening_entry(list_id,entry_ref,entity_type,names,birth_date,country,program,entry_hash) VALUES (?,?,?,?::jsonb,?,?,?,?)", batch);
            }
            tenants.audit(actor, bankId, "SCREENING_LIST_UPLOADED", bank.version(), bank.status(), bank.status(), "Screening list draft uploaded", null, null,
                    Map.of("listId", id, "name", input.name(), "entries", rows.size(), "sha256", sha));
            return list(bankId, id);
        });
    }

    static List<Row> parse(String csv) {
        var records = records(csv);
        if (records.isEmpty() || !records.getFirst().equals(List.of("entry_ref", "entity_type", "name", "aliases", "birth_date", "country", "program"))) {
            throw invalid(1, "the header must be entry_ref,entity_type,name,aliases,birth_date,country,program");
        }
        var rows = new ArrayList<Row>();
        var refs = new HashSet<String>();
        for (int i = 1; i < records.size(); i++) {
            var r = records.get(i);
            int line = i + 1;
            if (r.size() == 1 && r.getFirst().isBlank()) { continue; }
            if (r.size() != 7) { throw invalid(line, "expected 7 columns"); }
            String ref = r.get(0).strip(), type = r.get(1).strip().toUpperCase(Locale.ROOT);
            if (!ref.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}") || !refs.add(ref)) { throw invalid(line, "entry_ref is missing, invalid or repeated"); }
            if (!type.equals("INDIVIDUAL") && !type.equals("ENTITY")) { throw invalid(line, "entity_type must be INDIVIDUAL or ENTITY"); }
            var names = new ArrayList<String>();
            for (String n : (r.get(2) + ";" + r.get(3)).split(";")) {
                String name = n.strip().replaceAll("\\s+", " ");
                if (name.isEmpty()) { continue; }
                if (name.length() > 300 || NameMatcher.tokens(name).isEmpty()) { throw invalid(line, "a name is too long or has no letters"); }
                if (!names.contains(name)) { names.add(name); }
            }
            if (r.get(2).isBlank() || names.isEmpty() || names.size() > 20) { throw invalid(line, "name is required and at most 20 names are allowed"); }
            LocalDate born = null;
            if (!r.get(4).isBlank()) {
                try { born = LocalDate.parse(r.get(4).strip()); } catch (DateTimeParseException e) { throw invalid(line, "birth_date must be YYYY-MM-DD"); }
            }
            String country = r.get(5).strip().toUpperCase(Locale.ROOT);
            if (!country.isEmpty() && !country.matches("[A-Z]{2}")) { throw invalid(line, "country must be a two-letter code"); }
            String program = r.get(6).strip();
            if (program.length() > 200) { throw invalid(line, "program is too long"); }
            if (rows.size() >= MAX_ENTRIES) { throw error(422, "SCREENING_LIST_TOO_LARGE", "A list version may hold at most 100,000 entries"); }
            String hash = sha256(type + "\u001f" + String.join("\u001e", names) + "\u001f" + born + "\u001f" + country + "\u001f" + program);
            rows.add(new Row(ref, type, List.copyOf(names), born, country.isEmpty() ? null : country, program.isEmpty() ? null : program, hash));
        }
        if (rows.isEmpty()) { throw invalid(2, "the list has no entries"); }
        return rows;
    }

    private static List<List<String>> records(String raw) {
        String input = raw.startsWith("﻿") ? raw.substring(1) : raw;
        var records = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false, ended = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\0') { throw invalid(records.size() + 1, "the file contains a NUL character"); }
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') { field.append('"'); i++; } else { quoted = false; ended = true; }
                } else { field.append(c); }
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); ended = false;
                if (c != ',') {
                    if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') { i++; }
                    records.add(List.copyOf(row)); row.clear();
                }
            } else if (c == '"' && field.isEmpty() && !ended) { quoted = true; }
            else {
                if (ended || c == '"') { throw invalid(records.size() + 1, "misplaced quote"); }
                field.append(c);
            }
            if (field.length() > 7000 || row.size() > 7) { throw invalid(records.size() + 1, "a field or row is too long"); }
        }
        if (quoted) { throw invalid(records.size() + 1, "unterminated quote"); }
        if (!row.isEmpty() || !field.isEmpty() || ended) { row.add(field.toString()); records.add(List.copyOf(row)); }
        return records;
    }

    // ---- Lists ----------------------------------------------------------------------------------

    public List<Map<String, Object>> lists(Actor actor, UUID bankId) {
        AccessService.admin(actor); tenants.get(bankId, false);
        return jdbc.queryForList("SELECT l.id,l.name,l.category,l.source,l.threshold,l.sha256,l.entry_count,l.status,l.uploaded_by,l.created_at,l.activated_at,"
                + "r.status AS run_status,r.screened AS run_screened,r.hits AS run_hits FROM platform.screening_list l "
                + "LEFT JOIN LATERAL (SELECT status,screened,hits FROM platform.screening_run WHERE list_id=l.id ORDER BY created_at DESC LIMIT 1) r ON true "
                + "WHERE l.tenant_id=? ORDER BY l.name,l.created_at DESC LIMIT 200", bankId);
    }

    public Map<String, Object> entries(Actor actor, UUID bankId, UUID listId, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit, offset); tenants.get(bankId, false);
        var list = list(bankId, listId);
        var items = jdbc.queryForList("SELECT entry_ref,entity_type,names::text AS names,birth_date,country,program FROM platform.screening_entry WHERE list_id=? ORDER BY entry_ref LIMIT ? OFFSET ?", listId, limit, offset);
        for (var i : items) { i.put("names", JSON.readValue((String) i.get("names"), new TypeReference<List<String>>() { })); }
        return Map.of("list", list, "items", items, "limit", limit, "offset", offset);
    }

    private Map<String, Object> list(UUID bankId, UUID id) {
        var rows = jdbc.queryForList("SELECT id,name,category,source,threshold,sha256,entry_count,status,uploaded_by,created_at,activated_at FROM platform.screening_list WHERE tenant_id=? AND id=?", bankId, id);
        if (rows.isEmpty()) { throw error(404, "SCREENING_LIST_NOT_FOUND", "Screening list not found"); }
        return rows.getFirst();
    }

    // ---- Activation (approval) ------------------------------------------------------------------

    public void validate(Tenant bank, Activation input) {
        if (bank.version() != input.expectedVersion()) { throw error(409, "STALE_VERSION", "The bank changed; reload and try again"); }
        if (bank.status() != Status.ACTIVE) { throw error(409, "COMPLIANCE_BANK_NOT_ACTIVE", "The bank is not active"); }
        var list = list(bank.id(), input.listId());
        if (!"DRAFT".equals(list.get("status"))) { throw error(409, "SCREENING_LIST_NOT_DRAFT", "Only a draft list version can be activated"); }
        if (!input.sha256().equals(list.get("sha256"))) { throw error(409, "SCREENING_LIST_CHANGED", "The list checksum does not match the uploaded draft"); }
    }

    public Map<String, Object> before(Tenant bank, Activation input) {
        var name = list(bank.id(), input.listId()).get("name");
        var active = jdbc.queryForList("SELECT name,entry_count,sha256,threshold FROM platform.screening_list WHERE tenant_id=? AND name=? AND status='ACTIVE'", bank.id(), name);
        return active.isEmpty() ? Map.of() : active.getFirst();
    }

    public Map<String, Object> values(Tenant bank, Activation input) {
        var l = list(bank.id(), input.listId());
        var m = new LinkedHashMap<String, Object>();
        for (var k : List.of("name", "category", "source", "threshold", "entry_count", "sha256")) { m.put(k, l.get(k)); }
        m.put("reason", input.reason());
        return m;
    }

    public void apply(Actor actor, UUID bankId, Activation input, UUID approvalId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, true);
        jdbc.query("SELECT id FROM platform.screening_list WHERE id=? FOR UPDATE", r -> { }, input.listId());
        validate(bank, input);
        String name = (String) list(bankId, input.listId()).get("name");
        var superseded = jdbc.queryForList("SELECT id FROM platform.screening_list WHERE tenant_id=? AND name=? AND status='ACTIVE'", UUID.class, bankId, name);
        jdbc.update("UPDATE platform.screening_list SET status='SUPERSEDED' WHERE tenant_id=? AND name=? AND status='ACTIVE'", bankId, name);
        for (UUID old : superseded) {
            jdbc.update("UPDATE platform.screening_run SET status='CANCELLED',finished_at=clock_timestamp() WHERE list_id=? AND status='PENDING'", old);
        }
        jdbc.update("UPDATE platform.screening_list SET status='ACTIVE',approval_id=?,activated_at=clock_timestamp() WHERE id=?", approvalId, input.listId());
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.screening_run(id,tenant_id,list_id) VALUES (?,?,?)", run, bankId, input.listId());
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(actor, bankId, "SCREENING_LIST_ACTIVATED", bank.version() + 1, bank.status(), bank.status(), input.reason(), null, null,
                Map.of("listId", input.listId(), "name", name, "runId", run, "superseded", superseded));
    }

    // ---- Screening ------------------------------------------------------------------------------

    private record Active(UUID id, String name, double threshold, NameMatcher.Index index) { }

    private List<Active> active(UUID bankId) {
        return jdbc.query("SELECT id,name,threshold FROM platform.screening_list WHERE tenant_id=? AND status='ACTIVE' ORDER BY name",
                (r, n) -> new Active(r.getObject(1, UUID.class), r.getString(2), r.getBigDecimal(3).doubleValue(), index(r.getObject(1, UUID.class))), bankId);
    }

    private NameMatcher.Index index(UUID listId) {
        var cached = indexes.get(listId);
        if (cached != null) { return cached; }
        var entries = jdbc.query("SELECT entry_ref,entity_type,names::text,birth_date,entry_hash FROM platform.screening_entry WHERE list_id=?",
                (r, n) -> new NameMatcher.Entry(r.getString(1), "INDIVIDUAL".equals(r.getString(2)),
                        JSON.readValue(r.getString(3), new TypeReference<List<String>>() { }), r.getObject(4, LocalDate.class), r.getString(5)), listId);
        var index = new NameMatcher.Index(entries);
        indexes.put(listId, index);
        return index;
    }

    /** Screens one customer version against every active list; called when a profile change is applied. */
    public int screenCustomer(UUID bankId, UUID customerId, long version, String fullName, LocalDate birthDate, boolean individual) {
        int hits = 0;
        for (var list : active(bankId)) { hits += screen(bankId, list, customerId, version, fullName, birthDate, individual, "CUSTOMER"); }
        return hits;
    }

    private int screen(UUID bankId, Active list, UUID customerId, long version, String name, LocalDate birthDate, boolean individual, String trigger) {
        int hits = 0;
        String screened = name.strip().replaceAll("\\s+", " ");
        for (var m : list.index().match(screened, birthDate, individual, list.threshold())) {
            // A dismissal carries over while the screened name and the list entry are unchanged.
            if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.screening_hit WHERE customer_id=? AND list_name=? AND entry_ref=? AND entry_hash=? AND lower(customer_name)=lower(?) AND status='DISMISSED')",
                    Boolean.class, customerId, list.name(), m.entry().ref(), m.entry().hash(), screened)) { continue; }
            hits += jdbc.update("INSERT INTO platform.screening_hit(id,tenant_id,customer_id,customer_version,list_id,entry_ref,list_name,entry_hash,score,customer_name,matched_name,trigger) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (customer_id,customer_version,list_id,entry_ref) DO NOTHING",
                    UUID.randomUUID(), bankId, customerId, version, list.id(), m.entry().ref(), list.name(), m.entry().hash(),
                    BigDecimal.valueOf(m.score()), screened, m.matchedName(), trigger);
        }
        return hits;
    }

    /** Processes up to {@code chunks} chunks of pending re-screen runs; returns the chunks processed. */
    public int processRuns(int chunks) {
        int done = 0;
        while (done < chunks && Boolean.TRUE.equals(tx.execute(t -> chunk()))) { done++; }
        return done;
    }

    private boolean chunk() {
        var runs = jdbc.queryForList("SELECT id,tenant_id,list_id,cursor_id FROM platform.screening_run WHERE status='PENDING' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (runs.isEmpty()) { return false; }
        var run = runs.getFirst();
        UUID id = (UUID) run.get("id"), bankId = (UUID) run.get("tenant_id"), listId = (UUID) run.get("list_id"), cursor = (UUID) run.get("cursor_id");
        var list = active(bankId).stream().filter(l -> l.id().equals(listId)).findFirst();
        if (list.isEmpty()) {
            jdbc.update("UPDATE platform.screening_run SET status='CANCELLED',finished_at=clock_timestamp() WHERE id=?", id);
            return true;
        }
        var customers = jdbc.queryForList("SELECT id,version,name_key,birth_date,customer_type FROM platform.customer_registry WHERE tenant_id=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
                bankId, cursor, cursor, CHUNK);
        int hits = 0;
        for (var c : customers) {
            hits += screen(bankId, list.get(), (UUID) c.get("id"), ((Number) c.get("version")).longValue(), (String) c.get("name_key"),
                    c.get("birth_date") == null ? null : ((java.sql.Date) c.get("birth_date")).toLocalDate(), "INDIVIDUAL".equals(c.get("customer_type")), "LIST");
        }
        UUID last = customers.isEmpty() ? cursor : (UUID) customers.getLast().get("id");
        boolean finished = customers.size() < CHUNK;
        jdbc.update("UPDATE platform.screening_run SET cursor_id=?,screened=screened+?,hits=hits+?,status=?,finished_at=CASE WHEN ? THEN clock_timestamp() END WHERE id=?",
                last, customers.size(), hits, finished ? "DONE" : "PENDING", finished, id);
        return true;
    }

    // ---- Hits -----------------------------------------------------------------------------------

    public Map<String, Object> hits(Actor actor, UUID bankId, String status, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit, offset); tenants.get(bankId, false);
        if (!Set.of("OPEN", "DISMISSED", "ESCALATED", "ALL").contains(status)) { throw error(400, "INVALID_REQUEST", "Unknown status"); }
        String where = " WHERE h.tenant_id=?" + (status.equals("ALL") ? "" : " AND h.status=?");
        var args = new ArrayList<Object>(List.of(bankId));
        if (!status.equals("ALL")) { args.add(status); }
        long total = jdbc.queryForObject("SELECT count(*) FROM platform.screening_hit h" + where, Long.class, args.toArray());
        args.add(limit); args.add(offset);
        var items = jdbc.queryForList("SELECT h.id,h.customer_id,r.customer_number,h.customer_name,h.matched_name,h.list_name,h.entry_ref,h.score,h.trigger,h.status,h.version,h.case_id,h.created_at "
                + "FROM platform.screening_hit h JOIN platform.customer_registry r ON r.id=h.customer_id" + where + " ORDER BY h.created_at DESC,h.id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("version", tenants.get(bankId, false).version(), "items", items, "total", total, "limit", limit, "offset", offset);
    }

    public Map<String, Object> hit(Actor actor, UUID bankId, UUID id) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, false);
            var rows = jdbc.queryForList("SELECT h.*,h.decision::text AS decision_text,r.customer_number,r.version AS current_customer_version,e.entity_type,e.names::text AS entry_names,e.birth_date AS entry_birth_date,e.country AS entry_country,e.program AS entry_program "
                    + "FROM platform.screening_hit h JOIN platform.customer_registry r ON r.id=h.customer_id JOIN platform.screening_entry e ON e.list_id=h.list_id AND e.entry_ref=h.entry_ref WHERE h.tenant_id=? AND h.id=?", bankId, id);
            if (rows.isEmpty()) { throw error(404, "SCREENING_HIT_NOT_FOUND", "Screening hit not found"); }
            var hit = rows.getFirst();
            hit.put("entry_names", JSON.readValue((String) hit.get("entry_names"), new TypeReference<List<String>>() { }));
            String decision = (String) hit.remove("decision_text");
            hit.put("decision", decision == null ? null : JSON.readValue(decision, new TypeReference<Map<String, Object>>() { }));
            tenants.audit(actor, bankId, "SCREENING_HIT_VIEWED", bank.version(), bank.status(), bank.status(), "Screening evidence viewed", null, null, Map.of("hitId", id));
            return Map.of("version", bank.version(), "hit", hit);
        });
    }

    // ---- Helpers --------------------------------------------------------------------------------

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static TenantException invalid(int line, String problem) {
        return new TenantException(422, "SCREENING_LIST_INVALID", "Line " + line + ": " + problem);
    }

    static TenantException error(int status, String code, String message) { return new TenantException(status, code, message); }
}
