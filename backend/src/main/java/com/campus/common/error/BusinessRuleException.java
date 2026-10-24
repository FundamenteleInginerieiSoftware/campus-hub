package com.campus.common.error;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends AppException {
    public BusinessRuleException(String message) {

        super(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
