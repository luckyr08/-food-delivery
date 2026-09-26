package com.fooddelivery.search;

import com.fooddelivery.outbox.OutboxHandler;
import com.fooddelivery.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

/**
 * Projects restaurants from MySQL into the search index. Idempotent and order-independent:
 * it always re-reads the current state and writes it with version = search_version, so duplicate or
 * out-of-order processing can never leave an older state in the index.
 */
@Component
public class SearchProjector implements OutboxHandler {

    private final RestaurantDocumentLoader loader;
    private final SearchIndex index;
    private final TransactionTemplate readOnlyTx;

    public SearchProjector(RestaurantDocumentLoader loader, SearchIndex index, PlatformTransactionManager txManager) {
        this.loader = loader;
        this.index = index;
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    public String aggregateType() {
        return OutboxWriter.RESTAURANT;
    }

    /** Runs inside the relay's transaction. Throws SearchUnavailableException -> events retried later. */
    @Override
    public void handle(Set<Long> restaurantIds) {
        RestaurantDocumentLoader.Loaded loaded = loader.load(restaurantIds);
        index.upsertAll(loaded.visible().values());
        loaded.versions().forEach((id, version) -> {
            if (!loaded.visible().containsKey(id)) {
                index.delete(id, version); // deactivated restaurant or city
            }
        });
    }

    /** Full rebuild from MySQL + atomic swap: startup, mapping changes, drift repair. */
    public int reindexAll() {
        return index.rebuild(() -> {
            RestaurantDocumentLoader.Loaded loaded = readOnlyTx.execute(status -> loader.loadAll());
            return loaded == null ? List.of() : List.copyOf(loaded.visible().values());
        });
    }
}
