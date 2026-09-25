package com.fooddelivery.restaurant;

import com.fooddelivery.common.SqlLike;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestaurantBrowseService {

    private final RestaurantRepository restaurantRepository;

    public RestaurantBrowseService(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<RestaurantSummaryResponse> browse(Long cityId, String query, String cuisine, boolean openOnly,
                                                          int page, int size) {
        String cuisineFilter = cuisine == null || cuisine.isBlank() ? null : cuisine.trim();
        return PageResponse.from(restaurantRepository
                .browse(cityId, openOnly, cuisineFilter, SqlLike.containsPattern(query), PageRequest.of(page, size))
                .map(RestaurantSummaryResponse::from));
    }

    @Transactional(readOnly = true)
    public RestaurantSummaryResponse get(Long id) {
        return RestaurantSummaryResponse.from(requirePublic(id));
    }

    /** Visible to customers: active restaurant in an active city; otherwise 404. */
    @Transactional(readOnly = true)
    public Restaurant requirePublic(Long id) {
        return restaurantRepository.findPublicById(id).orElseThrow(() -> new NotFoundException("Restaurant", id));
    }
}
