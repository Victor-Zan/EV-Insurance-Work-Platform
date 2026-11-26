package com.evinsurance.platform.support;

import java.net.URI;
import java.util.Set;

/** Explicit loopback-only test service; each caller still creates a unique schema/database. */
public final class PostgresTestEnvironment {
    private PostgresTestEnvironment() {}
    public static String url() {
        String value = System.getenv("TEST_DB_URL");
        if (value == null || !value.startsWith("jdbc:postgresql://"))
            throw new IllegalStateException("A dedicated loopback TEST_DB_URL is required");
        URI uri = URI.create(value.substring(5));
        if (!Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getPath() == null || uri.getPath().length() <= 1
                || System.getenv("TEST_DB_USERNAME") == null || System.getenv("TEST_DB_PASSWORD") == null)
            throw new IllegalStateException("Explicit loopback test database credentials are required");
        return value;
    }
    public static URI address() { return URI.create(url().substring(5)); }
    public static String schemaUrl(String schema) {
        String url = url();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }
}
