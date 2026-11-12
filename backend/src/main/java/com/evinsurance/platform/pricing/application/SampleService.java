package com.evinsurance.platform.pricing.application;

import com.evinsurance.platform.pricing.api.SampleRequests;
import com.evinsurance.platform.pricing.domain.PricingAccess;
import com.evinsurance.platform.pricing.infrastructure.SampleMapper;
import com.evinsurance.platform.quotation.application.QuotationService;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SampleService {
    public static final List<String> FIELDS=List.of("partName","vehicleModel","amount","unit","partBrand","region","priceNature","sourceNote");
    private final SampleMapper mapper;
    private final PriceFileParser parser;
    private final ObjectMapper json;
    private final AuditService audit;
    private final ObjectStorageService storage;
    private final QuotationService quotations;

    public SampleService(SampleMapper mapper,PriceFileParser parser,ObjectMapper json,AuditService audit,
                         ObjectStorageService storage,QuotationService quotations) {
        this.mapper=mapper;this.parser=parser;this.json=json;this.audit=audit;this.storage=storage;this.quotations=quotations;
    }

    private JsonNode detail(UUID id) {
        String value=mapper.detail(id);
        if(value==null)throw ApiException.missing("Price sample");
        return json.readTree(value);
    }

    private SampleRequests.Filter filter(SampleRequests.Filter f,boolean confirmed) {
        PricingAccess.read();
        for(String value:Arrays.asList(f.query(),f.region(),f.unit(),f.partBrand(),f.priceNature()))
            if(value!=null&&value.length()>120)throw ApiException.invalid("筛选文字最多120字");
        if(f.from()!=null&&f.to()!=null&&f.from().isAfter(f.to()))throw ApiException.invalid("开始日期不得晚于结束日期");
        if(f.status()!=null&&!Set.of("","PENDING","CONFIRMED","EXCLUDED").contains(f.status()))throw ApiException.invalid("核对状态无效");
        if(f.sourceKind()!=null&&!Set.of("","XIAOBANGBANG","MARKET","SHOP_QUOTE","HISTORICAL_ARCHIVE").contains(f.sourceKind()))throw ApiException.invalid("来源类型无效");
        if(f.priceNature()!=null&&!Set.of("","MARKET_RETAIL","SHOP_QUOTED","SUPPLIER_QUOTED","CP_QUOTED","UNCLASSIFIED").contains(f.priceNature()))throw ApiException.invalid("价格性质无效");
        if(Boolean.TRUE.equals(f.exactGroup())&&(f.partId()==null||f.modelId()==null||f.unit()==null||f.priceNature()==null))throw ApiException.invalid("参考分组条件不完整");
        return new SampleRequests.Filter(f.query(),f.modelId(),f.partId(),f.sourceKind(),confirmed?"CONFIRMED":f.status(),f.region(),f.from(),f.to(),f.batchId(),f.unit(),f.partBrand(),f.priceNature(),f.exactGroup(),f.brandId());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<JsonNode> list(SampleRequests.Filter request,int page,int size,boolean reference) {
        var f=filter(request,reference);
        int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,mapper.count(f),mapper.list(f,offset,size).stream().map(json::readTree).toList());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<JsonNode> groups(SampleRequests.Filter request,int page,int size) {
        var f=filter(request,true);
        int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,mapper.groupCount(f),mapper.groups(f,offset,size).stream().map(json::readTree).toList());
    }

    public PageResponse<JsonNode> history(UUID id,int page,int size) {
        PricingAccess.read();detail(id);
        int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,mapper.historyCount(id),mapper.history(id,offset,size).stream().map(json::readTree).toList());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JsonNode referenceDetails(UUID id) {
        PricingAccess.read();
        var record=detail(id);
        if(!"CONFIRMED".equals(record.path("status").asText()))throw ApiException.missing("Confirmed price sample");
        var result=json.createObjectNode();
        result.set("record",record);
        result.set("relatedQuotes",json.valueToTree(("HISTORICAL_ARCHIVE".equals(record.path("sourceKind").asText())
            ||record.path("original").has("sheetName"))
            ?mapper.relatedHistoricalQuotes(id).stream().map(json::readTree).toList():List.of()));
        return result;
    }

    private UUID findBatch(String kind,String key) {
        mapper.lockSource(kind+":"+key);
        var existing=mapper.existing(kind,key);
        return existing==null?null:(UUID)existing.get("id");
    }

    private JsonNode batchResult(UUID id) {
        return json.createObjectNode().put("batchId",id.toString());
    }

    private void insertRows(UUID batch,List<Map<String,Object>> rows) {
        for(int start=0;start<rows.size();start+=500)
            mapper.samples(batch,json.writeValueAsString(rows.subList(start,Math.min(start+500,rows.size()))));
        audit.record(PricingAccess.write(),AuditService.Action.PRICE_SAMPLE_CAPTURE,"PRICE_SAMPLE_BATCH",batch,"Captured rows="+rows.size());
    }

    @Transactional
    public JsonNode capture(SampleRequests.Capture request) {
        var actor=PricingAccess.write();
        if(!FIELDS.containsAll(request.values().keySet()))throw ApiException.invalid("存在未知采集字段");
        String key="manual:"+actor.id()+":"+request.requestId();
        UUID existing=findBatch(request.sourceKind(),key);
        if(existing!=null) {
            var f=new SampleRequests.Filter(null,null,null,null,null,null,null,null,existing,null,null,null,false,null);
            var saved=json.readTree(mapper.list(f,0,1).getFirst()).path("original");
            if(!saved.equals(json.valueToTree(request.values())))throw ApiException.conflict("SAMPLE_REQUEST_CONFLICT","同一请求标识对应不同资料");
            return batchResult(existing);
        }
        UUID batch=UUID.randomUUID();
        mapper.batch(batch,request.sourceKind(),key,null,null,actor.id());
        insertRows(batch,List.of(Map.of("id",UUID.randomUUID(),"number",1,"original",request.values())));
        return batchResult(batch);
    }

    @Transactional
    public JsonNode upload(MultipartFile file,String sourceKind) {
        var actor=PricingAccess.write();
        if(!Set.of("XIAOBANGBANG","MARKET").contains(sourceKind))throw ApiException.invalid("请选择肖邦邦导入或市场询价来源");
        var parsed=parser.parse(file,FIELDS);
        UUID existing=findBatch(sourceKind,"file:"+parsed.sha256());
        if(existing!=null)return batchResult(existing);
        UUID batch=UUID.randomUUID();
        String objectKey="price-samples/"+actor.id()+"/"+batch+"/source";
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {if(status!=STATUS_COMMITTED)storage.compensate(objectKey);}
        });
        try(var input=file.getInputStream()) {
            storage.put(objectKey,input,file.getSize(),parsed.format().equals("CSV")?"text/csv":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch(IOException error) {throw new UncheckedIOException(error);}
        mapper.batch(batch,sourceKind,"file:"+parsed.sha256(),parsed.fileName(),objectKey,actor.id());
        var rows=new ArrayList<Map<String,Object>>();
        for(var row:parsed.rows()) {
            var values=new LinkedHashMap<String,Object>(row.values());
            if(!row.parseErrors().isEmpty())values.put("parseErrors",row.parseErrors());
            rows.add(Map.of("id",UUID.randomUUID(),"number",row.rowNumber(),"original",values));
        }
        insertRows(batch,rows);
        return batchResult(batch);
    }

    @Transactional
    public JsonNode captureQuote(UUID caseId,SampleRequests.QuoteCapture request) {
        var actor=PricingAccess.write();
        var quote=quotations.get(caseId).path("rawQuote");
        if(!request.quoteId().toString().equals(quote.path("id").asText()))throw ApiException.conflict("QUOTE_VERSION_CONFLICT","请刷新并选择当前网点报价");
        JsonNode selected=null;
        for(var line:quote.path("lines"))if(line.path("sequence").asInt()==request.lineNumber())selected=line;
        if(selected==null)throw ApiException.missing("Quote line");
        String key="quote:"+request.quoteId()+":"+request.lineNumber();
        UUID existing=findBatch("SHOP_QUOTE",key);
        if(existing!=null)return batchResult(existing);
        var values=json.createObjectNode().put("caseId",caseId.toString()).put("quoteId",request.quoteId().toString())
            .put("lineNumber",request.lineNumber()).put("partName",selected.path("description").asText())
            .put("amount",selected.path("unitPrice").asText()).put("quantity",selected.path("quantity").asInt())
            .put("lineTotal",selected.path("amount").asText()).put("priceNature","SHOP_QUOTED");
        UUID batch=UUID.randomUUID();
        mapper.batch(batch,"SHOP_QUOTE",key,null,null,actor.id());
        insertRows(batch,List.of(Map.of("id",UUID.randomUUID(),"number",1,"original",values)));
        return batchResult(batch);
    }

    @Transactional
    public JsonNode review(UUID id,SampleRequests.Review request) {
        var actor=PricingAccess.write();
        var saved=mapper.lock(id);
        if(saved==null)throw ApiException.missing("Price sample");
        int version=((Number)saved.get("revision")).intValue();
        if(request.expectedVersion()!=version)throw ApiException.conflict("SAMPLE_VERSION_CONFLICT","资料已更新，请刷新后核对");
        BigDecimal amount=null;
        if(request.amount()!=null&&!request.amount().isBlank()) {
            if(!request.amount().matches("[0-9]{1,16}(\\.[0-9]{1,2})?"))throw ApiException.invalid("配件单价须为最多两位小数的正金额");
            amount=new BigDecimal(request.amount());
            if(amount.signum()<=0)throw ApiException.invalid("配件单价须大于零");
        }
        if("CONFIRMED".equals(request.status())) {
            if(request.partId()==null||request.modelId()==null||amount==null||request.unit()==null||"UNCLASSIFIED".equals(request.priceNature()))
                throw ApiException.invalid("请补齐配件、适配车型、单价、单位及价格性质");
            if(!mapper.applicable(request.partId(),request.modelId(),request.unit()))throw ApiException.invalid("配件须启用、适配所选车型，且单位与配件资料一致");
        }
        mapper.review(id,request,amount,actor.id());
        mapper.advance(id);
        audit.record(actor,AuditService.Action.PRICE_SAMPLE_REVIEW,"PRICE_SAMPLE",id,"Review="+(version+1)+"; status="+request.status());
        return detail(id);
    }

    public byte[] source(UUID batchId) {
        var actor=PricingAccess.read();
        var batch=mapper.batchDetail(batchId);
        if(batch==null||batch.get("object_key")==null)throw ApiException.missing("Source file");
        try(var input=storage.read((String)batch.get("object_key"))) {
            byte[] bytes=input.readNBytes((int)PriceFileParser.MAX_BYTES+1);
            if(bytes.length>PriceFileParser.MAX_BYTES)throw ApiException.invalid("来源文件超出限制");
            audit.record(actor,AuditService.Action.PRICE_SAMPLE_DOWNLOAD,"PRICE_SAMPLE_BATCH",batchId,"Downloaded source evidence");
            return bytes;
        } catch(IOException error) {throw new UncheckedIOException(error);}
    }
}
