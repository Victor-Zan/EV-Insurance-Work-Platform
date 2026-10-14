package com.evinsurance.platform.ocr.application;
import com.evinsurance.platform.document.infrastructure.FileMapper;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import com.evinsurance.platform.integration.ocr.OcrProvider;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @EnableScheduling @ConditionalOnProperty(name="OCR_WORKER_ENABLED",havingValue="true",matchIfMissing=true)
public class OcrWorker {
    private final OcrTaskStore tasks;private final OcrSources sources;private final ObjectStorageService storage;private final OcrProvider provider;private final ObjectMapper json;private final int max,lease;
    public OcrWorker(OcrTaskStore tasks,OcrSources sources,ObjectStorageService storage,OcrProvider provider,ObjectMapper json,@Value("${OCR_MAX_ATTEMPTS:3}") int max,@Value("${OCR_LEASE_SECONDS:180}") int lease){this.tasks=tasks;this.sources=sources;this.storage=storage;this.provider=provider;this.json=json;this.max=max;this.lease=lease;if(max<1||max>10||lease<10||lease>600||(!provider.id().equals("MOCK_V1")&&lease<120))throw new IllegalArgumentException("Invalid OCR worker limits");}
    @Scheduled(fixedDelayString="${OCR_POLL_MS:1000}") public void tick(){var job=tasks.claim(max,lease);if(job==null)return;
        String rawKey=null;
        try {if(!sources.eligible(job,false)){tasks.finish(job,null,"SOURCE_ACCESS_CHANGED");return;}var f=sources.source(job,false);
            byte[] data;try(var source=storage.read(f.objectKey())){data=source.readNBytes(10485761);}
            if(data.length!=f.size()||!FileService.hash(data).equals(f.sha256())){tasks.finish(job,null,"SOURCE_HASH_MISMATCH");return;}
            if(!sources.eligible(job,false)){tasks.finish(job,null,"SOURCE_ACCESS_CHANGED");return;}
            var result=provider.recognize(f.category(),data,job.simulateFailure()&&job.attempts()==1);
            tools.jackson.databind.node.ObjectNode candidate=json.valueToTree(result.candidate());
            if(provider.id().equals("MOCK_V1"))candidate.putNull("confidence");
            String text=json.writeValueAsString(candidate);if(text.length()>50000)throw new com.evinsurance.platform.integration.ocr.OcrFailure("BAIDU_RESULT_TOO_LARGE");
            if(result.raw()!=null){rawKey="ocr/raw/"+job.id()+"/"+job.attempts()+"/"+java.util.UUID.randomUUID();storage.put(rawKey,new java.io.ByteArrayInputStream(result.raw()),result.raw().length,"application/json");}
            if(!tasks.finishResult(job,text,null,rawKey,result.raw()==null?null:FileService.hash(result.raw()),result.logId(),result.raw()==null?0:result.raw().length)&&rawKey!=null)storage.compensate(rawKey);
        }catch(com.evinsurance.platform.integration.ocr.OcrFailure error){if(rawKey!=null)storage.compensate(rawKey);tasks.finish(job,null,error.code());}
        catch(Exception error){if(rawKey!=null)storage.compensate(rawKey);tasks.finish(job,null,"PROVIDER_OR_STORAGE_FAILURE");}
    }
}
