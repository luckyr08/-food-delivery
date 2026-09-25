package com.fooddelivery.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination contract. Spring Data's Page/PageImpl JSON is not a supported API shape,
 * so controllers return this instead.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
