package com.fooddelivery.restaurant;

import com.fooddelivery.city.CityService;
import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.outbox.OutboxWriter;
import com.fooddelivery.user.NewUserRequest;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.User;
import com.fooddelivery.user.UserRepository;
import com.fooddelivery.user.UserResponse;
import com.fooddelivery.user.UserService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestaurantAdminService {

    private final RestaurantRepository restaurantRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final CityService cityService;
    private final OutboxWriter outboxWriter;

    public RestaurantAdminService(RestaurantRepository restaurantRepository, UserRepository userRepository,
                                  UserService userService, CityService cityService, OutboxWriter outboxWriter) {
        this.restaurantRepository = restaurantRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.cityService = cityService;
        this.outboxWriter = outboxWriter;
    }

    /** Step 1 of onboarding: the owner account. One owner can later get several restaurants. */
    @Transactional
    public UserResponse createOwner(NewUserRequest request) {
        return UserResponse.from(userService.createUser(request, Role.RESTAURANT_OWNER));
    }

    /** Step 2 of onboarding. New restaurants start closed; the owner opens them. */
    @Transactional
    public RestaurantResponse create(CreateRestaurantRequest request) {
        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new NotFoundException("User", request.ownerId()));
        if (owner.getRole() != Role.RESTAURANT_OWNER || !owner.isActive()) {
            throw new BadRequestException(ErrorCode.INVALID_OWNER,
                    "User " + owner.getId() + " is not an active restaurant owner");
        }
        Restaurant restaurant = new Restaurant();
        restaurant.setOwner(owner);
        restaurant.setCity(cityService.requireActive(request.cityId()));
        restaurant.setName(request.name());
        restaurant.setAddress(request.address());
        restaurant.setCuisine(request.cuisine());
        restaurant.setOpen(false);
        Restaurant saved = restaurantRepository.save(restaurant);
        outboxWriter.restaurantChanged(saved.getId());
        return RestaurantResponse.from(saved);
    }

    @Transactional
    public RestaurantResponse update(Long id, UpdateRestaurantRequest request) {
        Restaurant restaurant = restaurantRepository.findWithOwnerAndCityById(id)
                .orElseThrow(() -> new NotFoundException("Restaurant", id));
        outboxWriter.restaurantChanged(id); // before modifying the entity (lock order, ADR 0013)
        if (request.name() != null) {
            restaurant.setName(request.name());
        }
        if (request.address() != null) {
            restaurant.setAddress(request.address());
        }
        if (request.cuisine() != null) {
            restaurant.setCuisine(request.cuisine().isEmpty() ? null : request.cuisine());
        }
        if (request.active() != null) {
            restaurant.setActive(request.active());
            if (!request.active()) {
                restaurant.setOpen(false); // a deactivated restaurant can't keep taking orders
            }
        }
        return RestaurantResponse.from(restaurant);
    }

    @Transactional(readOnly = true)
    public PageResponse<RestaurantResponse> search(Long cityId, Boolean active, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("name").and(Sort.by("id")));
        return PageResponse.from(restaurantRepository.search(cityId, active, pageable).map(RestaurantResponse::from));
    }
}
