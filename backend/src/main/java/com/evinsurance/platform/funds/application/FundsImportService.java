package com.evinsurance.platform.funds.application;
import com.evinsurance.platform.funds.api.FundsRequests;
import com.evinsurance.platform.funds.domain.FundsMoney;
import com.evinsurance.platform.funds.infrastructure.*;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import tools.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class FundsImportService {
    private final FundsImportMapper mapper;private final FundsService funds;private final FundsFileParser parser;private final ObjectStorageService storage;private final AuditService audit;private final ObjectMapper json;private final TransactionTemplate tx;
    public FundsImportService(FundsImportMapper mapper,FundsService funds,FundsFileParser parser,ObjectStorageService storage,AuditService audit,ObjectMapper json,PlatformTransactionManager manager){this.mapper=mapper;this.funds=funds;this.parser=parser;this.storage=storage;this.audit=audit;this.json=json;this.tx=new TransactionTemplate(manager);}
    private Map<String,Object> owned(UUID id){funds.writer();var row=mapper.findBatch(id);if(row==null)throw ApiException.missing("Funds import");if(((Number)row.get("actor_id")).longValue()!=CurrentUser.require().id())throw ApiException.denied();return row;}
    private Map<String,String> raw(Map<String,Object> row){return json.readValue((String)row.get("raw_json"),new tools.jackson.core.type.TypeReference<Map<String,String>>(){});}
    private FundsRequests.Entry body(Map<String,String> values,Integer version){return new FundsRequests.Entry(version,values.get("direction"),values.get("transactionNo"),json.valueToTree(values.get("amount")),values.get("occurredAt"),values.get("note"));}
    private void validate(Map<String,String> row){
        if(row.containsKey("__parseError"))throw ApiException.invalid("Invalid columns or formulas");
        for(String field:List.of("transactionNo","claimNo","businessNo","occurredAt"))if(row.get(field)==null||row.get(field).isBlank()||row.get(field).length()>128)throw ApiException.invalid("Required identifier/time invalid");
        if(!Set.of("RECEIVE","PAY").contains(row.get("direction"))||row.get("note").length()>2000)throw ApiException.invalid("Invalid direction or note");FundsMoney.parse(json.valueToTree(row.get("amount")),true);
        try{Instant.parse(row.get("occurredAt"));}catch(Exception invalid){throw ApiException.invalid("Use ISO-8601 timestamps with offset");}
    }
    private JsonNode summary(Map<String,Object> row){return json.createObjectNode().put("id",row.get("id").toString()).put("fileName",(String)row.get("file_name")).put("format",(String)row.get("file_format")).put("sha256",(String)row.get("sha256")).put("totalRows",((Number)row.get("total_rows")).intValue()).put("createdAt",row.get("created_at").toString());}
    @Transactional public JsonNode preview(MultipartFile file){
        funds.writer();var parsed=parser.parse(file);UUID id=UUID.randomUUID();String key="funds-import/"+CurrentUser.require().id()+"/"+id+"/source."+(parsed.format().equals("CSV")?"csv":"xlsx");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.compensate(key);}});
        storage.put(key,new ByteArrayInputStream(parsed.bytes()),parsed.bytes().length,parsed.format().equals("CSV")?"text/csv":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        mapper.insertBatch(id,CurrentUser.require().id(),parsed.name(),parsed.format(),parsed.hash(),key,parsed.rows().size());
        var claims=parsed.rows().stream().map(r->r.values().get("claimNo").trim()).distinct().toList();var businesses=parsed.rows().stream().map(r->r.values().get("businessNo").trim()).distinct().toList();
        Map<String,List<UUID>> matched=new HashMap<>();for(var match:mapper.matches(json.writeValueAsString(claims),json.writeValueAsString(businesses)))matched.computeIfAbsent(match.get("kind")+":"+match.get("identifier"),ignored->new ArrayList<>()).add((UUID)match.get("id"));
        var stored=new ArrayList<FundsImportMapper.PreviewRow>();
        for(var row:parsed.rows()){
            UUID caseId=null;String state="READY",error=null;try{validate(row.values());var claim=matched.getOrDefault("CLAIM:"+row.values().get("claimNo").trim(),List.of());var business=matched.getOrDefault("BUSINESS:"+row.values().get("businessNo").trim(),List.of());if(claim.size()!=1||business.size()!=1||!claim.getFirst().equals(business.getFirst())){state="NEEDS_REVIEW";error="IDENTIFIER_CONFLICT";}else caseId=claim.getFirst();}catch(ApiException invalid){state="INVALID";error=invalid.code();}
            stored.add(new FundsImportMapper.PreviewRow(row.number(),json.writeValueAsString(row.values()),caseId,state,error));
        }
        for(int offset=0;offset<stored.size();offset+=500)mapper.insertRows(id,stored.subList(offset,Math.min(offset+500,stored.size())));
        audit.record(CurrentUser.require(),AuditService.Action.FUNDS_IMPORT_PREVIEW,"FUNDS_IMPORT",id,"Private import persisted; rows="+parsed.rows().size());return summary(mapper.findBatch(id));
    }
    public JsonNode get(UUID id){return summary(owned(id));}
    public PageResponse<JsonNode> batches(int page,int size){funds.writer();int offset=PageResponse.offset(page,size);long actor=CurrentUser.require().id();return new PageResponse<>(page,size,mapper.count(actor),mapper.batches(actor,offset,size).stream().map(this::summary).toList());}
    private JsonNode rowView(Map<String,Object> row){var out=json.createObjectNode().put("rowNumber",((Number)row.get("row_number")).intValue()).put("status",(String)row.get("status")).put("version",((Number)row.get("version")).intValue());out.set("values",json.readTree((String)row.get("raw_json")));for(String field:List.of("case_id","entry_id","error_code"))out.set(field,json.valueToTree(row.get(field)==null?null:row.get(field).toString()));return out;}
    public PageResponse<JsonNode> rows(UUID id,int page,int size){var batch=owned(id);int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,((Number)batch.get("total_rows")).longValue(),mapper.rows(id,offset,size).stream().map(this::rowView).toList());}
    @Transactional public JsonNode resolve(UUID id,int number,FundsRequests.Resolution request){
        owned(id);var row=mapper.lock(id,number);if(row==null)throw ApiException.missing("Import row");int version=((Number)row.get("version")).intValue();
        if(request.expectedVersion()==null||request.expectedVersion()!=version)throw ApiException.conflict("IMPORT_VERSION_CONFLICT","Refresh row version");
        if(!Set.of("NEEDS_REVIEW","READY","FAILED").contains(row.get("status")))throw ApiException.conflict("IMPORT_ROW_READ_ONLY","Invalid or recorded rows cannot be rematched");
        if(request.caseId()==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000)throw ApiException.invalid("Select case and provide a reason");funds.get(request.caseId());validate(raw(row));
        mapper.resolution(id,number,request.caseId(),request.reason().trim(),CurrentUser.require().id());if(mapper.resolve(id,number,version,request.caseId())!=1)throw ApiException.conflict("IMPORT_VERSION_CONFLICT","Row changed concurrently");audit.record(CurrentUser.require(),AuditService.Action.FUNDS_IMPORT_RESOLVE,"FUNDS_IMPORT",id,"Manual match recorded; row="+number);return rowView(mapper.lock(id,number));
    }
    public JsonNode confirm(UUID id){
        owned(id);for(int number:mapper.pending(id)){
            try{tx.execute(status->{owned(id);var row=mapper.lock(id,number);if(!Set.of("READY","FAILED").contains(row.get("status")))return null;UUID caseId=(UUID)row.get("case_id");validate(raw(row));
                var result=funds.record(caseId,body(raw(row),funds.version(caseId)),"import:"+id+":"+number);
                if(mapper.result(id,number,((Number)row.get("version")).intValue(),result.path("duplicate").asBoolean()?"DUPLICATE":"RECORDED",null,UUID.fromString(result.path("entryId").asText()))!=1)throw ApiException.conflict("IMPORT_VERSION_CONFLICT","Row changed concurrently");audit.record(CurrentUser.require(),AuditService.Action.FUNDS_IMPORT_CONFIRM,"FUNDS_IMPORT",id,"Row="+number+"; recorded or deduplicated");return null;
            });}catch(ApiException|org.springframework.dao.DataAccessException failed){String code=failed instanceof ApiException api?api.code():"DATABASE_CONFLICT";tx.execute(status->{owned(id);var row=mapper.lock(id,number);if(Set.of("READY","FAILED").contains(row.get("status")))mapper.result(id,number,((Number)row.get("version")).intValue(),"FAILED",code,null);return null;});}
        }return get(id);
    }
    public PageResponse<JsonNode> resolutions(UUID id,int page,int size){owned(id);int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,mapper.resolutionCount(id),mapper.resolutions(id,offset,size).stream().map(row->{var out=json.createObjectNode().put("id",((Number)row.get("id")).longValue()).put("rowNumber",((Number)row.get("row_number")).intValue()).put("caseId",row.get("case_id").toString()).put("reason",(String)row.get("reason")).put("createdAt",row.get("created_at").toString());return (JsonNode)out;}).toList());}
    public byte[] source(UUID id){var batch=owned(id);try(var input=storage.read((String)batch.get("object_key"))){byte[] bytes=input.readNBytes(10485761);if(!com.evinsurance.platform.document.application.FileService.hash(bytes).equals(batch.get("sha256")))throw new IllegalStateException("Import source hash mismatch");audit.record(CurrentUser.require(),AuditService.Action.FUNDS_IMPORT_DOWNLOAD,"FUNDS_IMPORT",id,"Private source downloaded");return bytes;}catch(java.io.IOException error){throw new IllegalStateException("Cannot read private import source",error);}}
}
