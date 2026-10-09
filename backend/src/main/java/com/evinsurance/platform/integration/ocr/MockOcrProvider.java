package com.evinsurance.platform.integration.ocr;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class MockOcrProvider implements OcrProvider {
    public Map<String,Object> extract(String category,byte[] content,boolean fail) {
        if(fail) throw new IllegalStateException("MOCK_FAILURE");
        var result=new LinkedHashMap<String,Object>();
        result.put("mock",true); result.put("candidateOnly",true); result.put("confidence",null);
        result.put("sourceCategory",category);
        result.put("fields",Map.of("claimNo","MOCK-CLAIM-001","insuranceCompany","模拟保险公司",
            "ownerName","模拟车主","ownerPhone","13800000000","vehicleIdentifier","MOCK-VIN-001",
            "accidentTime","2026-10-01T08:00:00+08:00","documentNo","MOCK-DOC-001"));
        if(!category.equals("NOTICE")) {
            result.put("items",List.of(Map.of("description","模拟维修项目","quantity","2","unitPrice","10.00","lineAmount","20.00")));
            result.put("amountCandidate","20.00");
        }
        result.put("otherReadableInformation",List.of("合成样本；固定Mock候选，不代表读取到的真实内容"));
        return result;
    }
}
