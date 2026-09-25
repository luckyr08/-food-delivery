package com.fooddelivery.restaurant;

import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RestaurantOwnerService {

    private final RestaurantRepository restaurantRepository;

    public RestaurantOwnerService(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }

    @Transactional(readOnly = true)
    public List<RestaurantResponse> myRestaurants(Long ownerId) {
        return restaurantRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .map(RestaurantResponse::from).toList();
    }

    @Transactional
    public RestaurantResponse setOpen(Long restaurantId, Long ownerId, boolean open) {
        Restaurant restaurant = requireOwned(restaurantId, ownerId);
        if (open && (!restaurant.isActive() || !restaurant.getCity().isActive())) {
            throw new ConflictException(ErrorCode.RESTAURANT_INACTIVE,
                    "Restaurant " + restaurantId + " is deactivated and cannot be opened");
        }
        restaurant.setOpen(open);
        return RestaurantResponse.from(restaurant);
    }

    /**
     * Ownership check used by every owner operation. 404 (not 403) for other owners' restaurants,
     * so their existence isn't revealed.
     */
    @Transactional(readOnly = true)
    public Restaurant requireOwned(Long restaurantId, Long ownerId) {
        return restaurantRepository.findByIdAndOwnerId(restaurantId, ownerId)
                .orElseThrow(() -> new NotFoundException("Restaurant", restaurantId));
    }
}
