package com.evinsurance.platform.ocr.application;
import com.evinsurance.platform.ocr.infrastructure.*;
import com.evinsurance.platform.document.application.*;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import tools.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OcrService {
    public record View(UUID id,UUID fileId,String provider,String state,int attempts,String errorCode,JsonNode candidate,int reviewVersion,boolean confirmed) {}
    private final OcrMapper jobs; private final FileService files; private final FileAccess access; private final AuditService audit; private final ObjectMapper json; private final Environment env; private final int maxAttempts;
    public OcrService(OcrMapper jobs,FileService files,FileAccess access,AuditService audit,ObjectMapper json,Environment env,@Value("${OCR_MAX_ATTEMPTS:3}") int maxAttempts){this.jobs=jobs;this.files=files;this.access=access;this.audit=audit;this.json=json;this.env=env;this.maxAttempts=maxAttempts;}
    private View view(OcrRow r){return new View(r.id(),r.fileId(),r.provider(),r.state(),r.attempts(),r.errorCode(),r.candidateJson()==null?null:json.readTree(r.candidateJson()),r.reviewVersion(),jobs.confirmed(r.id(),r.reviewVersion())>0);}
    private OcrRow accessible(UUID id,boolean lock){var r=lock?jobs.lock(id):jobs.find(id);if(r==null)throw ApiException.missing("OCR job");if(!access.staff(CurrentUser.require()))throw ApiException.denied();files.accessible(r.fileId());return r;}
    private void mutable(OcrRow r){access.customerService(CurrentUser.require());var f=files.accessible(r.fileId());access.mutable(access.order(f.workOrderId(),true));if(!"ACTIVE".equals(f.state()))throw ApiException.conflict("STALE_SOURCE","OCR source is no longer active");}
    @Transactional public View create(UUID fileId,boolean fail){access.customerService(CurrentUser.require());var f=files.accessible(fileId);access.mutable(access.order(f.workOrderId(),true));if(!"ACTIVE".equals(f.state())||!FileCategory.valueOf(f.category()).ocrSupported())throw ApiException.invalid("OCR only supports active document categories");
        if(fail&&!env.matchesProfiles("dev"))throw ApiException.invalid("Failure simulation is only available in dev");
        var prior=jobs.byFile(fileId);if(prior!=null)return view(prior);UUID id=UUID.randomUUID();jobs.insert(id,fileId,CurrentUser.require().id(),fail);audit.record(CurrentUser.require(),Action.OCR_CREATE,"OCR_JOB",id,"Mock task created; source="+fileId);return view(jobs.find(id));}
    public View get(UUID id){return view(accessible(id,false));}
    @Transactional public View retry(UUID id){var r=accessible(id,true);mutable(r);if(!"FAILED".equals(r.state())||r.attempts()>=maxAttempts)throw ApiException.conflict("OCR_RETRY_LIMIT","Only failed tasks below the retry limit may be retried");jobs.retry(id);audit.record(CurrentUser.require(),Action.OCR_RETRY,"OCR_JOB",id,"Mock task retry requested");return view(jobs.find(id));}
    @Transactional public View review(UUID id,int expected,JsonNode candidate,String key){var r=accessible(id,true);mutable(r);FileService.checkKey(key);
        if(candidate==null||!candidate.isObject())throw ApiException.invalid("Candidate must be an object");String text=json.writeValueAsString(candidate);if(text.length()>50000)throw ApiException.invalid("Candidate exceeds 50000 characters");
        var prior=jobs.reviewCommand(id,key);if(prior!=null){if(!text.equals(prior.get("candidate_json")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Review key already used");return view(jobs.find(id));}
        if(jobs.review(id,expected,text)!=1)throw ApiException.conflict("OCR_VERSION_CONFLICT","Candidate changed concurrently or is not ready");jobs.appendReview(id,expected+1,text,CurrentUser.require().id(),key);audit.record(CurrentUser.require(),Action.OCR_REVIEW,"OCR_JOB",id,"Candidate revision="+(expected+1)+"; not a formal business result");return view(jobs.find(id));}
    @Transactional public View confirm(UUID id,int expected){var r=accessible(id,true);mutable(r);if(!"SUCCEEDED".equals(r.state())||r.reviewVersion()!=expected)throw ApiException.conflict("OCR_VERSION_CONFLICT","Confirm the current successful candidate revision");
        if(jobs.confirm(id,expected,CurrentUser.require().id())>0)audit.record(CurrentUser.require(),Action.OCR_CONFIRM,"OCR_JOB",id,"Human confirmed revision="+expected+"; snapshot only; no quotation written");return view(jobs.find(id));}
    public PageResponse<Map<String,Object>> history(UUID id,int page,int size){accessible(id,false);return new PageResponse<>(page,size,jobs.historyCount(id),jobs.history(id,PageResponse.offset(page,size),size));}
}
