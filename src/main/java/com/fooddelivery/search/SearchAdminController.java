package com.fooddelivery.search;

import com.fooddelivery.outbox.OutboxRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/search")
@PreAuthorize("hasRole('ADMIN')")
public class SearchAdminController {

    private final SearchProjector projector;
    private final InMemorySearchIndex index;
    private final OutboxRepository outbox;

    public SearchAdminController(SearchProjector projector, InMemorySearchIndex index, OutboxRepository outbox) {
        this.projector = projector;
        this.index = index;
        this.outbox = outbox;
    }

    /** Full rebuild from MySQL + atomic swap (drift repair, mapping change). */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        long start = System.nanoTime();
        int indexed = projector.reindexAll();
        return Map.of("indexed", indexed, "durationMs", (System.nanoTime() - start) / 1_000_000);
    }

    /** Sync health: index size, outbox backlog, retries, rejected stale writes. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("indexAvailable", index.isAvailable());
        status.put("documents", index.documentCount());
        status.put("documentWrites", index.documentWrites());
        status.put("staleWritesRejected", index.staleWritesRejected());
        status.putAll(outbox.stats());
        return status;
    }

    record AvailabilityRequest(@NotNull Boolean available) {
    }

    /** SIMULATION ONLY: take the in-memory "cluster" down/up to demonstrate outbox retries and fallback. */
    @PutMapping("/simulated-availability")
    public Map<String, Object> setAvailability(@Valid @RequestBody AvailabilityRequest request) {
        index.setAvailable(request.available());
        return status();
    }
}
