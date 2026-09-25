package com.fooddelivery.security;

import com.fooddelivery.user.Role;
import com.fooddelivery.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated caller. Built from the DB at login (with email + password hash, for the password
 * check) and from the JWT on every other request (id + role only: no email, no hash, no DB lookup).
 * Controllers receive it via {@code @AuthenticationPrincipal AuthUser me}.
 */
public record AuthUser(Long id, String email, Role role, String passwordHash, boolean enabled)
        implements UserDetails {

    public static AuthUser fromEntity(User user) {
        return new AuthUser(user.getId(), user.getEmail(), user.getRole(), user.getPasswordHash(), user.isActive());
    }

    public static AuthUser fromToken(Long id, Role role) {
        return new AuthUser(id, null, role, null, true);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // "ROLE_" prefix is what hasRole('ADMIN') / @PreAuthorize("hasRole(...)") look for.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        // Token-based principals carry no email; the id identifies them.
        return email != null ? email : String.valueOf(id);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "AuthUser[id=" + id + ", role=" + role + "]"; // never log the hash
    }
}
