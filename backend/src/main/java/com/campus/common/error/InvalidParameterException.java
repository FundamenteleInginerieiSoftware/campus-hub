package com.campus.common.error;

public class InvalidParameterException extends BadRequestException {

    public InvalidParameterException(String message) {
        super("INVALID_PARAMETER", message);
    }
}
