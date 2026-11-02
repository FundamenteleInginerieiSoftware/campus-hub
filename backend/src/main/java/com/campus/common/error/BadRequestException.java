package com.campus.common.error;

import org.springframework.http.HttpStatus;

public class BadRequestException extends AppException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
