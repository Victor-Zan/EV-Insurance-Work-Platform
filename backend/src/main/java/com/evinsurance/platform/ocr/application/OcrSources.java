package com.evinsurance.platform.ocr.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.document.application.*;
import com.evinsurance.platform.document.infrastructure.FileMapper;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.integration.ocr.BaiduImage;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import com.evinsurance.platform.ocr.infrastructure.OcrRow;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OcrSources {
    public record Source(String objectKey,String contentType,long size,String sha256,String category){}
    public record Raw(String text,String logId,int attempt){}
    private final JdbcTemplate db;private final FileMapper files;private final FileService fileService;private final FileAccess access;private final ObjectStorageService storage;private final AuditService audit;private final com.evinsurance.platform.quotation.application.QuotationLifecycle lifecycle;private final ShopQuoteOcrPolicy shopPolicy;
    public OcrSources(JdbcTemplate db,FileMapper files,FileService fileService,FileAccess access,ObjectStorageService storage,AuditService audit,com.evinsurance.platform.quotation.application.QuotationLifecycle lifecycle,ShopQuoteOcrPolicy shopPolicy){this.db=db;this.files=files;this.fileService=fileService;this.access=access;this.storage=storage;this.audit=audit;this.lifecycle=lifecycle;this.shopPolicy=shopPolicy;}
    public boolean shopQuote(OcrRow job){return shopPolicy.scope(job.id())!=null;}
    public Integer shopPage(OcrRow job){var scope=shopPolicy.scope(job.id());return scope==null?null:((Number)scope.get("page_no")).intValue();}
    public Source source(OcrRow job,boolean authorize){
        if(authorize){var actor=CurrentUser.require();if(shopQuote(job))shopPolicy.authorize(job,false);else if(!access.staff(actor)){
            if(!actor.roles().contains(Role.REPAIR_SHOP)||job.fileId()==null)throw ApiException.denied();
            var file=fileService.accessible(job.fileId());if(!"SHOP_QUOTE".equals(file.category()))throw ApiException.denied();
        }if(job.intakeId()!=null&&!actor.roles().contains(Role.ADMIN)&&job.createdBy()!=actor.id())throw ApiException.denied();}
        if(job.fileId()!=null){var f=authorize?fileService.accessible(job.fileId()):files.find(job.fileId());if(f==null)throw ApiException.missing("OCR source");return new Source(f.objectKey(),f.contentType(),f.byteSize(),f.sha256(),f.category());}
        return db.query("SELECT * FROM ocr_intake_file WHERE id=?",(r,n)->new Source(r.getString("object_key"),r.getString("content_type"),r.getLong("byte_size"),r.getString("sha256"),"SHOP_QUOTE"),job.intakeId()).stream().findFirst().orElseThrow(()->ApiException.missing("OCR source"));
    }
    public void mutable(OcrRow job){
        if(shopQuote(job)){shopPolicy.authorize(job,true);return;}
        var actor=CurrentUser.require();source(job,true);
        if(!actor.roles().contains(Role.CUSTOMER_SERVICE)){
            if(!actor.roles().contains(Role.REPAIR_SHOP)||job.fileId()==null)throw ApiException.denied();
            var file=fileService.accessible(job.fileId());var order=access.order(file.workOrderId(),true);
            if(!"SHOP_QUOTE".equals(file.category())||!com.evinsurance.platform.document.domain.FileStagePolicy.allowed(order.getStatus(),com.evinsurance.platform.document.domain.FileCategory.SHOP_QUOTE,false))throw ApiException.denied();
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM work_order_assignment WHERE work_order_id=? AND shop_id=? AND assignment_version=? AND status='ACCEPTED')",Boolean.class,file.workOrderId(),actor.shopId(),file.assignmentVersion())))throw ApiException.denied();
        }
        if(job.fileId()!=null){var f=fileService.accessible(job.fileId());var order=access.order(f.workOrderId(),true);access.mutable(order);if(!f.state().equals("ACTIVE"))throw ApiException.conflict("STALE_SOURCE","OCR source is no longer active");com.evinsurance.platform.document.domain.FileStagePolicy.require(order.getStatus(),com.evinsurance.platform.document.domain.FileCategory.valueOf(f.category()),lifecycle.hasCurrentFormal(order));}
        else{var links=db.queryForList("SELECT work_order_id FROM ocr_draft_link WHERE job_id=?",job.id());if(!links.isEmpty())access.mutable(access.order((UUID)links.getFirst().get("work_order_id"),true));}
    }
    public boolean eligible(OcrRow job,boolean lock){
        if(shopQuote(job))return shopPolicy.eligible(job,lock);
        if(lock){db.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",job.createdBy());if(job.fileId()!=null)db.queryForList("SELECT w.id FROM work_order w JOIN case_file f ON f.work_order_id=w.id WHERE f.id=? FOR UPDATE OF w",job.fileId());}
        var enabled=db.queryForObject("SELECT count(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id WHERE u.id=? AND u.enabled AND (r.code='CUSTOMER_SERVICE' OR (r.code='REPAIR_SHOP' AND EXISTS(SELECT 1 FROM case_file f JOIN work_order w ON w.id=f.work_order_id JOIN work_order_assignment a ON a.work_order_id=w.id JOIN shop_account sa ON sa.user_id=u.id WHERE f.id=? AND f.category='SHOP_QUOTE' AND f.shop_id=sa.shop_id AND w.shop_id=sa.shop_id AND a.shop_id=sa.shop_id AND a.assignment_version=f.assignment_version AND a.status='ACCEPTED' AND w.status IN ('ARRIVED','WAITING_QUOTE','QUOTE_REVIEWING','WAITING_INSURER_ASSESSMENT','WAITING_REPAIR_AUTHORIZATION'))))",Long.class,job.createdBy(),job.fileId());
        if(enabled==null||enabled==0)return false;
        if(job.intakeId()!=null)return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM ocr_intake_file WHERE id=? AND actor_id=?)",Boolean.class,job.intakeId(),job.createdBy()));
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM case_file f JOIN work_order w ON w.id=f.work_order_id WHERE f.id=? AND f.state='ACTIVE' AND f.category IN ('NOTICE','SHOP_QUOTE','ASSESSMENT','LOSS_ASSESSMENT') AND w.status NOT IN ('COMPLETED','CANCELLED') AND (w.status<>'DRAFT' OR w.created_by=?))",Boolean.class,job.fileId(),job.createdBy()));
    }
    @Transactional public UUID upload(String key,MultipartFile file){
        var actor=CurrentUser.require();access.customerService(actor);FileService.checkKey(key);
        byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(10485761);}catch(IOException e){throw ApiException.invalid("Cannot read uploaded image");}
        String name=file.getOriginalFilename();if(name==null||name.isBlank()||name.length()>160||name.contains("/")||name.contains("\\"))throw ApiException.invalid("Invalid image filename");
        String type=FileService.detect(bytes,name,file.getContentType());try{BaiduImage.validate(bytes);}catch(com.evinsurance.platform.integration.ocr.OcrFailure failure){throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,failure.code(),"图片不符合识别要求：请检查 JPEG/PNG 格式、完整性、空白内容及 15–4096 像素尺寸");}String hash=FileService.hash(bytes);
        String requestHash=FileService.hash((hash+"|"+type+"|"+name).getBytes(StandardCharsets.UTF_8));
        db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",actor.id());
        var prior=db.queryForList("SELECT id,request_hash FROM ocr_intake_file WHERE actor_id=? AND command_key=?",actor.id(),key);
        if(!prior.isEmpty()){if(!prior.getFirst().get("request_hash").equals(requestHash))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Upload key already used");return (UUID)prior.getFirst().get("id");}
        UUID id=UUID.randomUUID();String object="ocr/intake/"+actor.id()+"/"+id;
        storage.put(object,new ByteArrayInputStream(bytes),bytes.length,type);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.compensate(object);}});
        db.update("INSERT INTO ocr_intake_file(id,actor_id,command_key,request_hash,object_key,original_name,content_type,byte_size,sha256) VALUES(?,?,?,?,?,?,?,?,?)",id,actor.id(),key,requestHash,object,name,type,bytes.length,hash);
        audit.record(actor,Action.OCR_CREATE,"OCR_INTAKE",id,"Private pre-case image uploaded; no work order created");return id;
    }
    public InputStream image(OcrRow job){var source=source(job,true);audit.record(CurrentUser.require(),Action.FILE_DOWNLOAD,"OCR_JOB",job.id(),"Authorized original image access");return storage.read(source.objectKey());}
    public Raw raw(OcrRow job){source(job,true);var entries=db.queryForList("SELECT * FROM ocr_raw_result WHERE job_id=? ORDER BY attempt DESC LIMIT 1",job.id());if(entries.isEmpty())throw ApiException.missing("Raw OCR result");var row=entries.getFirst();
        try(var in=storage.read(row.get("object_key").toString())){byte[] bytes=in.readNBytes(2097153);if(bytes.length!=((Number)row.get("byte_size")).intValue()||!FileService.hash(bytes).equals(row.get("sha256")))throw ApiException.conflict("RAW_INTEGRITY","Raw OCR integrity check failed");audit.record(CurrentUser.require(),Action.FILE_DOWNLOAD,"OCR_JOB",job.id(),"Authorized raw result access; contents excluded");return new Raw(new String(bytes,StandardCharsets.UTF_8),Objects.toString(row.get("log_id"),""),((Number)row.get("attempt")).intValue());}catch(IOException e){throw ApiException.invalid("Raw result unavailable");}
    }
}
