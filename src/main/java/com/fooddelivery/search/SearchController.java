package com.fooddelivery.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public search: typo-tolerant, matches restaurant names, cuisine and dishes. */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/restaurants")
    public SearchResponse search(@RequestParam long cityId,
                                 @RequestParam(required = false) @Size(max = 100) String q,
                                 @RequestParam(required = false) @Size(max = 100) String cuisine,
                                 @RequestParam(defaultValue = "false") boolean vegOnly,
                                 @RequestParam(defaultValue = "false") boolean openOnly,
                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        String cuisineFilter = cuisine == null || cuisine.isBlank() ? null : cuisine.trim();
        return searchService.search(new SearchQuery(cityId, q, cuisineFilter, vegOnly, openOnly, page, size));
    }

    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam long cityId,
                                @RequestParam @Size(min = 1, max = 50) String q,
                                @RequestParam(defaultValue = "8") @Min(1) @Max(20) int limit) {
        return searchService.suggest(cityId, q, limit);
    }
}
