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
    public record View(UUID id,UUID fileId,String provider,String state,int attempts,String errorCode,JsonNode candidate,int reviewVersion,boolean confirmed,UUID intakeId,String providerApi,String providerVersion,boolean retryable) {}
    private final OcrMapper jobs; private final FileService files; private final FileAccess access; private final AuditService audit; private final ObjectMapper json; private final Environment env; private final int maxAttempts;
    private final OcrSources sources;private final com.evinsurance.platform.integration.ocr.OcrProvider provider;private final org.springframework.jdbc.core.JdbcTemplate db;private final com.evinsurance.platform.workorder.application.WorkOrderService orders;
    public OcrService(OcrMapper jobs,FileService files,FileAccess access,AuditService audit,ObjectMapper json,Environment env,@Value("${OCR_MAX_ATTEMPTS:3}") int maxAttempts,OcrSources sources,com.evinsurance.platform.integration.ocr.OcrProvider provider,org.springframework.jdbc.core.JdbcTemplate db,com.evinsurance.platform.workorder.application.WorkOrderService orders){this.jobs=jobs;this.files=files;this.access=access;this.audit=audit;this.json=json;this.env=env;this.maxAttempts=maxAttempts;this.sources=sources;this.provider=provider;this.db=db;this.orders=orders;}
    private View view(OcrRow r){return new View(r.id(),r.fileId(),r.provider(),r.state(),r.attempts(),r.errorCode(),r.candidateJson()==null?null:visibleCandidate(json.readTree(r.candidateJson())),r.reviewVersion(),jobs.confirmed(r.id(),r.reviewVersion())>0,r.intakeId(),r.providerApi(),r.providerVersion(),r.state().equals("FAILED")&&r.attempts()<maxAttempts&&(r.provider().equals("MOCK_V1")||com.evinsurance.platform.integration.ocr.OcrFailure.retryable(r.errorCode())));}
    OcrRow accessible(UUID id,boolean lock){var r=jobs.find(id);if(r==null)throw ApiException.missing("OCR job");sources.source(r,true);if(lock){if(r.fileId()!=null)access.order(files.accessible(r.fileId()).workOrderId(),true);r=jobs.lock(id);}return r;}
    private void mutable(OcrRow r){sources.mutable(r);}
    @Transactional public View create(UUID fileId,boolean fail){var actor=CurrentUser.require();var f=files.accessible(fileId);access.mutable(access.order(f.workOrderId(),true));if(!"ACTIVE".equals(f.state())||!FileCategory.valueOf(f.category()).ocrSupported())throw ApiException.invalid("OCR only supports active document categories");
        if(!actor.roles().contains(com.evinsurance.platform.identity.domain.Role.CUSTOMER_SERVICE)&&!(actor.roles().contains(com.evinsurance.platform.identity.domain.Role.REPAIR_SHOP)&&"SHOP_QUOTE".equals(f.category())))throw ApiException.denied();
        if(fail&&!env.matchesProfiles("dev"))throw ApiException.invalid("Failure simulation is only available in dev");
        if(fail&&!provider.id().equals("MOCK_V1"))throw ApiException.invalid("Failure simulation requires Mock");
        var prior=jobs.byFile(fileId);if(prior!=null){sources.mutable(prior);return view(prior);}UUID id=UUID.randomUUID();jobs.insert(id,fileId,null,CurrentUser.require().id(),fail,provider.id(),provider.api(),provider.version());sources.mutable(jobs.find(id));audit.record(CurrentUser.require(),Action.OCR_CREATE,"OCR_JOB",id,"Provider="+provider.id()+"; source="+fileId);return view(jobs.find(id));}
    public View get(UUID id){return view(accessible(id,false));}
    @Transactional public View retry(UUID id){var r=accessible(id,true);mutable(r);if(!view(r).retryable())throw ApiException.conflict("OCR_RETRY_LIMIT","Task is not retryable or reached its attempt limit");if(!r.provider().equals(provider.id()))throw ApiException.conflict("OCR_PROVIDER_MISMATCH","Retry requires the original configured provider");jobs.retry(id);audit.record(CurrentUser.require(),Action.OCR_RETRY,"OCR_JOB",id,"Provider="+r.provider()+"; explicit retry; external call may be charged again");return view(jobs.find(id));}
    @Transactional public View intake(String key,org.springframework.web.multipart.MultipartFile file){UUID source=sources.upload(key,file);var prior=db.queryForList("SELECT id FROM ocr_job WHERE intake_id=?",source);if(!prior.isEmpty())return view(jobs.find((UUID)prior.getFirst().get("id")));UUID id=UUID.randomUUID();jobs.insert(id,null,source,CurrentUser.require().id(),false,provider.id(),provider.api(),provider.version());audit.record(CurrentUser.require(),Action.OCR_CREATE,"OCR_JOB",id,"Pre-case task; provider="+provider.id());return view(jobs.find(id));}
    public Map<String,Object> configuration(){if(!access.staff(CurrentUser.require())){if(!CurrentUser.require().roles().contains(com.evinsurance.platform.identity.domain.Role.REPAIR_SHOP))throw ApiException.denied();return Map.of("provider",provider.id(),"api",provider.api(),"version",provider.version(),"simulateFailure",false);}return Map.of("provider",provider.id(),"api",provider.api(),"version",provider.version(),"simulateFailure",provider.id().equals("MOCK_V1")&&env.matchesProfiles("dev"),"processRequestCounts",provider.requestCounts());}
    public OcrSources.Raw raw(UUID id){return sources.raw(accessible(id,false));}
    public JsonNode program(UUID id){return visibleCandidate(retainedProgram(id));}
    public JsonNode retainedProgram(UUID id){accessible(id,false);var rows=db.queryForList("SELECT candidate_json FROM ocr_review WHERE job_id=? AND (revision=0 OR candidate_json::jsonb->>'origin'='PROGRAM_LABELLED_TEXT_V2') ORDER BY revision DESC LIMIT 1",id);return rows.isEmpty()?json.createObjectNode():json.readTree(rows.getFirst().get("candidate_json").toString());}
    private boolean scalar(JsonNode value){return !value.isObject()&&!value.isArray();}
    public JsonNode visibleCandidate(JsonNode candidate){
        if(candidate==null||access.staff(CurrentUser.require()))return candidate;
        var safe=json.createObjectNode();for(String key:List.of("mock","candidateOnly","sourceCategory","origin","originalReviewed","amountCandidate"))if(candidate.has(key))safe.set(key,candidate.get(key));
        var fields=safe.putObject("fields");for(String key:List.of("claimNo","insuranceCompany","vehicleVin","vehiclePlate","vehicleIdentifier","documentNo","amountCandidate"))if(candidate.path("fields").has(key)&&scalar(candidate.path("fields").get(key)))fields.set(key,candidate.path("fields").get(key));
        for(String key:List.of("items","adopted"))if(candidate.path(key).isArray()){var lines=safe.putArray(key);for(var line:candidate.get(key)){var output=lines.addObject();for(String field:List.of("description","quantity","quantityOriginal","unitPrice","lineAmount"))if(line.has(field)&&scalar(line.get(field)))output.set(field,line.get(field));}}
        return safe;
    }
    @Transactional public View reextract(UUID id,int expected,String key){
        var r=accessible(id,true);mutable(r);FileService.checkKey(key);if(!r.provider().equals("BAIDU_HANDWRITING_V1")||!r.state().equals("SUCCEEDED"))throw ApiException.invalid("Only successful Baidu raw results can be reorganized");
        String command="SYSTEM-REEXTRACT-"+FileService.hash(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));if(jobs.reviewCommand(id,command)!=null)return view(jobs.find(id));
        var raw=sources.raw(r);var result=new com.evinsurance.platform.integration.ocr.HandwritingCandidates().extract(json.readTree(raw.text()),sources.source(r,true).category());result.put("rawAttempt",raw.attempt());String text=json.writeValueAsString(result);if(text.length()>50000)throw ApiException.invalid("Program candidate exceeds 50000 characters");
        if(jobs.review(id,expected,text)!=1)throw ApiException.conflict("OCR_VERSION_CONFLICT","Candidate changed concurrently");jobs.appendReview(id,expected+1,text,CurrentUser.require().id(),command);audit.record(CurrentUser.require(),Action.OCR_REVIEW,"OCR_JOB",id,"Program extraction V2 from retained raw attempt="+raw.attempt()+"; revision="+(expected+1)+"; no external OCR call");return view(jobs.find(id));
    }
    public OcrSources.Source source(UUID id){return sources.source(accessible(id,false),true);}
    public java.io.InputStream image(UUID id){return sources.image(accessible(id,false));}
    @Transactional public com.evinsurance.platform.workorder.api.WorkOrderViews.WorkOrder draft(UUID id,int revision,com.evinsurance.platform.workorder.api.WorkOrderRequests.Draft body){
        var r=accessible(id,true);mutable(r);if(r.intakeId()==null)throw ApiException.invalid("Only pre-case intake can create a new draft");
        String hash=FileService.hash(json.writeValueAsBytes(body));var links=db.queryForList("SELECT * FROM ocr_draft_link WHERE job_id=?",id);
        if(!links.isEmpty()){var link=links.getFirst();if(!hash.equals(link.get("request_hash"))||((Number)link.get("revision")).intValue()!=revision)throw ApiException.conflict("IDEMPOTENCY_CONFLICT","This task already created a different draft");return orders.get((UUID)link.get("work_order_id"));}
        if(r.reviewVersion()!=revision||jobs.confirmed(id,revision)==0)throw ApiException.conflict("OCR_REVIEW_REQUIRED","Save and confirm the current prefilled form before creating a draft");
        var saved=json.readTree(r.candidateJson()).path("draft");if(!saved.isObject()||!json.valueToTree(body).equals(json.valueToTree(json.treeToValue(saved,com.evinsurance.platform.workorder.api.WorkOrderRequests.Draft.class))))throw ApiException.conflict("OCR_REVIEW_REQUIRED","Form differs from the confirmed snapshot");
        var order=orders.createDraft(body);db.update("INSERT INTO ocr_draft_link(job_id,revision,work_order_id,actor_id,request_hash) VALUES(?,?,?,?,?)",id,revision,order.id(),CurrentUser.require().id(),hash);audit.record(CurrentUser.require(),Action.OCR_CONFIRM,"OCR_JOB",id,"Confirmed form copied through draft service; draft="+order.id()+"; revision="+revision+"; formal submit still required");return order;
    }
    @Transactional public View review(UUID id,int expected,JsonNode candidate,String key){var r=accessible(id,true);mutable(r);FileService.checkKey(key);
        if(candidate==null||!candidate.isObject())throw ApiException.invalid("Candidate must be an object");var human=(tools.jackson.databind.node.ObjectNode)candidate.deepCopy();human.put("origin","HUMAN_REVISION");String text=json.writeValueAsString(human);if(text.length()>50000)throw ApiException.invalid("Candidate exceeds 50000 characters");
        var prior=jobs.reviewCommand(id,key);if(prior!=null){if(!text.equals(prior.get("candidate_json")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Review key already used");return view(jobs.find(id));}
        if(jobs.review(id,expected,text)!=1)throw ApiException.conflict("OCR_VERSION_CONFLICT","Candidate changed concurrently or is not ready");jobs.appendReview(id,expected+1,text,CurrentUser.require().id(),key);audit.record(CurrentUser.require(),Action.OCR_REVIEW,"OCR_JOB",id,"Candidate revision="+(expected+1)+"; not a formal business result");return view(jobs.find(id));}
    @Transactional public View confirm(UUID id,int expected){var r=accessible(id,true);mutable(r);boolean manual="FAILED".equals(r.state())&&expected>0&&r.candidateJson()!=null&&json.readTree(r.candidateJson()).path("originalReviewed").asBoolean(false);if((!"SUCCEEDED".equals(r.state())&&!manual)||r.reviewVersion()!=expected)throw ApiException.conflict("OCR_VERSION_CONFLICT","Confirm the current reviewed candidate revision");
        if(jobs.confirm(id,expected,CurrentUser.require().id())>0)audit.record(CurrentUser.require(),Action.OCR_CONFIRM,"OCR_JOB",id,"Human confirmed revision="+expected+"; snapshot only; no quotation written");return view(jobs.find(id));}
    public PageResponse<Map<String,Object>> history(UUID id,int page,int size){accessible(id,false);return new PageResponse<>(page,size,jobs.historyCount(id),jobs.history(id,PageResponse.offset(page,size),size));}
}
