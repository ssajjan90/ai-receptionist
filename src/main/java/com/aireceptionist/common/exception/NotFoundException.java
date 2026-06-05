package com.aireceptionist.common.exception;

public class NotFoundException extends AiReceptionistException {

    private final String errorCode;

    public NotFoundException(String message) {
        this("NOT_FOUND", message);
    }

    public NotFoundException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
