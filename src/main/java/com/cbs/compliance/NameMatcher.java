package com.cbs.compliance;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fuzzy name screening against an approved list.
 *
 * <p>Names are normalized (accents removed, upper case, punctuation dropped, titles and company
 * suffixes ignored) and compared token by token with Jaro-Winkler similarity, independent of word
 * order, so "Smith, John" matches "JOHN SMITH" and small spelling differences still match. Initials
 * match the token they abbreviate. A single-token name against a longer one is discounted, because a
 * shared first name alone is weak evidence. When both sides are individuals with a date of birth, a
 * difference of more than one year rules the entry out. Candidate entries are found through a
 * three-letter token prefix index, so a typo in the first three letters of every token is not found.
 */
public final class NameMatcher {
    private static final Set<String> NOISE = Set.of("MR", "MRS", "MS", "MISS", "DR", "PROF", "SIR", "HON", "THE",
            "AND", "LTD", "LIMITED", "PLC", "INC", "LLC", "CO", "COMPANY", "CORP", "CORPORATION");
    private static final double TOKEN_FLOOR = 0.80;

    private NameMatcher() { }

    public record Entry(String ref, boolean individual, List<String> names, LocalDate birthDate, String hash) { }

    public record Match(Entry entry, String matchedName, double score) { }

    public static List<String> tokens(String name) {
        if (name == null) { return List.of(); }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
        if (plain.isEmpty()) { return List.of(); }
        var out = new ArrayList<String>();
        for (String t : plain.split(" ")) { if (!NOISE.contains(t)) { out.add(t); } }
        return List.copyOf(out);
    }

    /** Order-independent similarity of two names in [0,1]. */
    public static double similarity(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) { return 0; }
        List<String> shorter = a.size() <= b.size() ? a : b, longer = shorter == a ? b : a;
        // Score every pair, then take the best pairs greedily so each token is used once.
        record Pair(int i, int j, double score) { }
        var pairs = new ArrayList<Pair>();
        for (int i = 0; i < shorter.size(); i++) {
            for (int j = 0; j < longer.size(); j++) {
                double s = token(shorter.get(i), longer.get(j));
                if (s >= TOKEN_FLOOR) { pairs.add(new Pair(i, j, s)); }
            }
        }
        pairs.sort((x, y) -> Double.compare(y.score(), x.score()));
        boolean[] usedShort = new boolean[shorter.size()], usedLong = new boolean[longer.size()];
        double total = 0;
        for (var p : pairs) {
            if (usedShort[p.i()] || usedLong[p.j()]) { continue; }
            usedShort[p.i()] = true; usedLong[p.j()] = true; total += p.score();
        }
        double score = total / shorter.size();
        if (shorter.size() == 1 && longer.size() > 1) { score *= 0.85; }
        return Math.min(1, score);
    }

    private static double token(String x, String y) {
        if (x.equals(y)) { return 1; }
        if (x.length() == 1 || y.length() == 1) {
            return (x.length() == 1 ? y : x).startsWith(x.length() == 1 ? x : y) ? 0.9 : 0;
        }
        return jaroWinkler(x, y);
    }

    static double jaroWinkler(String s, String t) {
        int sl = s.length(), tl = t.length();
        if (sl == 0 || tl == 0) { return 0; }
        int window = Math.max(0, Math.max(sl, tl) / 2 - 1);
        boolean[] sm = new boolean[sl], tm = new boolean[tl];
        int matches = 0;
        for (int i = 0; i < sl; i++) {
            for (int j = Math.max(0, i - window); j < Math.min(tl, i + window + 1); j++) {
                if (!tm[j] && s.charAt(i) == t.charAt(j)) { sm[i] = tm[j] = true; matches++; break; }
            }
        }
        if (matches == 0) { return 0; }
        int k = 0, transpositions = 0;
        for (int i = 0; i < sl; i++) {
            if (!sm[i]) { continue; }
            while (!tm[k]) { k++; }
            if (s.charAt(i) != t.charAt(k)) { transpositions++; }
            k++;
        }
        double m = matches, jaro = (m / sl + m / tl + (m - transpositions / 2.0) / m) / 3;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(sl, tl)) && s.charAt(prefix) == t.charAt(prefix)) { prefix++; }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    private static String key(String token) { return token.length() <= 3 ? token : token.substring(0, 3); }

    /** An immutable, searchable form of one list version. */
    public static final class Index {
        private record Name(Entry entry, String raw, List<String> tokens) { }
        private final Map<String, List<Name>> byKey = new HashMap<>();
        private final int size;

        public Index(Collection<Entry> entries) {
            int n = 0;
            for (var e : entries) {
                for (String raw : e.names()) {
                    var name = new Name(e, raw, tokens(raw));
                    for (String k : new HashSet<>(name.tokens().stream().map(NameMatcher::key).toList())) {
                        byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(name);
                    }
                }
                n++;
            }
            size = n;
        }

        public int size() { return size; }

        /** Best match per entry at or above the threshold, strongest first. */
        public List<Match> match(String customerName, LocalDate birthDate, boolean individual, double threshold) {
            var mine = tokens(customerName);
            var candidates = new LinkedHashSet<Name>();
            for (String t : mine) {
                candidates.addAll(byKey.getOrDefault(key(t), List.of()));
                if (t.length() == 1) {
                    // An initial can abbreviate any token starting with it.
                    byKey.forEach((k, v) -> { if (k.startsWith(t)) { candidates.addAll(v); } });
                }
            }
            var best = new LinkedHashMap<String, Match>();
            for (var c : candidates) {
                if (individual && c.entry().individual() && birthDate != null && c.entry().birthDate() != null
                        && Math.abs(ChronoUnit.YEARS.between(c.entry().birthDate(), birthDate)) > 1) { continue; }
                double score = Math.round(similarity(mine, c.tokens()) * 1000) / 1000.0;
                if (score + 1e-9 < threshold) { continue; }
                var old = best.get(c.entry().ref());
                if (old == null || old.score() < score) { best.put(c.entry().ref(), new Match(c.entry(), c.raw(), score)); }
            }
            var out = new ArrayList<>(best.values());
            out.sort((x, y) -> Double.compare(y.score(), x.score()));
            return out;
        }
    }
}
