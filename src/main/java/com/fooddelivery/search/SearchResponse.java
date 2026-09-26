package com.fooddelivery.search;

import java.util.List;

/** source: "search-index" normally, "database-fallback" when the search cluster is unavailable. */
public record SearchResponse(String source, List<SearchResultItem> content, int page, int size,
                             long totalElements, int totalPages) {
}
