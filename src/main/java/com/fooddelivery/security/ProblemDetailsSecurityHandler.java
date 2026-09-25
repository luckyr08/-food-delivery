package com.fooddelivery.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 401/403 raised inside the security filter chain never reach @RestControllerAdvice, because no
 * controller has run yet. Instead of formatting JSON a second time here, we hand the exception to
 * Spring MVC's HandlerExceptionResolver, which invokes GlobalExceptionHandler: one error format,
 * one place that produces it.
 */
@Component
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public ProblemDetailsSecurityHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    /** Not authenticated (no token, or the filter rejected it). */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        // Prefer the specific reason recorded by the JWT filter (expired / invalid).
        Object reason = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        AuthenticationException ex = reason instanceof AuthenticationException specific ? specific : authException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); // RFC 6750
        resolver.resolveException(request, response, null, ex);
    }

    /** Authenticated but the URL rule denied the role. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) {
        resolver.resolveException(request, response, null, accessDeniedException);
    }
}
