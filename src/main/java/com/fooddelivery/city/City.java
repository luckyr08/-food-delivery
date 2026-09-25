package com.fooddelivery.city;

import com.fooddelivery.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cities")
@Getter
@Setter
@NoArgsConstructor
public class City extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 100)
    private String state;

    @Column(nullable = false)
    private boolean active = true;
}
