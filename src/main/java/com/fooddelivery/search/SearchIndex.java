package com.fooddelivery.search;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Port for the restaurant search index, shaped after Elasticsearch operations. The application ships an
 * in-memory adapter ({@link InMemorySearchIndex}); a real Elasticsearch adapter implements the same
 * interface (bulk with version_type=external, versioned delete, alias swap, bool + nested query).
 * All methods throw {@link SearchUnavailableException} when the cluster is unreachable.
 */
public interface SearchIndex {

    /** Bulk upsert with external versioning: a document older than (or equal to) the stored one is ignored. */
    void upsertAll(Collection<RestaurantDocument> documents);

    /** Versioned delete (leaves a tombstone so an older upsert can't resurrect the document). */
    void delete(long id, long version);

    SearchHits search(SearchQuery query);

    /** Autocomplete over restaurant and dish names (ES edge n-grams). */
    List<String> suggest(long cityId, String prefix, int limit);

    /** Builds a fresh index from the loader and swaps it in atomically (ES: new index + alias swap). */
    int rebuild(Supplier<List<RestaurantDocument>> loader);
}
