package com.fooddelivery.common;

/**
 * Builds "contains" patterns for JPQL LIKE ... ESCAPE '\'.
 * Without escaping, a search for "100%" would treat % as a wildcard and match "100 Biryani".
 */
public final class SqlLike {

    private SqlLike() {
    }

    /** Null/blank input -> null (filter disabled); otherwise %escaped%. */
    public static String containsPattern(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String escaped = input.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
