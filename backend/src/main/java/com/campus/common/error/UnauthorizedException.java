package com.campus.common.error;

import org.springframework.http.HttpStatus;

/**
 * Thrown when authentication fails (e.g. {@code INVALID_CREDENTIALS} during login).
 * Mapped to HTTP 401 by {@link GlobalExceptionHandler}.
 */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
