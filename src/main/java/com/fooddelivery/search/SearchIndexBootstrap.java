package com.fooddelivery.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** The in-memory index starts empty, so it is rebuilt from MySQL once the app is ready. */
@Slf4j
@Component
class SearchIndexBootstrap {

    private final SearchProjector projector;

    SearchIndexBootstrap(SearchProjector projector) {
        this.projector = projector;
    }

    @EventListener(ApplicationReadyEvent.class)
    void rebuildOnStartup() {
        try {
            log.info("Search index rebuilt on startup: {} restaurants", projector.reindexAll());
        } catch (RuntimeException e) {
            log.error("Startup reindex failed; search falls back to MySQL until a reindex succeeds", e);
        }
    }
}
