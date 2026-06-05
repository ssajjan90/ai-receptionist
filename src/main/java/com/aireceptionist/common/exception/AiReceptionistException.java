package com.aireceptionist.common.exception;

public class AiReceptionistException extends RuntimeException {

    public AiReceptionistException(String message) {
        super(message);
    }

    public AiReceptionistException(String message, Throwable cause) {
        super(message, cause);
    }
}
