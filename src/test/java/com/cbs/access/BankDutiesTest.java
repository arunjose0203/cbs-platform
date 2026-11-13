package com.cbs.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.cbs.access.BankDuties.Duty;
import com.cbs.access.BankDuties.Grant;
import com.cbs.approvals.ApprovalModels.Action;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BankDutiesTest {
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Test void onlyPlatformAndLendingOnlyActionsHaveNoBankDuty() {
        var unclassified = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) if (BankDuties.duty(action) == null) unclassified.add(action);
        assertThat(unclassified).containsExactlyInAnyOrder(Action.BANK_SUSPEND, Action.BANK_RESUME, Action.STAFF_UPDATE,
                Action.CREDIT_REVIEW, Action.CREDIT_RELEASE, Action.LOAN_SERVICING, Action.LOAN_FEE);
    }

    @Test void accessChangesAreAccessAdministrationAndMoneyMovesAreTransactional() {
        assertThat(BankDuties.duty(Action.BANK_DUTY)).isEqualTo(Duty.ACCESS_ADMINISTRATION);
        assertThat(BankDuties.duty(Action.LENDING_ACCESS)).isEqualTo(Duty.ACCESS_ADMINISTRATION);
        assertThat(BankDuties.duty(Action.JOURNAL_POST)).isEqualTo(Duty.FINANCE);
        assertThat(BankDuties.duty(Action.ACCOUNT_FUNDING)).isEqualTo(Duty.CUSTOMER_OPERATIONS);
        assertThat(BankDuties.duty(Action.SCREENING_LIST)).isEqualTo(Duty.COMPLIANCE);
        assertThat(BankDuties.duty(Action.FX_TRADE)).isEqualTo(Duty.TREASURY);
        assertThat(BankDuties.duty(Action.PAYMENT_ORDER)).isEqualTo(Duty.PAYMENTS);
        assertThat(BankDuties.TRANSACTIONAL).doesNotContain(Duty.ACCESS_ADMINISTRATION, Duty.COMPLIANCE, Duty.BANK_CONFIGURATION);
    }

    @Test void sqlMappingCoversEveryClassifiedAction() {
        String sql = BankDuties.dutySql("a.action");
        assertThat(sql).startsWith("(CASE a.action").endsWith(" END)").contains("WHEN 'JOURNAL_POST' THEN 'FINANCE'")
                .doesNotContain("'STAFF_UPDATE'").doesNotContain("'LOAN_FEE'");
        for (Action action : Action.values())
            if (BankDuties.duty(action) != null) assertThat(sql).contains("WHEN '" + action.name() + "' THEN '" + BankDuties.duty(action).name() + "'");
    }

    @Test void expiryIsStoredAsIsoTextSoTheDatabaseCanCompareIt() {
        var grant = grant(List.of(Duty.FINANCE), List.of(), false, NOW);
        String json = JsonMapper.builder().build().writeValueAsString(grant);
        assertThat(json).contains("\"expiresAt\":\"2026-11-13T10:00:00Z\"");
        assertThat(JsonMapper.builder().build().readValue(json, Grant.class)).isEqualTo(grant);
    }

    @Test void findingsFlagConflictsDormancyAndTimeLimits() {
        Instant recent = NOW.minus(1, ChronoUnit.DAYS);
        assertThat(BankDuties.findings(grant(List.of(Duty.ACCESS_ADMINISTRATION), List.of(Duty.ACCESS_ADMINISTRATION), false, null), true, recent, NOW, false)).isEmpty();
        assertThat(BankDuties.findings(grant(List.of(Duty.ACCESS_ADMINISTRATION, Duty.FINANCE), List.of(), false, null), true, recent, NOW, false))
                .containsExactly("CONFLICT_ACCESS_AND_TRANSACTIONS");
        assertThat(BankDuties.findings(grant(List.of(Duty.ACCESS_ADMINISTRATION), List.of(), false, null), true, recent, NOW, true))
                .containsExactly("CONFLICT_ACCESS_AND_TRANSACTIONS");
        assertThat(BankDuties.findings(grant(List.of(Duty.CUSTOMER_OPERATIONS), List.of(Duty.COMPLIANCE), false, null), true, recent, NOW, false))
                .containsExactly("CONFLICT_CUSTOMER_AND_COMPLIANCE");
        assertThat(BankDuties.findings(grant(List.of(Duty.FINANCE), List.of(), false, NOW.plusSeconds(60)), true, recent, NOW, false))
                .containsExactly("TIME_LIMITED_GRANT");
        assertThat(BankDuties.findings(grant(List.of(Duty.FINANCE), List.of(), false, NOW.minusSeconds(1)), false, null, NOW, false))
                .containsExactly("STAFF_DISABLED_WITH_GRANT", "DORMANT", "DUTY_EXPIRED");
        assertThat(BankDuties.findings(null, true, NOW.minus(BankDuties.DORMANT_DAYS + 1, ChronoUnit.DAYS), NOW, true)).containsExactly("DORMANT");
    }

    @Test void hashIgnoresSessionTimesButNotAccess() {
        var item = new LinkedHashMap<String,Object>(Map.of("staffId", "s1", "lastSeen", NOW, "findings", List.of()));
        var snapshot = new LinkedHashMap<String,Object>(Map.of("bankId", "b1", "items", List.of(item)));
        String first = BankDuties.hash(snapshot);
        item.put("lastSeen", NOW.plusSeconds(5));
        assertThat(BankDuties.hash(snapshot)).isEqualTo(first).matches("[0-9a-f]{64}");
        item.put("findings", List.of("DORMANT"));
        assertThat(BankDuties.hash(snapshot)).isNotEqualTo(first);
    }

    @Test void reviewedDutiesSerializeInAFixedOrderSoHashesAreReproducible() {
        var view = BankDuties.dutiesView(grant(List.of(Duty.TREASURY, Duty.FINANCE), List.of(Duty.COMPLIANCE), true, NOW));
        assertThat(JsonMapper.builder().build().writeValueAsString(view))
                .isEqualTo("{\"submit\":[\"FINANCE\",\"TREASURY\"],\"approve\":[\"COMPLIANCE\"],\"audit\":true,\"expiresAt\":\"2026-11-13T10:00:00Z\"}");
    }

    private static Grant grant(List<Duty> submit, List<Duty> approve, boolean audit, Instant expiresAt) {
        return new Grant(1, UUID.fromString("00000000-0000-0000-0000-000000000001"), true, submit, approve, audit, expiresAt, "Test grant");
    }
}
