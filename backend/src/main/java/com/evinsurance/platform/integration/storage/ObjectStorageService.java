package com.evinsurance.platform.integration.storage;

import java.io.InputStream;

public interface ObjectStorageService {
    void put(String key, InputStream source, long size, String contentType);
    InputStream read(String key);
    /** Only for compensating an uncommitted upload, never a business delete. */
    void compensate(String key);
}
