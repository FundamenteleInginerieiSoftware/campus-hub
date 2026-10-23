package com.campus.common.error;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

@Getter
@Setter
public abstract class AppException extends RuntimeException {
    private String code;
    private HttpStatus status;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
