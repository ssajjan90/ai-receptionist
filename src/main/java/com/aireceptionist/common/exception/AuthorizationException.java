package com.aireceptionist.common.exception;

public class AuthorizationException extends AiReceptionistException {

    private final String errorCode;

    public AuthorizationException(String message) {
        this("FORBIDDEN", message);
    }

    public AuthorizationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
