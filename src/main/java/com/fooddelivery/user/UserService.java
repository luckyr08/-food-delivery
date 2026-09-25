package com.fooddelivery.user;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Single place where accounts are created (self-registration and admin onboarding), so email
     * normalization, the duplicate check and password hashing can't drift apart.
     * Joins the caller's transaction.
     */
    @Transactional
    public User createUser(NewUserRequest request, Role role) {
        String email = normalizeEmail(request.email());
        // Fast, friendly check. Two simultaneous requests can both pass it; the UNIQUE
        // constraint on users.email then rejects the second (-> 409 via GlobalExceptionHandler).
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        User user = new User();
        user.setName(request.name());
        user.setEmail(email);
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.from(require(userId));
    }

    /** Admin blocks/unblocks an account. Blocked users can't log in; issued tokens live until expiry. */
    @Transactional
    public UserResponse setActive(Long actingAdminId, Long userId, boolean active) {
        if (actingAdminId.equals(userId) && !active) {
            throw new BadRequestException(ErrorCode.CANNOT_DEACTIVATE_SELF, "Admins cannot deactivate themselves");
        }
        User user = require(userId);
        user.setActive(active);
        return UserResponse.from(user);
    }

    User require(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    }

    /** Emails are case-insensitive identifiers: store and look up one canonical form. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
