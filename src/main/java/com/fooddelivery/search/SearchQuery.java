package com.fooddelivery.search;

public record SearchQuery(long cityId, String text, String cuisine, boolean vegOnly, boolean openOnly,
                          int page, int size) {
}
