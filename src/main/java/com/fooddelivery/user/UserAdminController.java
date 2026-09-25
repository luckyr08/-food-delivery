package com.fooddelivery.user;

import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')") // defence in depth on top of the /api/admin/** URL rule
public class UserAdminController {

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @PatchMapping("/{id}/status")
    public UserResponse setStatus(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                                  @Valid @RequestBody UserStatusRequest request) {
        return userService.setActive(admin.id(), id, request.active());
    }
}
