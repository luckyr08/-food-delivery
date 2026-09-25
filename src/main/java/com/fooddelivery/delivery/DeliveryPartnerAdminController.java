package com.fooddelivery.delivery;

import com.fooddelivery.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery-partners")
@PreAuthorize("hasRole('ADMIN')")
public class DeliveryPartnerAdminController {

    private final DeliveryPartnerAdminService service;

    public DeliveryPartnerAdminController(DeliveryPartnerAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryPartnerResponse create(@Valid @RequestBody CreateDeliveryPartnerRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public DeliveryPartnerResponse update(@PathVariable Long id, @RequestBody UpdateDeliveryPartnerRequest request) {
        return service.update(id, request);
    }

    @GetMapping
    public PageResponse<DeliveryPartnerResponse> search(@RequestParam(required = false) Long cityId,
                                                        @RequestParam(required = false) PartnerStatus status,
                                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(cityId, status, page, size);
    }
}
