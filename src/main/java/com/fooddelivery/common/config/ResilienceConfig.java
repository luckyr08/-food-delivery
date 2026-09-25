package com.fooddelivery.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/** Activates Spring Framework 7's built-in @Retryable / @ConcurrencyLimit. */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {
}
