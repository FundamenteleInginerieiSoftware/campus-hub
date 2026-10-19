package com.campus.incident_hub.common.exception;

import lombok.Getter;

@Getter
public class BusinessRuleException extends RuntimeException {

    private final String errorCode;

    public BusinessRuleException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
