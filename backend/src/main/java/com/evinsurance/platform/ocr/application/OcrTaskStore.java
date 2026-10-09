package com.evinsurance.platform.ocr.application;
import com.evinsurance.platform.ocr.infrastructure.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OcrTaskStore {
    private final OcrMapper jobs;private final AuditService audit;private final org.springframework.jdbc.core.JdbcTemplate db;
    public OcrTaskStore(OcrMapper jobs,AuditService audit,org.springframework.jdbc.core.JdbcTemplate db){this.jobs=jobs;this.audit=audit;this.db=db;}
    @Transactional public OcrRow claim(int maxAttempts,int seconds){jobs.expire(maxAttempts);return jobs.claim(UUID.randomUUID(),seconds,maxAttempts);}
    @Transactional public void finish(OcrRow r,String candidate,String error){var statuses=db.queryForList("SELECT w.status FROM work_order w JOIN case_file f ON f.work_order_id=w.id WHERE f.id=? FOR UPDATE OF w",String.class,r.fileId());if(statuses.isEmpty()||java.util.Set.of("COMPLETED","CANCELLED").contains(statuses.getFirst()))return; if(jobs.finish(r.id(),r.leaseToken(),candidate,error)!=1)return;
        if(error==null)jobs.appendReview(r.id(),0,candidate,r.createdBy(),"SYSTEM-MOCK-CANDIDATE");
        audit.record(null,error==null?Action.OCR_SUCCEEDED:Action.OCR_FAILED,"OCR_JOB",r.id(),"Worker attempt="+r.attempts()+"; result="+(error==null?"CANDIDATE_ONLY":error));}
}
