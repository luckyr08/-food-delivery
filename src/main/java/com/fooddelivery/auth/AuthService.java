package com.fooddelivery.auth;

import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.security.AuthUser;
import com.fooddelivery.security.JwtService;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.User;
import com.fooddelivery.user.UserRepository;
import com.fooddelivery.user.UserResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.fooddelivery.user.UserService.normalizeEmail;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /** Self-registration always creates a CUSTOMER; other roles are created by an admin. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        // Fast, friendly check. Two simultaneous registrations can both pass it; the UNIQUE
        // constraint on users.email then rejects the second (-> 409 via GlobalExceptionHandler).
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        User user = new User();
        user.setName(request.name());
        user.setEmail(email);
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.CUSTOMER);
        return UserResponse.from(userRepository.save(user));
    }

    /** Throws BadCredentialsException / DisabledException (-> 401) on failure. */
    public LoginResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(normalizeEmail(request.email()),
                        request.password()));
        AuthUser user = (AuthUser) auth.getPrincipal();
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(), user.id(), user.role());
    }
}
