package com.fooddelivery.search;

import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.restaurant.RestaurantBrowseService;
import com.fooddelivery.restaurant.RestaurantSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** Search reads the index; if the cluster is down it degrades to the MySQL name search instead of failing. */
@Slf4j
@Service
public class SearchService {

    static final String FROM_INDEX = "search-index";
    static final String FROM_DATABASE = "database-fallback";

    private final SearchIndex index;
    private final RestaurantBrowseService browseService;

    public SearchService(SearchIndex index, RestaurantBrowseService browseService) {
        this.index = index;
        this.browseService = browseService;
    }

    public SearchResponse search(SearchQuery query) {
        try {
            SearchHits hits = index.search(query);
            return new SearchResponse(FROM_INDEX, hits.hits().stream().map(SearchResultItem::from).toList(),
                    query.page(), query.size(), hits.total(), totalPages(hits.total(), query.size()));
        } catch (SearchUnavailableException e) {
            log.warn("Search index unavailable, falling back to MySQL: {}", e.getMessage());
            // Fallback: plain name search, no fuzziness, dish matching or veg filter.
            PageResponse<RestaurantSummaryResponse> page = browseService.browse(query.cityId(), query.text(),
                    query.cuisine(), query.openOnly(), query.page(), query.size());
            return new SearchResponse(FROM_DATABASE, page.content().stream()
                    .map(r -> new SearchResultItem(r.id(), r.name(), r.cuisine(), r.cityName(), r.open(),
                            r.averageRating(), r.ratingCount(), List.of(), null))
                    .toList(), page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }

    public List<String> suggest(long cityId, String prefix, int limit) {
        try {
            return index.suggest(cityId, prefix, limit);
        } catch (SearchUnavailableException e) {
            return List.of(); // autocomplete is best effort
        }
    }

    private static int totalPages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }
}
