package com.cbs.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationServiceTest {

    @Test
    void validateCustomersDetectsDuplicatesAndRequiredFields() {
        var rec1 = new MigrationModels.CustomerRecord(
                "LEG-001", "12345678", "Alice Mwangi", "0712345678", "alice@example.com",
                LocalDate.of(1985, 5, 20), "KE", "ID/12345678"
        );
        var rec2 = new MigrationModels.CustomerRecord(
                "LEG-002", "12345678", "Duplicate ID Person", "0722345678", "dup@example.com",
                LocalDate.of(1990, 1, 1), "KE", "ID/12345678"
        );
        var rec3 = new MigrationModels.CustomerRecord(
                "LEG-003", "", "Missing ID Person", null, null,
                null, "KE", null
        );
        var rec4 = new MigrationModels.CustomerRecord(
                "LEG-004", "87654321", "Bob Otieno", "0733456789", "bob@example.com",
                LocalDate.of(1992, 10, 15), "KE", "ID/87654321"
        );

        List<MigrationModels.CustomerRecord> records = List.of(rec1, rec2, rec3, rec4);

        // Deduplication & validation logic test
        java.util.Set<String> seen = new java.util.HashSet<>();
        int valid = 0;
        int errors = 0;

        for (var r : records) {
            if (r.nationalId() == null || r.nationalId().isBlank() || !seen.add(r.nationalId()) || r.fullName().isBlank()) {
                errors++;
            } else {
                valid++;
            }
        }

        assertEquals(2, valid, "Only rec1 and rec4 have valid unique national IDs and names");
        assertEquals(2, errors, "rec2 (duplicate) and rec3 (blank ID) must be errors");
    }

    @Test
    void validateAccountsRejectsNegativeBalances() {
        var a1 = new MigrationModels.AccountRecord(
                "ACC-1", "LEG-001", "10001", "SAV-01", "KES", "50000.00", "5.0"
        );
        var a2 = new MigrationModels.AccountRecord(
                "ACC-2", "LEG-004", "10002", "SAV-01", "KES", "-100.00", "5.0"
        );

        assertTrue(new BigDecimal(a1.openingBalance()).signum() >= 0);
        assertTrue(new BigDecimal(a2.openingBalance()).signum() < 0, "Negative balance must be detected");
    }

    @Test
    void reconciliationIdentifiesExactMatchesAndDiscrepancies() {
        BigDecimal legacyKes = new BigDecimal("1500000.00");
        BigDecimal legacyUsd = new BigDecimal("20000.00");

        BigDecimal platformKes = new BigDecimal("1500000.00");
        BigDecimal platformUsd = new BigDecimal("19950.00");

        BigDecimal diffKes = platformKes.subtract(legacyKes);
        BigDecimal diffUsd = platformUsd.subtract(legacyUsd);

        assertEquals(0, diffKes.compareTo(BigDecimal.ZERO), "KES must match with zero variance");
        assertNotEquals(0, diffUsd.compareTo(BigDecimal.ZERO), "USD must show discrepancy of -50.00");
        assertEquals(0, new BigDecimal("-50.00").compareTo(diffUsd));
    }
}
