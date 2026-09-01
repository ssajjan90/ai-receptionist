package com.aireceptionist.common.api;

import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.common.exception.RateLimitExceededException;
import com.aireceptionist.common.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NotFoundException ex) {
        return ApiResponse.error(ex.getErrorCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(ValidationException ex) {
        return ApiResponse.error(ex.getErrorCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Void> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ApiResponse.error(ex.getErrorCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(AuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAuthorization(AuthorizationException ex) {
        return ApiResponse.error(ex.getErrorCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException ex) {
        return ApiResponse.error("FORBIDDEN", "You do not have permission to perform this action", null);
    }

    @ExceptionHandler(ExternalServiceException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleExternalService(ExternalServiceException ex) {
        return ApiResponse.error("EXTERNAL_SERVICE_ERROR", ex.getMessage(), null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> handleBusinessRule(BusinessRuleException ex) {
        HttpStatus status = ("EMAIL_ALREADY_REGISTERED".equals(ex.getErrorCode())
                || "PHONE_ALREADY_REGISTERED".equals(ex.getErrorCode()))
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return org.springframework.http.ResponseEntity
                .status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldErrors = (Map<String, Object>) (Map<?, ?>) ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(
                        FieldError::getField,
                        Collectors.mapping(FieldError::getDefaultMessage, Collectors.toList())));
        return ApiResponse.error("VALIDATION_ERROR", "Request validation failed", fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException ex) {
        log.debug("Constraint violation: {}", ex.getMessage());
        return ApiResponse.error("VALIDATION_ERROR", "Request constraint violation", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException ex) {
        return ApiResponse.error("MALFORMED_REQUEST", "Malformed JSON request body", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ApiResponse.error("METHOD_NOT_ALLOWED", "HTTP method not supported", null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    public ApiResponse<Void> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ApiResponse.error("NOT_ACCEPTABLE", "Requested media type is not supported", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ApiResponse.error("FILE_TOO_LARGE", "Uploaded file exceeds the 5MB limit", null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ApiResponse.error("CONCURRENT_MODIFICATION", "This record was modified by another request. Please retry.", null);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleCircuitOpen(CallNotPermittedException ex) {
        log.warn("Circuit breaker open: {}", ex.getMessage());
        return ApiResponse.error("SERVICE_UNAVAILABLE", "Service temporarily unavailable, please retry shortly", null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred", null);
    }
}
