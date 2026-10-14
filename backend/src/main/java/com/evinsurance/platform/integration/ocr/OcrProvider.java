package com.evinsurance.platform.integration.ocr;
import java.util.Map;
public interface OcrProvider {
    Map<String,Object> extract(String category,byte[] content,boolean fail);
    default String id(){return "MOCK_V1";}
    default String api(){return "local-mock";}
    default String version(){return "1";}
    default Map<String,Long> requestCounts(){return Map.of("authentication",0L,"ocr",0L);}
    record Result(Map<String,Object> candidate,byte[] raw,String logId) {}
    default Result recognize(String category,byte[] content,boolean fail){return new Result(extract(category,content,fail),null,null);}
}
