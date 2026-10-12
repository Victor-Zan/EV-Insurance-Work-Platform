package com.evinsurance.platform.historical.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.historical.infrastructure.HistoricalCaseMapper;
import com.evinsurance.platform.historical.infrastructure.HistoricalCaseMapper.Data;
import com.evinsurance.platform.historical.infrastructure.HistoricalCaseMapper.PreviewRow;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class HistoricalCaseService {
    private final HistoricalCaseMapper mapper;
    private final HistoricalCaseFileParser parser;
    private final ObjectStorageService storage;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public HistoricalCaseService(HistoricalCaseMapper mapper,HistoricalCaseFileParser parser,ObjectStorageService storage,
        AuditService audit,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.parser=parser;this.storage=storage;this.audit=audit;this.json=json;this.tx=new TransactionTemplate(manager);
    }

    private void staff() {
        var roles=CurrentUser.require().roles();
        if(!roles.contains(Role.ADMIN)&&!roles.contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
    }
    private Map<String,Object> batch(UUID id) {staff();var found=mapper.batch(id);if(found==null)throw ApiException.missing("Historical import");return found;}
    private String text(Map<String,String> values,String field,int limit,boolean required) {
        String value=values.getOrDefault(field,"").trim();
        if(value.length()>limit||(required&&value.isEmpty()))throw ApiException.invalid("Invalid historical field: "+field);
        return value.isEmpty()?null:value;
    }
    private BigDecimal decimal(String value,String field,BigDecimal minimum,BigDecimal maximum,int scale) {
        try {
            var amount=new BigDecimal(value);
            if(amount.scale()>scale||amount.compareTo(minimum)<0||amount.compareTo(maximum)>0)throw ApiException.invalid("Invalid historical field: "+field);
            return amount;
        }catch(NumberFormatException error){throw ApiException.invalid("Invalid historical field: "+field);}
    }
    private Data validated(Map<String,String> values,boolean formula) {
        if(formula)throw ApiException.invalid("Formula or error cells are not accepted");
        String phone=text(values,"车主电话",20,true);
        if(!phone.matches("1[3-9][0-9]{9}"))throw ApiException.invalid("Invalid historical field: 车主电话");
        String amount=text(values,"定损金额",80,false),date=text(values,"维修日期",30,false);
        BigDecimal assessment=amount==null?null:decimal(amount,"定损金额",new BigDecimal("-999999999999999999999999999999999999.99"),new BigDecimal("999999999999999999999999999999999999.99"),2);
        LocalDate repairDate=null;
        if(date!=null)try {repairDate=LocalDate.parse(date);if(!repairDate.toString().equals(date))throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw ApiException.invalid("Invalid historical field: 维修日期");}
        return new Data(text(values,"报案号",128,false),text(values,"车主姓名",120,true),phone,
            text(values,"车牌号",100,false),text(values,"品牌",120,true),text(values,"车型",120,true),
            text(values,"事故地址",255,true),text(values,"故障描述",2000,true),
            decimal(text(values,"纬度",30,true),"纬度",new BigDecimal("-90"),new BigDecimal("90"),6),
            decimal(text(values,"经度",30,true),"经度",new BigDecimal("-180"),new BigDecimal("180"),6),
            assessment,repairDate,text(values,"维修网点",120,false),text(values,"状态",80,false),
            text(values,"故障类型",120,false),text(values,"来源",120,false),text(values,"备注",2000,false));
    }
    private String hash(Map<String,String> values){return FileService.hash(json.writeValueAsBytes(HistoricalCaseFileParser.FIELDS.stream().map(values::get).toList()));}
    private Map<String,String> raw(Map<String,Object> row){return json.readValue((String)row.get("raw_json"),new TypeReference<Map<String,String>>(){});}
    private JsonNode summary(Map<String,Object> batch) {
        UUID id=(UUID)batch.get("id");var counts=mapper.batchStatus(id);var out=json.createObjectNode()
            .put("id",id.toString()).put("fileName",(String)batch.get("file_name"))
            .put("totalRows",((Number)batch.get("total_rows")).intValue())
            .put("createdAt",batch.get("created_at").toString());
        for(String key:List.of("ready_count","invalid_count","duplicate_count","imported_count","failed_count"))
            out.put(key,((Number)counts.get(key)).intValue());
        return out;
    }

    @Transactional
    public JsonNode preview(MultipartFile file) {
        staff();var parsed=parser.parse(file);UUID id=UUID.randomUUID();
        String key="historical-import/"+id+"/source.xlsx";
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.compensate(key);}
        });
        storage.put(key,new ByteArrayInputStream(parsed.bytes()),parsed.bytes().length,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        mapper.insertBatch(id,CurrentUser.require().id(),parsed.name(),parsed.hash(),key,parsed.rows().size());
        var hashes=parsed.rows().stream().map(row->hash(row.values())).distinct().toList();
        var priorHashes=new HashSet<>(mapper.existingHashes(hashes));
        var claims=parsed.rows().stream().map(row->row.values().get("报案号").trim()).filter(value->!value.isEmpty()).distinct().toList();
        var priorClaims=new HashSet<>(claims.isEmpty()?List.<String>of():mapper.existingClaims(claims));
        var seenHashes=new HashSet<String>();var seenClaims=new HashMap<String,String>();var previews=new ArrayList<PreviewRow>();
        for(var row:parsed.rows()) {
            String fingerprint=hash(row.values()),status="READY",error=null,warning=null;
            try {validated(row.values(),row.formula());}
            catch(ApiException invalid){status="INVALID";error=invalid.getMessage();}
            String claim=row.values().get("报案号").trim();
            if(!claim.isEmpty()&&(priorClaims.contains(claim)||(seenClaims.containsKey(claim)&&!seenClaims.get(claim).equals(fingerprint))))
                warning="CLAIM_NO_REUSED";
            if(!claim.isEmpty())seenClaims.putIfAbsent(claim,fingerprint);
            if(status.equals("READY")&&(priorHashes.contains(fingerprint)||!seenHashes.add(fingerprint)))status="DUPLICATE";
            previews.add(new PreviewRow(row.number(),json.writeValueAsString(row.values()),fingerprint,status,error,warning));
        }
        for(int offset=0;offset<previews.size();offset+=200)mapper.insertRows(id,previews.subList(offset,Math.min(offset+200,previews.size())));
        audit.record(CurrentUser.require(),Action.HISTORICAL_IMPORT_PREVIEW,"HISTORICAL_IMPORT",id,"Private source previewed; rows="+previews.size());
        return summary(mapper.batch(id));
    }
    public JsonNode getBatch(UUID id){return summary(batch(id));}
    public PageResponse<JsonNode> batches(int page,int size){staff();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,mapper.batchCount(),mapper.batches(offset,size).stream().map(this::summary).toList());}
    public PageResponse<JsonNode> rows(UUID id,int page,int size){var batch=batch(id);int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,((Number)batch.get("total_rows")).longValue(),mapper.rows(id,offset,size).stream().map(row->{var out=json.createObjectNode()
            .put("rowNumber",((Number)row.get("row_number")).intValue()).put("status",(String)row.get("status"));
            out.set("values",json.readTree((String)row.get("raw_json")));
            out.set("error",json.valueToTree(row.get("error_code")));
            out.set("warning",json.valueToTree(row.get("warning_code")));
            out.set("archiveId",json.valueToTree(row.get("archive_id")));
            return (JsonNode)out;}).toList());
    }
    public JsonNode confirm(UUID id){batch(id);
        for(int number:mapper.pending(id)){
            try {tx.execute(ignored->{batch(id);var row=mapper.lockRow(id,number);
                if(row==null||!"READY".equals(row.get("status")))return null;
                var data=validated(raw(row),false);String fingerprint=(String)row.get("row_hash");UUID archiveId=UUID.randomUUID();
                int inserted=mapper.insertArchive(archiveId,id,number,fingerprint,data);
                if(inserted==0){archiveId=(UUID)mapper.archiveByHash(fingerprint).get("id");mapper.setResult(id,number,"DUPLICATE",null,archiveId);}
                else {mapper.setResult(id,number,"IMPORTED",null,archiveId);audit.record(CurrentUser.require(),Action.HISTORICAL_IMPORT_CONFIRM,"HISTORICAL_CASE",archiveId,"Archived from import="+id+"; row="+number);}
                return null;
            });}catch(ApiException|DataAccessException failed){tx.execute(ignored->{var row=mapper.lockRow(id,number);if(row!=null&&"READY".equals(row.get("status")))mapper.setResult(id,number,"FAILED",failed instanceof ApiException?"VALIDATION_ERROR":"DATABASE_ERROR",null);return null;});}
        }
        return getBatch(id);
    }
    public byte[] source(UUID id){var batch=batch(id);try(var input=storage.read((String)batch.get("object_key"))){
        byte[] bytes=input.readNBytes(10485761);if(!FileService.hash(bytes).equals(batch.get("sha256")))throw new IllegalStateException("Historical import source hash mismatch");
        audit.record(CurrentUser.require(),Action.HISTORICAL_IMPORT_DOWNLOAD,"HISTORICAL_IMPORT",id,"Private historical source downloaded");return bytes;
    }catch(IOException error){throw new IllegalStateException("Cannot read historical source",error);}}
    public PageResponse<JsonNode> archives(int page,int size,String query){staff();int offset=PageResponse.offset(page,size);
        String filter=query==null?null:query.trim();if(filter!=null&&filter.length()>100)throw ApiException.invalid("Search is too long");
        return new PageResponse<>(page,size,mapper.archiveCount(filter),mapper.archives(filter,offset,size).stream().map(row->(JsonNode)json.valueToTree(row)).toList());
    }
    public JsonNode archive(UUID id){staff();var found=mapper.archive(id);if(found==null)throw ApiException.missing("Historical case");return json.valueToTree(found);}
}
