package com.fooddelivery.delivery;

import com.fooddelivery.city.City;
import com.fooddelivery.city.CityService;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.User;
import com.fooddelivery.user.UserService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryPartnerAdminService {

    private final DeliveryPartnerRepository partnerRepository;
    private final UserService userService;
    private final CityService cityService;

    public DeliveryPartnerAdminService(DeliveryPartnerRepository partnerRepository, UserService userService,
                                       CityService cityService) {
        this.partnerRepository = partnerRepository;
        this.userService = userService;
        this.cityService = cityService;
    }

    /** User + profile in ONE transaction: either both rows exist afterwards or neither does. */
    @Transactional
    public DeliveryPartnerResponse create(CreateDeliveryPartnerRequest request) {
        City city = cityService.requireActive(request.cityId()); // validate before creating anything
        User user = userService.createUser(request.toNewUser(), Role.DELIVERY_PARTNER);
        DeliveryPartner partner = new DeliveryPartner();
        partner.setUser(user);
        partner.setCity(city);
        partner.setVehicleType(request.vehicleType());
        partner.setStatus(PartnerStatus.OFFLINE); // the partner goes online themselves
        return DeliveryPartnerResponse.from(partnerRepository.save(partner));
    }

    @Transactional
    public DeliveryPartnerResponse update(Long id, UpdateDeliveryPartnerRequest request) {
        DeliveryPartner partner = partnerRepository.findWithUserAndCityById(id)
                .orElseThrow(() -> new NotFoundException("Delivery partner", id));
        if (request.cityId() != null) {
            partner.setCity(cityService.requireActive(request.cityId()));
        }
        if (request.vehicleType() != null) {
            partner.setVehicleType(request.vehicleType());
        }
        return DeliveryPartnerResponse.from(partner);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryPartnerResponse> search(Long cityId, PartnerStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("id"));
        return PageResponse.from(partnerRepository.search(cityId, status, pageable).map(DeliveryPartnerResponse::from));
    }
}
