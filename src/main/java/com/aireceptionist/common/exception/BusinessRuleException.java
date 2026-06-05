package com.aireceptionist.common.exception;

public class BusinessRuleException extends AiReceptionistException {

    private final String errorCode;

    public BusinessRuleException(String message) {
        this("BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessRuleException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
