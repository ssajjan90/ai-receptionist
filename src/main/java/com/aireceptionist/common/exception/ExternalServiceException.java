package com.aireceptionist.common.exception;

public class ExternalServiceException extends AiReceptionistException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
