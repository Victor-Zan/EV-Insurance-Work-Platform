package com.evinsurance.platform.foundation.web;

import org.slf4j.MDC;

public final class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}

