package com.evinsurance.platform.foundation.api;

import java.util.List;

public record PageResponse<T>(int page, int size, long total, List<T> records) {
    public static int offset(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw ApiException.invalid("page must be 1..100000 and size 1..100");
        }
        return (page - 1) * size;
    }
}
