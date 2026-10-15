package com.evinsurance.platform.quotation.application;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.ocr.application.ShopQuoteOcrPolicy;
import com.evinsurance.platform.ocr.infrastructure.OcrMapper;
import com.evinsurance.platform.quotation.api.QuotationRequests;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Invoked under the existing work-order lock and quotation transaction. */
@Service
public class QuoteOcrLineage {
    private final ShopQuoteOcrPolicy policy;private final OcrMapper jobs;private final JdbcTemplate db;private final ObjectMapper json;
    public QuoteOcrLineage(ShopQuoteOcrPolicy policy,OcrMapper jobs,JdbcTemplate db,ObjectMapper json){this.policy=policy;this.jobs=jobs;this.db=db;this.json=json;}
    public Map<String,Object> validate(UUID caseId,QuotationRequests.Line line,Set<String> used){
        var ref=line.ocr();if(ref==null)return Map.of();
        if(ref.jobId()==null||ref.revision()==null||ref.itemIndex()==null)throw ApiException.invalid("Incomplete OCR source");
        var scope=policy.scope(ref.jobId());if(scope==null||!caseId.equals(scope.get("work_order_id")))throw ApiException.denied();
        var job=jobs.lock(ref.jobId());policy.authorize(job,true);
        if(!used.add(ref.jobId()+":"+ref.itemIndex()))throw ApiException.conflict("OCR_DUPLICATE_ITEM","同一识别行不能重复计入本版报价");
        if(!job.state().equals("SUCCEEDED")||job.reviewVersion()!=ref.revision()||jobs.confirmed(job.id(),ref.revision())==0)throw ApiException.conflict("OCR_REVIEW_REQUIRED","请先确认当前报价明细快照");
        var candidate=json.readTree(job.candidateJson());var matched=new ArrayList<tools.jackson.databind.JsonNode>();
        for(var item:candidate.path("items"))if(item.path("index").asInt(-1)==ref.itemIndex())matched.add(item);
        if(!candidate.path("purpose").asString("").equals("SHOP_QUOTE_FORM")||matched.size()!=1)throw ApiException.conflict("OCR_REVIEW_REQUIRED","候选来源不属于当前报价表单");
        var item=matched.getFirst();
        try{if(!line.description().equals(item.path("description").asString())||new java.math.BigDecimal(line.quantity().asString()).compareTo(new java.math.BigDecimal(item.path("quantity").asString()))!=0||new java.math.BigDecimal(line.unitPrice().asString()).compareTo(new java.math.BigDecimal(item.path("unitPrice").asString()))!=0)throw ApiException.conflict("OCR_REVIEW_REQUIRED","报价明细已修改，请重新核对并确认当前表单");}
        catch(NumberFormatException e){throw ApiException.invalid("OCR source quantity/price incomplete");}
        return Map.of("ocrJobId",job.id(),"ocrRevision",ref.revision(),"ocrItemIndex",ref.itemIndex());
    }
    public void link(UUID quoteId,int line,QuotationRequests.Ocr ref){if(ref!=null)db.update("INSERT INTO quote_raw_ocr_line(raw_quote_id,line_no,job_id,revision,item_index) VALUES(?,?,?,?,?)",quoteId,line,ref.jobId(),ref.revision(),ref.itemIndex());}
}
