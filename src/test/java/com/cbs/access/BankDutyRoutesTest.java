package com.cbs.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cbs.access.BankDuties.Duty;
import com.cbs.access.BankDutyRoutes.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class BankDutyRoutesTest {
    private static final String BANK = "/api/v1/admin/tenants/{bank}";

    /** Every bank-scoped administrator route declared by a controller, as "METHOD pattern". */
    static SortedSet<String> bankRoutes() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var routes = new TreeSet<String>();
        for (var candidate : scanner.findCandidateComponents("com.cbs")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            var base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (var method : type.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                var verbs = mapping.method().length == 0 ? List.of("GET") : Arrays.stream(mapping.method()).map(Enum::name).toList();
                for (String prefix : prefixes) for (String path : paths) for (String verb : verbs) {
                    String pattern = prefix + path;
                    if (pattern.matches("^/api/v1/admin/tenants/\\{[^}]+}.*")) routes.add(verb + " " + pattern);
                }
            }
        }
        return routes;
    }

    @Test void everyBankScopedAdministratorRouteHasADutyRule() throws Exception {
        var routes = bankRoutes();
        var unclassified = new ArrayList<String>();
        for (String route : routes) {
            try { assertThat(BankDutyRoutes.rule(route.substring(route.indexOf(' ') + 1))).isNotNull(); }
            catch (IllegalStateException missing) { unclassified.add(route); }
        }
        assertThat(unclassified).isEmpty();
        // Round 9b counted about 140 such routes; a sharp drop means the scan stopped seeing controllers.
        assertThat(routes).hasSizeGreaterThanOrEqualTo(140);
    }

    @Test void routesMapToTheDutyOfTheWorkTheyDo() {
        assertThat(rule("/journals/{id}/retry").write()).isEqualTo(Duty.FINANCE);
        assertThat(rule("/journals").read()).containsExactly(Duty.FINANCE);
        assertThat(rule("/servicing/policies").write()).isEqualTo(Duty.BANK_CONFIGURATION);
        assertThat(rule("/servicing/runs").write()).isEqualTo(Duty.FINANCE);
        assertThat(rule("/accounts/{accountId}/statement/pdf").read()).contains(Duty.CUSTOMER_OPERATIONS, Duty.COMPLIANCE);
        assertThat(rule("/compliance/screening/hits/{id}/escalate").write()).isEqualTo(Duty.COMPLIANCE);
        assertThat(rule("/fx/valuation/runs").write()).isEqualTo(Duty.TREASURY);
        assertThat(rule("/statement-imports/preview").write()).isEqualTo(Duty.PAYMENTS);
        assertThat(rule("/branches/{id}/memberships").write()).isEqualTo(Duty.ACCESS_ADMINISTRATION);
        assertThat(rule("/branches/{id}").kind()).isEqualTo(Kind.ANY);
        assertThat(rule("/memberships").write()).isEqualTo(Duty.ACCESS_ADMINISTRATION);
        assertThat(rule("").kind()).isEqualTo(Kind.ANY);
        assertThat(rule("/settings").write()).isEqualTo(Duty.BANK_CONFIGURATION);
        assertThat(rule("/suspend").kind()).isEqualTo(Kind.PLATFORM);
        assertThat(rule("/provision").kind()).isEqualTo(Kind.PLATFORM);
        assertThat(rule("/bank-duties/policy").kind()).isEqualTo(Kind.SELF);
        assertThat(rule("/contracts/{id}/collections").kind()).isEqualTo(Kind.LENDING);
        assertThat(rule("/contracts/fees/preview").kind()).isEqualTo(Kind.LENDING);
        assertThat(rule("/contracts/servicing").write()).isEqualTo(Duty.CUSTOMER_OPERATIONS);
        assertThat(rule("/credit/applications").kind()).isEqualTo(Kind.LENDING);
    }

    @Test void prefixesMatchWholeSegmentsOnly() {
        // "/fxrates" must not borrow the "/fx" rule, and the bank list is not bank-scoped.
        assertThatThrownBy(() -> BankDutyRoutes.rule(BANK + "/fxrates")).isInstanceOf(IllegalStateException.class);
        assertThat(BankDutyRoutes.rule("/api/v1/admin/tenants")).isNull();
        assertThat(BankDutyRoutes.rule("/api/v1/admin/approvals/{id}")).isNull();
        assertThat(BankDutyRoutes.remainder("/api/v1/admin/tenants/{tenantId}/branches/{id}/memberships")).isEqualTo("/branches/{id}/memberships");
    }

    private static BankDutyRoutes.Rule rule(String rest) { return BankDutyRoutes.rule(BANK + rest); }
}
