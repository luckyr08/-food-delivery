package com.fooddelivery.security;

import com.fooddelivery.common.error.ErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/** 401 raised for a present-but-unusable token, carrying the reason (expired vs invalid). */
@Getter
public class JwtAuthenticationException extends AuthenticationException {

    private final ErrorCode code;

    public JwtAuthenticationException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
