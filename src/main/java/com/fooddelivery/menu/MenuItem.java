package com.fooddelivery.menu;

import com.fooddelivery.common.AuditedEntity;
import com.fooddelivery.restaurant.Restaurant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "menu_items")
@Getter
@Setter
@NoArgsConstructor
public class MenuItem extends AuditedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "is_veg", nullable = false)
    private boolean veg = true;

    /** Owner toggle ("sold out today") independent of stock. */
    @Column(nullable = false)
    private boolean available = true;

    /** Remaining units; null = unlimited. Decremented only via conditional UPDATE. */
    private Integer stock;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;
}
