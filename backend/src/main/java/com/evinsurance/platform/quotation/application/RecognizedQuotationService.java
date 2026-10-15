package com.evinsurance.platform.quotation.application;
import com.evinsurance.platform.quotation.api.QuotationRequests;
import com.evinsurance.platform.ocr.application.OcrService;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.CurrentUser;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
@Service
public class RecognizedQuotationService {
    private final QuotationService quotes;private final OcrService ocr;private final FileService files;private final JdbcTemplate db;private final ObjectMapper json;
    public RecognizedQuotationService(QuotationService quotes,OcrService ocr,FileService files,JdbcTemplate db,ObjectMapper json){this.quotes=quotes;this.ocr=ocr;this.files=files;this.db=db;this.json=json;}
    public record Quote(UUID jobId,int revision,QuotationRequests.Raw quote){} public record Assessment(UUID jobId,int revision,QuotationRequests.Assessment assessment){}
    private UUID require(UUID caseId,UUID jobId,int revision,String category,JsonNode adopted){
        db.queryForList("SELECT id FROM work_order WHERE id=? FOR UPDATE",caseId);db.queryForList("SELECT id FROM ocr_job WHERE id=? FOR UPDATE",jobId);
        var job=ocr.get(jobId);if(job.fileId()==null)throw ApiException.invalid("须使用本案原件");var file=files.accessible(job.fileId());
        if(!caseId.equals(file.workOrderId())||!category.equals(file.category())||!"ACTIVE".equals(file.state()))throw ApiException.conflict("STALE_SOURCE","原件已变化，请重新核对");
        if(!job.confirmed()||job.reviewVersion()!=revision||job.candidate()==null||!job.candidate().path("originalReviewed").asBoolean(false)||!adopted.equals(job.candidate().path("adopted")))throw ApiException.conflict("OCR_REVIEW_REQUIRED","请保存并确认当前原件的采用信息");return file.id();
    }
    private void link(UUID business,UUID caseId,String kind,UUID job,int revision,UUID file){var source=ocr.get(job);var program=ocr.retainedProgram(job);db.update("INSERT INTO workflow_recognition_link(business_id,work_order_id,kind,job_id,revision,file_id,actor_id,provider,recognition_state,error_code,program_json) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",business,caseId,kind,job,revision,file,CurrentUser.require().id(),source.provider(),source.state(),source.errorCode(),json.writeValueAsString(program));}
    private JsonNode command(UUID id,String kind,String key,Object body,java.util.function.Supplier<JsonNode> action){
        FileService.checkKey(key);quotes.get(id);db.queryForList("SELECT id FROM work_order WHERE id=? FOR UPDATE",id);
        long actor=CurrentUser.require().id();String hash=FileService.hash(json.writeValueAsBytes(body));var old=db.queryForList("SELECT request_hash,result_json FROM workflow_recognition_command WHERE work_order_id=? AND actor_id=? AND kind=? AND command_key=?",id,actor,kind,key);
        if(!old.isEmpty()){if(!hash.equals(old.getFirst().get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","提交依据已变化，请重新核对");return json.readTree(old.getFirst().get("result_json").toString());}
        var result=action.get();db.update("INSERT INTO workflow_recognition_command(work_order_id,actor_id,kind,command_key,request_hash,result_json) VALUES(?,?,?,?,?,?)",id,actor,kind,key,hash,json.writeValueAsString(result));return result;
    }
    @Transactional public JsonNode quote(UUID id,Quote body,String key){
        if(body.quote()==null||body.jobId()==null)throw ApiException.invalid("报价和识别依据必填");
        return command(id,"QUOTE",key,body,()->{var adopted=json.valueToTree(body.quote().lines());UUID file=require(id,body.jobId(),body.revision(),"SHOP_QUOTE",adopted);
        var result=quotes.submitRaw(id,body.quote(),"R-"+FileService.hash(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));link(UUID.fromString(result.path("rawQuote").path("id").asString()),id,"QUOTE",body.jobId(),body.revision(),file);return result;});
    }
    @Transactional public JsonNode assess(UUID id,Assessment body,String key){
        if(body.assessment()==null||body.jobId()==null)throw ApiException.invalid("核损和识别依据必填");
        return command(id,"ASSESSMENT",key,body,()->{UUID file=require(id,body.jobId(),body.revision(),"LOSS_ASSESSMENT",body.assessment().amount());var result=quotes.assess(id,body.assessment(),"R-"+FileService.hash(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        link(UUID.fromString(result.path("assessment").path("id").asString()),id,"ASSESSMENT",body.jobId(),body.revision(),file);return result;});
    }
    public JsonNode source(UUID id,UUID business){quotes.get(id);var rows=db.queryForList("SELECT job_id,revision,file_id,provider,recognition_state,error_code,program_json FROM workflow_recognition_link WHERE business_id=? AND work_order_id=?",business,id);if(rows.isEmpty())return json.nullNode();var row=rows.getFirst();ocr.get((UUID)row.get("job_id"));var history=db.queryForList("SELECT candidate_json FROM ocr_review WHERE job_id=? AND revision=?",row.get("job_id"),row.get("revision"));var result=json.valueToTree(row);var out=(tools.jackson.databind.node.ObjectNode)result;out.put("state",row.get("recognition_state").toString());out.set("program",ocr.visibleCandidate(json.readTree(row.get("program_json").toString())));out.remove("program_json");out.set("review",ocr.visibleCandidate(json.readTree(history.getFirst().get("candidate_json").toString())));return out;}
}
