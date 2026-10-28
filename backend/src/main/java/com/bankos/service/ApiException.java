package com.bankos.service;

import org.springframework.http.HttpStatus;

import java.util.List;

/** An error with an HTTP status and a stable error code, rendered by ApiExceptionHandler. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ApiException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static ApiException notFound(String what, String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found", null);
    }

    public static ApiException badRequest(String message, List<String> details) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, details);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message, null);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<String> getDetails() {
        return details;
    }
}
