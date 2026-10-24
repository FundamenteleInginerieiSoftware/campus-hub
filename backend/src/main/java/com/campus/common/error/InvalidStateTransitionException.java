package com.campus.common.error;

import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends AppException {
    public InvalidStateTransitionException(String message) {

        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message);
    }

    public InvalidStateTransitionException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
