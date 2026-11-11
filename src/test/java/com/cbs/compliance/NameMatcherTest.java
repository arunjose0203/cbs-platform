package com.cbs.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NameMatcherTest {
    private static NameMatcher.Entry person(String ref, LocalDate born, String... names) {
        return new NameMatcher.Entry(ref, true, List.of(names), born, "h" + ref);
    }

    private final NameMatcher.Index index = new NameMatcher.Index(List.of(
            person("UN-1", LocalDate.of(1970, 3, 1), "Viktor Petrov", "Viktor Ivanovich PETROV"),
            person("UN-2", null, "José María Ñúñez"),
            new NameMatcher.Entry("UN-3", false, List.of("Blue Ocean Trading Co. Ltd"), null, "h3"),
            person("UN-4", null, "Mohammed Al Rashid")));

    @Test void normalizesAccentsPunctuationTitlesAndSuffixes() {
        assertThat(NameMatcher.tokens("Dr. José-María  Ñúñez")).containsExactly("JOSE", "MARIA", "NUNEZ");
        assertThat(NameMatcher.tokens("Blue Ocean Trading Co. Ltd")).containsExactly("BLUE", "OCEAN", "TRADING");
        assertThat(NameMatcher.tokens("  ")).isEmpty();
    }

    @Test void findsReorderedMisspelledAndAccentFreeVariants() {
        assertThat(index.match("PETROV, Viktor", null, true, 0.9)).first()
                .satisfies(m -> { assertThat(m.entry().ref()).isEqualTo("UN-1"); assertThat(m.score()).isEqualTo(1.0); });
        assertThat(index.match("Victor Petrof", null, true, 0.85)).extracting(m -> m.entry().ref()).containsExactly("UN-1");
        assertThat(index.match("Jose Maria Nunez", null, true, 0.9)).extracting(m -> m.entry().ref()).containsExactly("UN-2");
        assertThat(index.match("Mohamed Al-Rashid", null, true, 0.85)).extracting(m -> m.entry().ref()).containsExactly("UN-4");
        assertThat(index.match("Blue Ocean Trading Limited", null, false, 0.9)).extracting(m -> m.entry().ref()).containsExactly("UN-3");
        assertThat(index.match("V. Petrov", null, true, 0.85)).extracting(m -> m.entry().ref()).containsExactly("UN-1");
    }

    @Test void birthDatesRuleOutDifferentPeopleWithTheSameName() {
        assertThat(index.match("Viktor Petrov", LocalDate.of(1971, 1, 1), true, 0.9)).hasSize(1);
        assertThat(index.match("Viktor Petrov", LocalDate.of(1990, 5, 5), true, 0.9)).isEmpty();
        // Organizations are never ruled out by an individual's date of birth.
        assertThat(index.match("Viktor Petrov", LocalDate.of(1990, 5, 5), false, 0.9)).hasSize(1);
    }

    @Test void unrelatedAndWeakNamesStayBelowThreshold() {
        assertThat(index.match("Amina Wanjiru", null, true, 0.8)).isEmpty();
        assertThat(index.match("Viktor", null, true, 0.9)).isEmpty();
        assertThat(index.match("Peter Petrovic", null, true, 0.9)).isEmpty();
        assertThat(NameMatcher.similarity(List.of("A"), List.of())).isZero();
    }

    @Test void jaroWinklerReferenceValues() {
        assertThat(NameMatcher.jaroWinkler("MARTHA", "MARHTA")).isBetween(0.960, 0.962);
        assertThat(NameMatcher.jaroWinkler("DWAYNE", "DUANE")).isBetween(0.839, 0.841);
        assertThat(NameMatcher.jaroWinkler("ABC", "XYZ")).isZero();
    }
}
