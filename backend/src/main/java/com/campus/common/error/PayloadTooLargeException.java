package com.campus.common.error;

import org.springframework.http.HttpStatus;

public class PayloadTooLargeException extends AppException {

    public PayloadTooLargeException(String code, String message) {
        super(HttpStatus.CONTENT_TOO_LARGE, code, message);
    }
}
