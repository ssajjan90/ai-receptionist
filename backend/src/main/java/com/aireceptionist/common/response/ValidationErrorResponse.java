package com.aireceptionist.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationErrorResponse {

    private final boolean success = false;
    private final String message = "Validation failed";
    private final Map<String, List<String>> errors;
    private final Instant timestamp;

    public ValidationErrorResponse(Map<String, List<String>> errors) {
        this.errors = errors;
        this.timestamp = Instant.now();
    }
}
