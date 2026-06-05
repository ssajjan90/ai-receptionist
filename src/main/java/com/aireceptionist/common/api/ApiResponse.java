package com.aireceptionist.common.api;

import java.time.Instant;
import java.util.Map;

public record ApiResponse<T>(
        boolean success,
        T data,
        String errorCode,
        String message,
        Map<String, Object> details,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(String errorCode, String message, Map<String, Object> details) {
        return new ApiResponse<>(false, null, errorCode, message, details, Instant.now());
    }
}
