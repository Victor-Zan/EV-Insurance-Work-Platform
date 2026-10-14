package com.evinsurance.platform.integration.ocr;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class OcrConfiguration {
    @Bean @Primary
    OcrProvider selectedOcrProvider(MockOcrProvider mock,Environment env,ObjectMapper json){
        return switch(env.getProperty("OCR_PROVIDER","MOCK_V1")){
            case "MOCK_V1" -> mock;
            case "BAIDU_HANDWRITING_V1" -> new BaiduOcrProvider(env.getProperty("BAIDU_OCR_API_KEY"),env.getProperty("BAIDU_OCR_SECRET_KEY"),json,env.getProperty("BAIDU_OCR_TOKEN_RETRY_ENABLED",Boolean.class,true),env.getProperty("BAIDU_OCR_MAX_CALLS_PER_PROCESS",Integer.class,0));
            default -> throw new IllegalStateException("Unsupported OCR_PROVIDER");
        };
    }
}
