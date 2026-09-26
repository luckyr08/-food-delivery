package com.fooddelivery.restaurant;

import com.fooddelivery.city.City;
import com.fooddelivery.common.AuditedEntity;
import com.fooddelivery.user.User;
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

@Entity
@Table(name = "restaurants")
@Getter
@Setter
@NoArgsConstructor
public class Restaurant extends AuditedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(length = 100)
    private String cuisine;

    /** Accepting orders right now (owner-controlled). */
    @Column(name = "is_open", nullable = false)
    private boolean open;

    /** Soft delete (admin-controlled). */
    @Column(nullable = false)
    private boolean active = true;

    // Aggregates updated atomically in SQL; average = ratingSum / ratingCount.
    @Column(name = "rating_sum", nullable = false)
    private int ratingSum;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    /**
     * Search document version, bumped only by OutboxWriter (native UPDATE). Never written by entity
     * saves, so a stale entity can't overwrite a newer value.
     */
    @Column(name = "search_version", insertable = false, updatable = false)
    private long searchVersion;

    @Version
    private long version;
}
