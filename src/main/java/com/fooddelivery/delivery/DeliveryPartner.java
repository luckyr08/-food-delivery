package com.fooddelivery.delivery;

import com.fooddelivery.city.City;
import com.fooddelivery.common.AuditedEntity;
import com.fooddelivery.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Partner-specific profile; login details live in {@link User}. */
@Entity
@Table(name = "delivery_partners")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryPartner extends AuditedEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartnerStatus status = PartnerStatus.OFFLINE;

    @Column(name = "rating_sum", nullable = false)
    private int ratingSum;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Version
    private long version;
}
