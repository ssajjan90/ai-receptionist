package com.aireceptionist.common.exception;

/** Story 5.4 (AC3) — distinct from the generic per-tenant {@code RateLimitFilter}, which writes
 * a raw (non-{@code ApiResponse}) 429 body; this goes through the normal exception-handling path
 * so admin-notification rate limiting stays consistent with the rest of the API's error envelope. */
public class RateLimitExceededException extends AiReceptionistException {

    private final String errorCode;

    public RateLimitExceededException(String message) {
        this("RATE_LIMIT_EXCEEDED", message);
    }

    public RateLimitExceededException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
