package com.fooddelivery.security;

import com.fooddelivery.common.error.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads "Authorization: Bearer <jwt>" and, if valid, authenticates the request.
 * It never rejects a request itself: an invalid token just leaves the request anonymous, so public
 * endpoints still work, and protected ones get a 401 from the entry point with the stored reason.
 * Deliberately NOT a @Component: Spring Boot would also register it as a plain servlet filter,
 * running it twice. It is created in SecurityConfig and added only to the security chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute read by the entry point to explain why the request is unauthenticated. */
    public static final String AUTH_ERROR_ATTRIBUTE = "auth.error";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(header.substring(BEARER_PREFIX.length()), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            AuthUser user = jwtService.parse(token);
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    user, null, user.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (ExpiredJwtException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE,
                    new JwtAuthenticationException(ErrorCode.TOKEN_EXPIRED, "Access token has expired"));
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE,
                    new JwtAuthenticationException(ErrorCode.INVALID_TOKEN, "Access token is invalid"));
        }
    }
}
