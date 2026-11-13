package com.cbs.access;

import com.cbs.access.BankDuties.Duty;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Bank duty needed on each bank-scoped administrator route ({@code /api/v1/admin/tenants/{bank}/...}).
 *
 * <p>Round 9a put duties on approval requests. These rules extend them to the direct routes: reads,
 * previews, draft uploads, retries and the few direct writes. A read needs any grant of one of the
 * listed duties, or the audit flag; any other method needs the submit duty. Only banks that enforce
 * duties are affected. Every mapped route must match a rule; {@code BankDutyRoutesTest} checks this
 * against the live handler mappings, and an unmatched route is refused while duties are enforced.
 */
public final class BankDutyRoutes {
    private BankDutyRoutes() { }

    public enum Kind {
        /** Platform-wide work (onboarding, suspension): not a bank duty. */
        PLATFORM,
        /** The endpoint applies its own duty rules (bank duties and access reviews, including lockout recovery). */
        SELF,
        /** Governed by lending grants on every path, as in Round 9a. */
        LENDING,
        /** Reads that any duty holder needs (bank header, settings, reference lists); writes need the write duty. */
        ANY,
        /** Reads need one of the read duties or audit; other methods need the write duty. */
        DUTY
    }

    public record Rule(String prefix, Kind kind, Duty write, Set<Duty> read) {
        /** Duty that a non-read request needs, or null when no bank duty applies. */
        public Duty writeDuty() { return kind==Kind.DUTY||kind==Kind.ANY ? write : null; }
    }

    private static final Duty ACCESS = Duty.ACCESS_ADMINISTRATION, CONFIG = Duty.BANK_CONFIGURATION, FINANCE = Duty.FINANCE,
            CUSTOMER = Duty.CUSTOMER_OPERATIONS, PAYMENTS = Duty.PAYMENTS, TREASURY = Duty.TREASURY, COMPLIANCE = Duty.COMPLIANCE;

    /** Most specific prefix first; a prefix matches the path itself or anything below it. */
    static final List<Rule> RULES = List.of(
            platform("/provision"), platform("/suspend"), platform("/resume"),
            new Rule("/bank-duties", Kind.SELF, null, Set.of()),
            lending("/credit"), lending("/loan-collections"),
            lending("/contracts/fees"), lending("/contracts/loan-servicing"), lending("/contracts/collections"),
            lending("/contracts/{id}/collections"),
            // Access administration: who may act in the bank.
            duty("/memberships", ACCESS), duty("/branches/{id}/memberships", ACCESS),
            duty("/lending-access", ACCESS), duty("/payment-access", ACCESS), duty("/audit", ACCESS),
            // Bank configuration. Its lists are reference data that every duty holder reads.
            any("", CONFIG), any("/settings", CONFIG), any("/legal-entities", CONFIG), any("/calendar-day", CONFIG),
            any("/rounding-preview", CONFIG), any("/branches", CONFIG), any("/account-branches", CONFIG),
            any("/branch-limits", CONFIG), any("/transaction-limits", CONFIG), any("/cash-denominations", CONFIG),
            any("/cash-locations", CONFIG), any("/products", CONFIG), any("/chart-of-accounts", FINANCE),
            any("/ledger-accounts", FINANCE),
            duty("/servicing/policies", CONFIG, CONFIG, FINANCE),
            // Finance.
            duty("/servicing", FINANCE, FINANCE, CONFIG), duty("/journals", FINANCE), duty("/business-day", FINANCE),
            duty("/period-close", FINANCE), any("/operations-console", FINANCE), duty("/accounting-reports", FINANCE), duty("/reporting", FINANCE),
            // Customer operations. Customer and account records are also read by compliance, payments and finance.
            duty("/customers", CUSTOMER, CUSTOMER, COMPLIANCE, PAYMENTS, FINANCE),
            duty("/accounts", CUSTOMER, CUSTOMER, COMPLIANCE, PAYMENTS, FINANCE),
            duty("/account-operations", CUSTOMER, CUSTOMER, FINANCE), duty("/funding", CUSTOMER, CUSTOMER, FINANCE),
            // Message wording is bank configuration (Round 10); customer staff read it.
            duty("/notifications/templates", CONFIG, CONFIG, CUSTOMER), duty("/notifications", CUSTOMER), duty("/contracts", CUSTOMER, CUSTOMER, FINANCE),
            // Payments, treasury and compliance.
            duty("/payments", PAYMENTS, PAYMENTS, FINANCE), duty("/statement-imports", PAYMENTS, PAYMENTS, FINANCE),
            duty("/fx", TREASURY, TREASURY, FINANCE), duty("/compliance", COMPLIANCE),
            // Privacy (Round 18): the data protection officer works under the compliance duty.
            duty("/privacy", COMPLIANCE));

    private static Rule platform(String prefix) { return new Rule(prefix, Kind.PLATFORM, null, Set.of()); }
    private static Rule lending(String prefix) { return new Rule(prefix, Kind.LENDING, null, Set.of()); }
    private static Rule any(String prefix, Duty write) { return new Rule(prefix, Kind.ANY, write, Set.of()); }
    private static Rule duty(String prefix, Duty write) { return new Rule(prefix, Kind.DUTY, write, EnumSet.of(write)); }
    private static Rule duty(String prefix, Duty write, Duty first, Duty... more) {
        return new Rule(prefix, Kind.DUTY, write, EnumSet.of(first, more));
    }

    /** The rule for a route pattern, or null when the pattern is not bank-scoped. Throws for an unclassified bank route. */
    public static Rule rule(String pattern) {
        String rest = remainder(pattern);
        if (rest == null) return null;
        for (Rule rule : RULES) {
            if (rule.prefix().isEmpty() ? rest.isEmpty() : (rest.equals(rule.prefix()) || rest.startsWith(rule.prefix() + "/"))) return rule;
        }
        throw new IllegalStateException("Unclassified bank route");
    }

    /** Pattern below {@code /api/v1/admin/tenants/{var}}, with every path variable written as {id}; null when not bank-scoped. */
    static String remainder(String pattern) {
        var match = java.util.regex.Pattern.compile("^/api/v1/admin/tenants/\\{[^}/]+}(/.*)?$").matcher(pattern);
        if (!match.matches()) return null;
        String rest = match.group(1) == null ? "" : match.group(1);
        return rest.replaceAll("\\{[^}/]+}", "{id}");
    }
}
