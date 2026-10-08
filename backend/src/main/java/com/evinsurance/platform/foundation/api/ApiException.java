package com.evinsurance.platform.foundation.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException missing(String type) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", type + " not found");
    }
    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
    public static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied");
    }
    public static ApiException conflict(String code,String message) {
        return new ApiException(HttpStatus.CONFLICT,code,message);
    }
}
