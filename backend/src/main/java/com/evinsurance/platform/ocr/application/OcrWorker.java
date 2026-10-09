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
    private final OcrTaskStore tasks;private final FileMapper files;private final ObjectStorageService storage;private final OcrProvider provider;private final ObjectMapper json;private final int max,lease;
    public OcrWorker(OcrTaskStore tasks,FileMapper files,ObjectStorageService storage,OcrProvider provider,ObjectMapper json,@Value("${OCR_MAX_ATTEMPTS:3}") int max,@Value("${OCR_LEASE_SECONDS:60}") int lease){this.tasks=tasks;this.files=files;this.storage=storage;this.provider=provider;this.json=json;this.max=max;this.lease=lease;if(max<1||max>10||lease<10||lease>600)throw new IllegalArgumentException("Invalid OCR worker limits");}
    @Scheduled(fixedDelayString="${OCR_POLL_MS:1000}") public void tick(){var job=tasks.claim(max,lease);if(job==null)return;
        try {var f=files.find(job.fileId());if(!"ACTIVE".equals(f.state())){tasks.finish(job,null,"STALE_SOURCE");return;}
            byte[] data;try(var source=storage.read(f.objectKey())){data=source.readNBytes(10485761);}
            if(data.length!=f.byteSize()||!FileService.hash(data).equals(f.sha256())){tasks.finish(job,null,"SOURCE_HASH_MISMATCH");return;}
            tools.jackson.databind.node.ObjectNode candidate=json.valueToTree(provider.extract(f.category(),data,job.simulateFailure()&&job.attempts()==1));
            candidate.putNull("confidence"); // Explicitly unknown in Mock, including with NON_NULL API defaults.
            tasks.finish(job,json.writeValueAsString(candidate),null);
        }catch(Exception error){tasks.finish(job,null,"PROVIDER_OR_STORAGE_FAILURE");}
    }
}
