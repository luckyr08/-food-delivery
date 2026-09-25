package com.fooddelivery.security;

import com.fooddelivery.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Used only at login by DaoAuthenticationProvider to load the user and check the password. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email)
                .map(AuthUser::fromEntity)
                // Converted to BadCredentialsException by the provider, so callers can't tell
                // "unknown email" from "wrong password".
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
