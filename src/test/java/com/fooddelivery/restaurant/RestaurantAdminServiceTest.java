package com.fooddelivery.restaurant;

import com.fooddelivery.city.City;
import com.fooddelivery.city.CityService;
import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.outbox.OutboxWriter;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.User;
import com.fooddelivery.user.UserRepository;
import com.fooddelivery.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Business rules of restaurant onboarding, without Spring or a database. */
@ExtendWith(MockitoExtension.class)
class RestaurantAdminServiceTest {

    @Mock
    RestaurantRepository restaurantRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    UserService userService;
    @Mock
    CityService cityService;
    @Mock
    OutboxWriter outboxWriter;
    @InjectMocks
    RestaurantAdminService service;

    private final CreateRestaurantRequest request =
            new CreateRestaurantRequest(1L, 10L, "Spice Hub", "MG Road", "Indian");

    private static User user(Role role, boolean active) {
        User u = new User();
        u.setName("U");
        u.setRole(role);
        u.setActive(active);
        return u;
    }

    @Test
    void rejectsCustomerAsOwner() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(Role.CUSTOMER, true)));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_OWNER);
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void rejectsDeactivatedOwner() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(Role.RESTAURANT_OWNER, false)));

        assertThatThrownBy(() -> service.create(request))
                .extracting("code").isEqualTo(ErrorCode.INVALID_OWNER);
    }

    @Test
    void newRestaurantStartsClosed() {
        City city = new City();
        city.setName("Pune");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(Role.RESTAURANT_OWNER, true)));
        when(cityService.requireActive(10L)).thenReturn(city);
        when(restaurantRepository.save(any())).thenAnswer(inv -> {
            Restaurant saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 99L); // what the database would assign
            return saved;
        });

        RestaurantResponse response = service.create(request);

        verify(outboxWriter).restaurantChanged(99L); // new restaurant is queued for the search index

        assertThat(response.open()).isFalse();
        assertThat(response.active()).isTrue();
        assertThat(response.cityName()).isEqualTo("Pune");
    }
}
