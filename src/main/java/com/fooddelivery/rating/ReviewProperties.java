package com.fooddelivery.rating;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.review")
public record ReviewProperties(@Positive int windowDays) {
}
