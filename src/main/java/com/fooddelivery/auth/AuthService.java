package com.fooddelivery.auth;

import com.fooddelivery.security.AuthUser;
import com.fooddelivery.security.JwtService;
import com.fooddelivery.user.NewUserRequest;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.UserResponse;
import com.fooddelivery.user.UserService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.fooddelivery.user.UserService.normalizeEmail;

@Service
public class AuthService {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserService userService, AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /** Self-registration always creates a CUSTOMER; other roles are created by an admin. */
    @Transactional
    public UserResponse register(NewUserRequest request) {
        return UserResponse.from(userService.createUser(request, Role.CUSTOMER));
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
