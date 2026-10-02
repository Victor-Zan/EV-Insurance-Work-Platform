package com.evinsurance.platform.foundation.api;

import com.evinsurance.platform.foundation.web.TraceContext;

public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "success", data, TraceContext.currentTraceId());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.currentTraceId());
    }
}

