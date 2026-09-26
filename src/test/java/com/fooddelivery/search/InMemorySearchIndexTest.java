package com.fooddelivery.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests of the simulated Elasticsearch behaviours the sync design depends on. */
class InMemorySearchIndexTest {

    private InMemorySearchIndex index;

    private static MenuDocument dish(long id, String name, boolean veg) {
        return new MenuDocument(id, name, "Mains", new BigDecimal("200.00"), veg, true);
    }

    private static RestaurantDocument restaurant(long id, String name, String cuisine, boolean open, long version,
                                                 MenuDocument... menu) {
        return new RestaurantDocument(id, name, "Somewhere", cuisine, 1, "Pune", open, new BigDecimal("4.0"), 3,
                List.of(menu), version);
    }

    private static SearchQuery query(String text) {
        return new SearchQuery(1, text, null, false, false, 0, 20);
    }

    @BeforeEach
    void setUp() {
        index = new InMemorySearchIndex();
        index.upsertAll(List.of(
                restaurant(1, "Spice Hub", "North Indian", true, 1,
                        dish(11, "Chicken Biryani", false), dish(12, "Paneer Tikka", true)),
                restaurant(2, "Biryani House", "Hyderabadi", false, 1, dish(21, "Mutton Biryani", false)),
                restaurant(3, "Dosa Corner", "South Indian", true, 1, dish(31, "Masala Dosa", true))));
    }

    @Test
    void typoToleratedAndNameOutranksDishMatch() {
        SearchHits hits = index.search(query("biryni"));

        assertThat(hits.hits()).extracting(h -> h.document().name()).containsExactly("Biryani House", "Spice Hub");
        assertThat(hits.hits().get(1).matchedDishes()).containsExactly("Chicken Biryani");
    }

    @Test
    void dishSearchFindsRestaurantsServingIt() {
        assertThat(index.search(query("panner")).hits()).singleElement()
                .satisfies(h -> assertThat(h.matchedDishes()).containsExactly("Paneer Tikka"));
    }

    @Test
    void vegOnlyAppliesToTheSameDish() {
        // "biryani" + vegOnly: both biryanis are non-veg, so nothing matches through the menu
        SearchHits hits = index.search(new SearchQuery(1, "biryani", null, true, false, 0, 20));

        assertThat(hits.hits()).extracting(h -> h.document().name()).doesNotContain("Spice Hub");
    }

    @Test
    void filtersAndPagination() {
        assertThat(index.search(new SearchQuery(1, null, "south indian", false, false, 0, 20)).total()).isEqualTo(1);
        assertThat(index.search(new SearchQuery(1, null, null, false, true, 0, 20)).total()).isEqualTo(2);
        SearchHits page = index.search(new SearchQuery(1, null, null, false, false, 1, 2));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hits()).hasSize(1);
    }

    @Test
    void externalVersioningRejectsStaleWrites() {
        index.upsertAll(List.of(restaurant(3, "Dosa Corner NEW", "South Indian", true, 5)));
        index.upsertAll(List.of(restaurant(3, "Dosa Corner OLD", "South Indian", true, 4)));

        assertThat(index.search(query("dosa")).hits().get(0).document().name()).isEqualTo("Dosa Corner NEW");
        assertThat(index.staleWritesRejected()).isEqualTo(1);
    }

    @Test
    void tombstoneStopsAnOlderUpsertFromResurrectingADeletedDocument() {
        index.delete(3, 7);
        index.upsertAll(List.of(restaurant(3, "Dosa Corner", "South Indian", true, 6)));

        assertThat(index.search(query("dosa")).total()).isZero();
        assertThat(index.documentCount()).isEqualTo(2);
    }

    @Test
    void rebuildSwapsAtomicallyAndKeepsWritesMadeDuringIt() {
        int indexed = index.rebuild(() -> {
            // a live write lands while the new index is being built...
            index.upsertAll(List.of(restaurant(4, "Late Arrival", "Cafe", true, 9)));
            return List.of(restaurant(1, "Spice Hub", "North Indian", true, 2));
        });

        assertThat(indexed).isEqualTo(1);
        // ...and survives the swap; documents missing from the rebuild are gone
        assertThat(index.search(query(null)).hits()).extracting(h -> h.document().name())
                .containsExactlyInAnyOrder("Spice Hub", "Late Arrival");
    }

    @Test
    void autocompleteByPrefixRestaurantsFirst() {
        assertThat(index.suggest(1, "bir", 5)).containsExactly("Biryani House", "Chicken Biryani", "Mutton Biryani");
    }

    @Test
    void outageThrows() {
        index.setAvailable(false);

        assertThatThrownBy(() -> index.search(query("dosa"))).isInstanceOf(SearchUnavailableException.class);
        assertThatThrownBy(() -> index.upsertAll(List.of())).isInstanceOf(SearchUnavailableException.class);
    }
}
