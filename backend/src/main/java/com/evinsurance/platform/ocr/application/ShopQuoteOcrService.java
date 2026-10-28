package com.evinsurance.platform.ocr.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.integration.ocr.*;
import com.evinsurance.platform.ocr.infrastructure.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.*;

@Service
public class ShopQuoteOcrService {
    public record Item(int index,String description,String quantity,String unitPrice,String unit,String lineAmount){}
    private final ShopQuoteOcrPolicy policy;private final OcrService ocr;private final OcrMapper jobs;private final FileService files;private final OcrProvider provider;private final JdbcTemplate db;private final AuditService audit;private final ObjectMapper json;
    public ShopQuoteOcrService(ShopQuoteOcrPolicy policy,OcrService ocr,OcrMapper jobs,FileService files,OcrProvider provider,JdbcTemplate db,AuditService audit,ObjectMapper json){this.policy=policy;this.ocr=ocr;this.jobs=jobs;this.files=files;this.provider=provider;this.db=db;this.audit=audit;this.json=json;}
    public Map<String,String> configuration(){if(!CurrentUser.require().roles().contains(Role.REPAIR_SHOP))throw ApiException.denied();return Map.of("provider",provider.id(),"api",provider.api(),"version",provider.version());}
    public void check(UUID caseId,UUID id,boolean write){var r=jobs.find(id);if(r==null)throw ApiException.missing("OCR job");var scope=policy.scope(id);if(scope==null||!caseId.equals(scope.get("work_order_id")))throw ApiException.denied();policy.authorize(r,write);}
    @Transactional public OcrService.View upload(UUID caseId,int assignmentVersion,String category,int pageNo,String key,MultipartFile file){
        policy.shopCase(caseId,assignmentVersion,true);FileService.checkKey(key);
        // Work-order locking serializes source deduplication; this also serializes a command reused across cases.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",CurrentUser.require().id()+":"+provider.id()+":"+key);
        if(!Set.of("SHOP_QUOTE","ASSESSMENT").contains(category)||pageNo<1||pageNo>20)throw ApiException.invalid("仅报价/网点定损清单，页次1至20");
        byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(10485761);}catch(java.io.IOException e){throw ApiException.invalid("Cannot read image");}
        String name=file.getOriginalFilename();if(name==null||name.isBlank()||name.length()>160||name.contains("/")||name.contains("\\")||name.chars().anyMatch(ch->ch<32))throw ApiException.invalid("Invalid image filename");
        FileService.detect(bytes,name,file.getContentType());
        try{BaiduImage.validate(bytes);}catch(OcrFailure failure){throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,failure.code(),"请上传完整、非空白且符合尺寸限制的JPEG/PNG图片");}
        String requestHash=FileService.hash((caseId+":"+assignmentVersion+":"+category+":"+FileService.hash(bytes)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var command=db.queryForList("SELECT request_hash,job_id FROM shop_quote_ocr_command WHERE actor_id=? AND command_key=? AND provider=?",CurrentUser.require().id(),key,provider.id());
        if(!command.isEmpty()){if(!requestHash.equals(command.getFirst().get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","上传键已用于其他原件");return ocr.get((UUID)command.getFirst().get("job_id"));}
        var prior=db.queryForList("SELECT job_id FROM shop_quote_ocr_scope WHERE work_order_id=? AND shop_id=? AND assignment_version=? AND category=? AND file_sha256=? AND provider=?",caseId,CurrentUser.require().shopId(),assignmentVersion,category,FileService.hash(bytes),provider.id());
        if(!prior.isEmpty()){UUID priorId=(UUID)prior.getFirst().get("job_id");saveCommand(key,requestHash,priorId);return ocr.get(priorId);}
        var previousSource=db.queryForList("SELECT s.job_id,s.page_no,j.file_id FROM shop_quote_ocr_scope s JOIN ocr_job j ON j.id=s.job_id WHERE s.work_order_id=? AND s.shop_id=? AND s.assignment_version=? AND s.category=? AND s.file_sha256=? ORDER BY s.created_at,s.job_id LIMIT 1",caseId,CurrentUser.require().shopId(),assignmentVersion,category,FileService.hash(bytes));
        int nextPage;UUID fileId;
        if(!previousSource.isEmpty()){
            var previous=previousSource.getFirst();check(caseId,(UUID)previous.get("job_id"),true);
            nextPage=((Number)previous.get("page_no")).intValue();fileId=(UUID)previous.get("file_id");
        }else{
            nextPage=db.queryForObject("SELECT coalesce(max(page_no),0)+1 FROM shop_quote_ocr_scope WHERE work_order_id=? AND shop_id=? AND assignment_version=?",Integer.class,caseId,CurrentUser.require().shopId(),assignmentVersion);
            if(nextPage>20)throw ApiException.conflict("OCR_FILE_LIMIT","本次派单最多20张不同OCR原件，可继续手工录入报价");
            fileId=files.upload(caseId,FileCategory.valueOf(category),null,assignmentVersion,key,file).id();
        }
        var existing=jobs.byFile(fileId,provider.id());if(existing!=null){check(caseId,existing.id(),true);saveCommand(key,requestHash,existing.id());return ocr.get(existing.id());}
        UUID id=UUID.randomUUID();jobs.insert(id,fileId,null,CurrentUser.require().id(),false,provider.id(),provider.api(),provider.version());
        db.update("INSERT INTO shop_quote_ocr_scope(job_id,work_order_id,shop_id,assignment_version,file_sha256,category,page_no,provider) VALUES(?,?,?,?,?,?,?,?)",id,caseId,CurrentUser.require().shopId(),assignmentVersion,FileService.hash(bytes),category,nextPage,provider.id());
        saveCommand(key,requestHash,id);
        audit.record(CurrentUser.require(),Action.OCR_CREATE,"OCR_JOB",id,"Shop quotation; provider="+provider.id()+"; assignment="+assignmentVersion+"; page="+nextPage);return ocr.get(id);
    }
    private void saveCommand(String key,String hash,UUID job){db.update("INSERT INTO shop_quote_ocr_command(actor_id,command_key,request_hash,job_id,provider) VALUES(?,?,?,?,?)",CurrentUser.require().id(),key,hash,job,provider.id());}
    public PageResponse<OcrService.View> list(UUID c,int assignmentVersion,int page,int size){
        policy.shopCase(c,assignmentVersion,false);
        String from=" FROM shop_quote_ocr_scope s JOIN ocr_job j ON j.id=s.job_id JOIN case_file f ON f.id=j.file_id WHERE s.work_order_id=? AND s.shop_id=? AND s.assignment_version=? AND s.provider=? AND f.state='ACTIVE'";
        Object[] args={c,CurrentUser.require().shopId(),assignmentVersion,provider.id()};
        long count=db.queryForObject("SELECT count(*)"+from,Long.class,args);
        int offset=PageResponse.offset(page,size);
        var ids=db.queryForList("SELECT s.job_id"+from+" ORDER BY s.page_no,s.created_at,s.job_id LIMIT ? OFFSET ?",c,CurrentUser.require().shopId(),assignmentVersion,provider.id(),size,offset);
        return new PageResponse<>(page,size,count,ids.stream().map(row->get(c,(UUID)row.get("job_id"))).toList());
    }
    public OcrService.View get(UUID c,UUID j){check(c,j,false);return ocr.get(j);}
    public JsonNode program(UUID c,UUID j){check(c,j,false);return ocr.program(j);}
    public OcrSources.Raw raw(UUID c,UUID j){check(c,j,false);return ocr.raw(j);}
    public PageResponse<Map<String,Object>> history(UUID c,UUID j,int page,int size){check(c,j,false);return ocr.history(j,page,size);}
    @Transactional public OcrService.View retry(UUID c,UUID j,int expected,String key){check(c,j,true);return ocr.recognizeAgain(j,expected,key);}
    @Transactional public OcrService.View apply(UUID c,UUID j,int expected,List<Item> items,String key){
        check(c,j,true);if(items==null||items.isEmpty()||items.size()>200)throw ApiException.invalid("人工候选须为1至200行");
        var prior=jobs.reviewCommand(j,key);if(prior!=null&&((Number)prior.get("revision")).intValue()!=jobs.find(j).reviewVersion())throw ApiException.conflict("OCR_VERSION_CONFLICT","原确认后已发生其他修订，请重新核对最新结果");
        var seen=new HashSet<Integer>();var candidate=json.createObjectNode();candidate.put("origin","HUMAN_REVISION").put("purpose","SHOP_QUOTE_FORM");var rows=candidate.putArray("items");
        var program=ocr.program(j).path("items");
        for(var item:items){if(item==null||item.index()<0||item.index()>=200||!seen.add(item.index()))throw ApiException.invalid("Invalid or repeated candidate index");
            for(String value:List.of(Objects.toString(item.description(),""),Objects.toString(item.quantity(),""),Objects.toString(item.unitPrice(),""),Objects.toString(item.unit(),""),Objects.toString(item.lineAmount(),"")))if(value.length()>500)throw ApiException.invalid("Candidate field too long");
            var row=rows.addObject();row.put("index",item.index()).put("description",Objects.toString(item.description(),"")).put("quantity",Objects.toString(item.quantity(),"")).put("unitPrice",Objects.toString(item.unitPrice(),"")).put("unit",Objects.toString(item.unit(),"")).put("lineAmount",Objects.toString(item.lineAmount(),""));
            if(item.index()<program.size())row.set("programEvidence",program.path(item.index()));else row.put("addedByHuman",true);
        }
        var revised=ocr.review(j,expected,candidate,key);return ocr.confirm(j,revised.reviewVersion());
    }
}
