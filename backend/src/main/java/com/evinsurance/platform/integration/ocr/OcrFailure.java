package com.evinsurance.platform.integration.ocr;

/** Safe codes only: never retain vendor URLs, bodies, credentials or exception causes. */
public final class OcrFailure extends RuntimeException {
    public OcrFailure(String code){super(code);}
    public String code(){return getMessage();}
    public static boolean retryable(String code){return java.util.Set.of("BAIDU_RATE_LIMIT","BAIDU_TIMEOUT_POSSIBLY_CHARGED","BAIDU_NETWORK_POSSIBLY_CHARGED","BAIDU_SYSTEM","BAIDU_HTTP","BAIDU_LEASE_POSSIBLY_CHARGED","PROVIDER_OR_STORAGE_FAILURE").contains(code);}
}
