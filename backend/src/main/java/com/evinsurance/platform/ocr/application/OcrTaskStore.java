package com.evinsurance.platform.ocr.application;
import com.evinsurance.platform.ocr.infrastructure.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OcrTaskStore {
    private final OcrMapper jobs;private final AuditService audit;private final org.springframework.jdbc.core.JdbcTemplate db;private final OcrSources sources;private final com.evinsurance.platform.integration.ocr.OcrProvider provider;
    public OcrTaskStore(OcrMapper jobs,AuditService audit,org.springframework.jdbc.core.JdbcTemplate db,OcrSources sources,com.evinsurance.platform.integration.ocr.OcrProvider provider){this.jobs=jobs;this.audit=audit;this.db=db;this.sources=sources;this.provider=provider;}
    @Transactional public OcrRow claim(int maxAttempts,int seconds){
        var expired=db.queryForList("WITH expired AS (SELECT id FROM ocr_job WHERE provider<>'MOCK_V1' AND state='RUNNING' AND lease_until<now() ORDER BY lease_until,id FOR UPDATE SKIP LOCKED LIMIT 50) UPDATE ocr_job j SET state='FAILED',error_code='BAIDU_LEASE_POSSIBLY_CHARGED',lease_token=NULL,lease_until=NULL,updated_at=now() FROM expired e WHERE j.id=e.id RETURNING j.id");
        for(var r:expired)audit.record(null,Action.OCR_FAILED,"OCR_JOB",(UUID)r.get("id"),"Lease expired; no automatic provider retry; possibly charged");
        jobs.expire(maxAttempts);return jobs.claim(UUID.randomUUID(),seconds,maxAttempts,provider.id());
    }
    @Transactional public void finish(OcrRow r,String candidate,String error){finishResult(r,candidate,error,null,null,null,0);}
    @Transactional public boolean finishResult(OcrRow r,String candidate,String error,String rawKey,String hash,String logId,int size){
        if(!sources.eligible(r,true)){
            // Preserve legacy Mock in-flight read-only behaviour; real tasks end safely without publishing results.
            if(r.provider().equals("MOCK_V1")&&!sources.shopQuote(r))return false;
            candidate=null;error="SOURCE_ACCESS_CHANGED";rawKey=null;
        }
        if(jobs.finish(r.id(),r.leaseToken(),candidate,error)!=1)return false;
        if(error==null){jobs.appendReview(r.id(),0,candidate,r.createdBy(),"SYSTEM-"+r.provider()+"-CANDIDATE");if(rawKey!=null)db.update("INSERT INTO ocr_raw_result(job_id,attempt,object_key,sha256,byte_size,log_id) VALUES(?,?,?,?,?,?)",r.id(),r.attempts(),rawKey,hash,size,logId);}
        audit.record(null,error==null?Action.OCR_SUCCEEDED:Action.OCR_FAILED,"OCR_JOB",r.id(),"Provider="+r.provider()+"; attempt="+r.attempts()+"; result="+(error==null?"CANDIDATE_ONLY":error));return error==null;
    }
}
