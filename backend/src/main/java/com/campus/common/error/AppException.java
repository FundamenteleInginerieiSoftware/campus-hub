package com.campus.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base runtime exception for all domain and application errors mapped to RFC 9457
 * {@code ProblemDetail} responses by {@link GlobalExceptionHandler}.
 */
@Getter
public abstract class AppException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected AppException(HttpStatus status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
