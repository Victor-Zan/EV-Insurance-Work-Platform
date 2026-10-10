package com.evinsurance.platform.document.application;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.document.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import java.util.*;
import java.io.*;
import java.time.Instant;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
@Service
public class FileService {
    public record View(UUID id,UUID groupId,String category,int version,String state,String name,String contentType,long size,Instant createdAt) {}
    public record MaterialStatus(boolean noticePresent,String missingReason) {}
    public record Download(View metadata,InputStream stream) {}
    private final com.evinsurance.platform.quotation.application.QuotationLifecycle quotationLifecycle;
    private final com.evinsurance.platform.repair.application.RepairMaterialPolicy repairMaterials;
    private final FileMapper files; private final FileAccess access; private final ObjectStorageService storage; private final AuditService audit;
    public FileService(FileMapper files,FileAccess access,ObjectStorageService storage,AuditService audit,com.evinsurance.platform.quotation.application.QuotationLifecycle quotationLifecycle,com.evinsurance.platform.repair.application.RepairMaterialPolicy repairMaterials) {this.repairMaterials=repairMaterials;this.quotationLifecycle=quotationLifecycle;this.files=files;this.access=access;this.storage=storage;this.audit=audit;}
    public View view(FileRow f) {
        boolean staff=access.staff(CurrentUser.require());
        String name=staff?f.originalName():f.category().toLowerCase(Locale.ROOT)+"-"+f.id()+extension(f.contentType());
        return new View(f.id(),f.groupId(),f.category(),f.versionNo(),f.state(),name,f.contentType(),f.byteSize(),f.createdAt());
    }
    public FileRow accessible(UUID id) { var f=files.find(id); if(f==null) throw ApiException.missing("File"); access.readable(f); return f; }
    public PageResponse<View> list(UUID orderId,int page,int size) {
        var order=access.order(orderId,false); var a=CurrentUser.require(); int offset=PageResponse.offset(page,size);
        String scope=access.staff(a)?"STAFF":a.roles().contains(Role.OWNER)?"OWNER":"SHOP";
        Integer assignment=access.assignment(orderId);
        return new PageResponse<>(page,size,files.count(orderId,scope,a.shopId(),assignment),files.list(orderId,offset,size,scope,a.shopId(),assignment).stream().map(this::view).toList());
    }
    @Transactional
    public View upload(UUID orderId,FileCategory category,UUID replace,Integer assignmentVersion,String key,MultipartFile upload) {
        var a=CurrentUser.require(); var order=access.order(orderId,true); access.mutable(order); checkKey(key);
        Integer current=access.assignment(orderId);
        if(!a.roles().contains(Role.CUSTOMER_SERVICE)) {
            if(!a.roles().contains(Role.REPAIR_SHOP)||!category.shopVisible()||replace!=null||current==null||!current.equals(assignmentVersion)) throw ApiException.denied();
        }
        if(upload.isEmpty()||upload.getSize()>10485760) throw ApiException.invalid("File must be 1 byte to 10 MiB");
        byte[] bytes;
        try(var in=upload.getInputStream()) {bytes=in.readNBytes(10485761);} catch(IOException e){throw ApiException.invalid("Cannot read upload");}
        if(bytes.length==0||bytes.length>10485760) throw ApiException.invalid("File must be 1 byte to 10 MiB");
        quotationLifecycle.beforeMaterialChange(order,category);
        String name=upload.getOriginalFilename();
        if(name==null||name.length()>160||name.contains("/")||name.contains("\\")||name.chars().anyMatch(c->c<32)) throw ApiException.invalid("Invalid file name");
        String type=detect(bytes,name,upload.getContentType()); String sha=hash(bytes);
        String requestHash=hash((orderId+":"+category+":"+replace+":"+assignmentVersion+":"+sha+":"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var command=files.command(a.id(),key);
        if(command!=null) {
            if(!requestHash.equals(command.get("request_hash"))) throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Key already used for different upload");
            return view(accessible((UUID)command.get("file_id")));
        }
        FileRow previous=replace==null?null:accessible(replace);
        if(previous!=null && (!previous.workOrderId().equals(orderId)||!previous.category().equals(category.name())||!"ACTIVE".equals(previous.state()))) throw ApiException.conflict("FILE_VERSION_CONFLICT","Only the current active version of the same category can be replaced");
        if(previous!=null)repairMaterials.requireChangeable(previous.id());
        if(previous==null&&files.activeCount(orderId,category.name())>=20) throw ApiException.conflict("FILE_LIMIT","At most 20 active files per category");
        UUID id=UUID.randomUUID(); String objectKey=orderId+"/"+id;
        // Compensate on rollback, including failures during transaction commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.compensate(objectKey);}
        });
        storage.put(objectKey,new ByteArrayInputStream(bytes),bytes.length,type);
        if(previous!=null&&files.changeState(previous.id(),"SUPERSEDED")!=1) throw ApiException.conflict("FILE_VERSION_CONFLICT","File changed concurrently");
        var row=new FileRow(id,orderId,previous==null?id:previous.groupId(),category.name(),previous==null?1:previous.versionNo()+1,"ACTIVE",objectKey,name,type,bytes.length,sha,order.getShopId(),current,a.id(),Instant.now());
        files.insert(row); files.insertCommand(a.id(),key,requestHash,id);
        quotationLifecycle.afterMaterialChange(order,category);
        audit.record(a,previous==null?Action.FILE_UPLOAD:Action.FILE_REPLACE,"CASE_FILE",id,"category="+category+"; version="+row.versionNo());
        return view(files.find(id));
    }
    @Transactional public void voidFile(UUID id) {
        var a=CurrentUser.require(); access.customerService(a); var f=accessible(id); var order=access.order(f.workOrderId(),true); access.mutable(order);
        if("VOID".equals(f.state())) return;
        repairMaterials.requireChangeable(id);
        quotationLifecycle.beforeMaterialChange(order,FileCategory.valueOf(f.category()));
        if(files.changeState(id,"VOID")!=1) throw ApiException.conflict("FILE_VERSION_CONFLICT","File is no longer active");
        quotationLifecycle.afterMaterialChange(order,FileCategory.valueOf(f.category()));
        audit.record(a,Action.FILE_VOID,"CASE_FILE",id,"Version voided; object retained");
    }
    @Transactional public Download download(UUID id) {
        var f=accessible(id); var stream=storage.read(f.objectKey());
        try { audit.record(CurrentUser.require(),Action.FILE_DOWNLOAD,"CASE_FILE",id,"Authorized download; category="+f.category()); }
        catch(RuntimeException e){try{stream.close();}catch(IOException ignored){}throw e;}
        return new Download(view(f),stream);
    }
    public MaterialStatus materialStatus(UUID orderId) {
        access.order(orderId,false); if(!access.staff(CurrentUser.require())) throw ApiException.denied();
        return new MaterialStatus(files.activeCount(orderId,"NOTICE")>0,files.missingReason(orderId));
    }
    @Transactional public MaterialStatus missing(UUID orderId,String reason) {
        access.customerService(CurrentUser.require()); access.mutable(access.order(orderId,true));
        if(reason==null||reason.isBlank()||reason.length()>1000) throw ApiException.invalid("Missing reason must be 1..1000 characters");
        files.missing(orderId,CurrentUser.require().id(),reason.trim()); audit.record(CurrentUser.require(),Action.MATERIAL_MISSING,"WORK_ORDER",orderId,"Missing notice reason recorded; historical reason retained");
        return materialStatus(orderId);
    }
    public static void checkKey(String key){if(key==null||key.isBlank()||key.length()>128)throw ApiException.invalid("Idempotency-Key must be 1..128 characters");}
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String detect(byte[] b,String name,String declared) {
        String lower=name.toLowerCase(Locale.ROOT); String type;
        if(b.length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-'&&lower.endsWith(".pdf"))type="application/pdf";
        else if(b.length>=8&&Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10})&&lower.endsWith(".png"))type="image/png";
        else if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255&&(lower.endsWith(".jpg")||lower.endsWith(".jpeg")))type="image/jpeg";
        else throw ApiException.invalid("Only matching PDF/JPG/PNG signatures and extensions are accepted");
        if(declared!=null&&!declared.equals(type)&&!declared.equals("application/octet-stream"))throw ApiException.invalid("Content type does not match file signature");
        return type;
    }
    private static String extension(String type){return type.equals("application/pdf")?".pdf":type.equals("image/png")?".png":".jpg";}
}
