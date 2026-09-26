package com.fooddelivery.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * SIMULATED Elasticsearch: an in-memory adapter of {@link SearchIndex} that mimics the ES behaviours the
 * design relies on — external versioning with tombstones, fuzzy full-text matching, nested dish matches,
 * relevance scoring, filters, autocomplete, alias-swap rebuilds and cluster outages.
 * Limitations: lives in one JVM (rebuilt from MySQL on startup) and isn't shared between app instances.
 */
@Slf4j
@Component
public class InMemorySearchIndex implements SearchIndex {

    /** version + document; document == null is a tombstone left by a versioned delete. */
    private record Entry(long version, RestaurantDocument document) {
    }

    // "Alias" -> current index. During a rebuild, live writes also go to the index being built.
    private volatile Map<Long, Entry> current = new ConcurrentHashMap<>();
    private volatile Map<Long, Entry> building;
    private final ReentrantReadWriteLock swapLock = new ReentrantReadWriteLock();

    private volatile boolean available = true;
    private final AtomicLong documentWrites = new AtomicLong();
    private final AtomicLong staleWritesRejected = new AtomicLong();

    // ---- writes ----

    @Override
    public void upsertAll(Collection<RestaurantDocument> documents) {
        ensureAvailable();
        swapLock.readLock().lock();
        try {
            for (RestaurantDocument doc : documents) {
                write(doc.id(), new Entry(doc.version(), doc));
            }
        } finally {
            swapLock.readLock().unlock();
        }
    }

    @Override
    public void delete(long id, long version) {
        ensureAvailable();
        swapLock.readLock().lock();
        try {
            write(id, new Entry(version, null));
        } finally {
            swapLock.readLock().unlock();
        }
    }

    private void write(long id, Entry entry) {
        applyVersioned(current, id, entry);
        Map<Long, Entry> target = building;
        if (target != null) {
            applyVersioned(target, id, entry); // don't lose writes that happen during a rebuild
        }
    }

    /** version_type=external: only a strictly newer version replaces what's stored. */
    private void applyVersioned(Map<Long, Entry> index, long id, Entry incoming) {
        index.compute(id, (key, existing) -> {
            if (existing != null && existing.version() >= incoming.version()) {
                staleWritesRejected.incrementAndGet();
                return existing;
            }
            documentWrites.incrementAndGet();
            return incoming;
        });
    }

    @Override
    public int rebuild(Supplier<List<RestaurantDocument>> loader) {
        ensureAvailable();
        Map<Long, Entry> fresh = new ConcurrentHashMap<>();
        swapLock.writeLock().lock();
        try {
            building = fresh;
        } finally {
            swapLock.writeLock().unlock();
        }
        try {
            List<RestaurantDocument> docs = loader.get();
            docs.forEach(doc -> applyVersioned(fresh, doc.id(), new Entry(doc.version(), doc)));
            swapLock.writeLock().lock();
            try {
                current = fresh; // alias swap
                return docs.size();
            } finally {
                swapLock.writeLock().unlock();
            }
        } finally {
            building = null;
        }
    }

    // ---- reads ----

    @Override
    public SearchHits search(SearchQuery q) {
        ensureAvailable();
        List<String> tokens = TextMatcher.tokens(q.text());
        List<SearchHit> hits = new ArrayList<>();
        for (Entry entry : current.values()) {
            RestaurantDocument doc = entry.document();
            if (doc == null || doc.cityId() != q.cityId()
                    || (q.openOnly() && !doc.open())
                    || (q.cuisine() != null && !q.cuisine().equalsIgnoreCase(doc.cuisine()))) {
                continue;
            }
            List<MenuDocument> candidates = doc.menu().stream()
                    .filter(m -> !q.vegOnly() || m.veg())
                    .toList();
            if (q.vegOnly() && candidates.isEmpty()) {
                continue;
            }
            double score = 0;
            List<String> matchedDishes = List.of();
            if (!tokens.isEmpty()) {
                if (TextMatcher.matchesAll(tokens, doc.name())) {
                    score += 3; // name^3
                }
                if (TextMatcher.matchesAll(tokens, doc.cuisine())) {
                    score += 2; // cuisine^2
                }
                matchedDishes = candidates.stream()
                        .filter(m -> TextMatcher.matchesAll(tokens, m.name()))
                        .map(MenuDocument::name).toList();
                if (!matchedDishes.isEmpty()) {
                    score += 1 + Math.min(0.5, 0.1 * matchedDishes.size()); // nested menu.name
                }
                if (score == 0) {
                    continue;
                }
            }
            score += doc.open() ? 0.5 : 0; // function_score boosts
            score += doc.averageRating() == null ? 0 : doc.averageRating().doubleValue() / 10;
            hits.add(new SearchHit(doc, score, matchedDishes));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(h -> h.document().name()).thenComparingLong(h -> h.document().id()));
        int from = Math.min(q.page() * q.size(), hits.size());
        int to = Math.min(from + q.size(), hits.size());
        return new SearchHits(List.copyOf(hits.subList(from, to)), hits.size());
    }

    @Override
    public List<String> suggest(long cityId, String prefix, int limit) {
        ensureAvailable();
        List<String> tokens = TextMatcher.tokens(prefix);
        if (tokens.isEmpty()) {
            return List.of();
        }
        String last = tokens.get(tokens.size() - 1);
        Set<String> restaurants = new LinkedHashSet<>();
        Set<String> dishes = new LinkedHashSet<>();
        current.values().stream()
                .map(Entry::document)
                .filter(d -> d != null && d.cityId() == cityId)
                .sorted(Comparator.comparing(RestaurantDocument::name))
                .forEach(d -> {
                    if (TextMatcher.anyTokenStartsWith(d.name(), last)) {
                        restaurants.add(d.name());
                    }
                    d.menu().stream().filter(m -> TextMatcher.anyTokenStartsWith(m.name(), last))
                            .forEach(m -> dishes.add(m.name()));
                });
        List<String> out = new ArrayList<>(restaurants);
        dishes.stream().sorted().forEach(out::add);
        return out.stream().distinct().limit(limit).toList();
    }

    // ---- operations / simulation controls ----

    /** Simulates the cluster going down / coming back (tests, demos). */
    public void setAvailable(boolean available) {
        this.available = available;
        log.warn("Simulated search cluster is now {}", available ? "UP" : "DOWN");
    }

    public boolean isAvailable() {
        return available;
    }

    public long documentCount() {
        return current.values().stream().filter(e -> e.document() != null).count();
    }

    public long documentWrites() {
        return documentWrites.get();
    }

    public long staleWritesRejected() {
        return staleWritesRejected.get();
    }

    /** Test support: empty index, cluster up, counters reset. */
    public void reset() {
        current = new ConcurrentHashMap<>();
        building = null;
        available = true;
        documentWrites.set(0);
        staleWritesRejected.set(0);
    }

    private void ensureAvailable() {
        if (!available) {
            throw new SearchUnavailableException("Search cluster unavailable");
        }
    }
}
