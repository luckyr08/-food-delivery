package com.fooddelivery.notification;

import com.fooddelivery.user.Role;

public record Recipient(Long userId, Role role) {
}
