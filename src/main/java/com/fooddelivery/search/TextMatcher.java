package com.fooddelivery.search;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The analysis + fuzzy matching part of the simulated Elasticsearch:
 * lowercase + accent folding + split on non-alphanumerics (like the standard analyzer with asciifolding),
 * and ES "fuzziness: AUTO" edit distances: 0 for 1–2 chars, 1 for 3–5 chars, 2 above.
 */
final class TextMatcher {

    private TextMatcher() {
    }

    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(folded.split("[^a-z0-9]+")).filter(t -> !t.isEmpty()).toList();
    }

    /** Every query token must fuzzily match some token of the field (ES operator "and"). */
    static boolean matchesAll(List<String> queryTokens, String fieldText) {
        if (queryTokens.isEmpty()) {
            return false;
        }
        List<String> fieldTokens = tokens(fieldText);
        return queryTokens.stream().allMatch(q -> fieldTokens.stream().anyMatch(f -> fuzzyEquals(q, f)));
    }

    static boolean fuzzyEquals(String queryToken, String fieldToken) {
        int allowed = queryToken.length() <= 2 ? 0 : queryToken.length() <= 5 ? 1 : 2;
        if (Math.abs(queryToken.length() - fieldToken.length()) > allowed) {
            return false;
        }
        return levenshtein(queryToken, fieldToken, allowed) <= allowed;
    }

    /** Edit distance with early exit once every cell in a row exceeds the limit. */
    private static int levenshtein(String a, String b, int limit) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > limit) {
                return rowMin;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /** Autocomplete: any token of the text starts with the prefix (ES edge_ngram analyzer). */
    static boolean anyTokenStartsWith(String text, String prefix) {
        return tokens(text).stream().anyMatch(t -> t.startsWith(prefix));
    }
}
