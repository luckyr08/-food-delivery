package com.fooddelivery.demo;

import com.fooddelivery.city.CityRepository;
import com.fooddelivery.city.CityRequest;
import com.fooddelivery.city.CityService;
import com.fooddelivery.delivery.CreateDeliveryPartnerRequest;
import com.fooddelivery.delivery.DeliveryAssignmentService;
import com.fooddelivery.delivery.DeliveryPartnerAdminService;
import com.fooddelivery.delivery.PartnerStatus;
import com.fooddelivery.delivery.VehicleType;
import com.fooddelivery.menu.MenuItemRequest;
import com.fooddelivery.menu.MenuOwnerService;
import com.fooddelivery.restaurant.CreateRestaurantRequest;
import com.fooddelivery.restaurant.RestaurantAdminService;
import com.fooddelivery.restaurant.RestaurantOwnerService;
import com.fooddelivery.user.NewUserRequest;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Sample data for trying the API (profile "demo" only; never in tests or by default).
 * Seeds through the real services, so validation, password hashing and business rules apply.
 * Idempotent: skipped if the demo city already exists.
 */
@Slf4j
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String PASSWORD = "Password@123";

    private final CityRepository cityRepository;
    private final CityService cityService;
    private final RestaurantAdminService restaurantAdminService;
    private final RestaurantOwnerService restaurantOwnerService;
    private final MenuOwnerService menuOwnerService;
    private final DeliveryPartnerAdminService partnerAdminService;
    private final DeliveryAssignmentService assignmentService;
    private final UserService userService;

    public DemoDataSeeder(CityRepository cityRepository, CityService cityService,
                          RestaurantAdminService restaurantAdminService, RestaurantOwnerService restaurantOwnerService,
                          MenuOwnerService menuOwnerService, DeliveryPartnerAdminService partnerAdminService,
                          DeliveryAssignmentService assignmentService, UserService userService) {
        this.cityRepository = cityRepository;
        this.cityService = cityService;
        this.restaurantAdminService = restaurantAdminService;
        this.restaurantOwnerService = restaurantOwnerService;
        this.menuOwnerService = menuOwnerService;
        this.partnerAdminService = partnerAdminService;
        this.assignmentService = assignmentService;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (cityRepository.existsByName("Pune")) {
            log.info("Demo data already present, skipping");
            return;
        }
        Long pune = cityService.create(new CityRequest("Pune", "Maharashtra")).id();
        Long bengaluru = cityService.create(new CityRequest("Bengaluru", "Karnataka")).id();

        Long ravi = owner("Ravi Sharma", "owner1@demo.com");
        Long meera = owner("Meera Iyer", "owner2@demo.com");

        Long spiceHub = openRestaurant(ravi, pune, "Spice Hub", "12 MG Road, Pune", "North Indian");
        item(spiceHub, ravi, "Paneer Tikka", "Starters", "249.00", true, null);
        item(spiceHub, ravi, "Chicken Biryani", "Mains", "329.00", false, 5); // limited: try to oversell it
        menuOwnerService.create(spiceHub, ravi, new MenuItemRequest("Festival Thali", null, "Specials",
                new BigDecimal("499.00"), true, true, 20, true)); // flash-sale item (Redis gate when enabled)
        item(spiceHub, ravi, "Dal Makhani", "Mains", "219.00", true, null);
        item(spiceHub, ravi, "Butter Naan", "Breads", "49.00", true, 50);

        Long dosaCorner = openRestaurant(ravi, pune, "Dosa Corner", "8 FC Road, Pune", "South Indian");
        item(dosaCorner, ravi, "Masala Dosa", "Mains", "129.00", true, null);
        item(dosaCorner, ravi, "Filter Coffee", "Beverages", "49.00", true, 20);

        Long bowlCo = openRestaurant(meera, bengaluru, "The Bowl Co", "100 Ft Road, Indiranagar", "Healthy");
        item(bowlCo, meera, "Quinoa Salad Bowl", "Bowls", "299.00", true, 10);
        item(bowlCo, meera, "Grilled Chicken Bowl", "Bowls", "349.00", false, null);

        partner("Kiran Patil", "partner1@demo.com", pune, VehicleType.SCOOTER);
        partner("Arjun Rao", "partner2@demo.com", pune, VehicleType.MOTORBIKE);
        partner("Deepa N", "partner3@demo.com", bengaluru, VehicleType.BICYCLE);

        userService.createUser(new NewUserRequest("Asha Kulkarni", "customer@demo.com", PASSWORD, "9876543210"),
                Role.CUSTOMER);

        log.info("Demo data seeded: 2 cities, 3 open restaurants, 3 available partners, 1 customer "
                + "(password for all demo users: {})", PASSWORD);
    }

    private Long owner(String name, String email) {
        return restaurantAdminService.createOwner(new NewUserRequest(name, email, PASSWORD, null)).id();
    }

    private Long openRestaurant(Long ownerId, Long cityId, String name, String address, String cuisine) {
        Long id = restaurantAdminService.create(new CreateRestaurantRequest(ownerId, cityId, name, address, cuisine)).id();
        restaurantOwnerService.setOpen(id, ownerId, true);
        return id;
    }

    private void item(Long restaurantId, Long ownerId, String name, String category, String price, boolean veg,
                      Integer stock) {
        menuOwnerService.create(restaurantId, ownerId,
                new MenuItemRequest(name, null, category, new BigDecimal(price), veg, true, stock, false));
    }

    private void partner(String name, String email, Long cityId, VehicleType vehicle) {
        Long userId = partnerAdminService.create(
                new CreateDeliveryPartnerRequest(name, email, PASSWORD, null, cityId, vehicle)).userId();
        assignmentService.setAvailability(userId, PartnerStatus.AVAILABLE);
    }
}
