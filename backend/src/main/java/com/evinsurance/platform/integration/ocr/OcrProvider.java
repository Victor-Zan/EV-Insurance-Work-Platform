package com.evinsurance.platform.integration.ocr;
import java.util.Map;
public interface OcrProvider {
    Map<String,Object> extract(String category,byte[] content,boolean fail);
}
