package dev.tachyonscript.language.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * "Did you mean" suggestions based on edit distance.
 *
 * <p>Uses the optimal string alignment distance (Levenshtein plus adjacent transpositions,
 * so {@code giev} is one edit away from {@code give}), compared case-insensitively.
 */
public final class Suggestions {

    private Suggestions() {
    }

    /** Up to {@code max} candidates close to {@code input}, best first. */
    public static List<String> closest(String input, Collection<String> candidates, int max) {
        String needle = input.toLowerCase(Locale.ROOT);
        int threshold = Math.max(1, Math.min(3, needle.length() / 3 + 1));
        record Scored(String candidate, int distance) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.equals(input)) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            int distance = lower.equals(needle) ? 0 : distance(needle, lower);
            if (distance <= threshold) {
                scored.add(new Scored(candidate, distance));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::distance).thenComparing(Scored::candidate));
        List<String> result = new ArrayList<>();
        for (Scored entry : scored) {
            if (result.size() == max) {
                break;
            }
            result.add(entry.candidate());
        }
        return result;
    }

    /** Optimal string alignment distance between {@code a} and {@code b}. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, d[i - 2][j - 2] + 1);
                }
                d[i][j] = value;
            }
        }
        return d[a.length()][b.length()];
    }
}
