package com.evinsurance.platform.pricing.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.pricing.api.CatalogueRequests;
import com.evinsurance.platform.pricing.domain.PricingAccess;
import com.evinsurance.platform.pricing.infrastructure.SampleMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class HistoricalPriceImportService {
    static final String CATALOGUE_LOCK = "historical-price-catalogue";
    static final String WORKBOOK_PREFIX = "quote-workbook:";
    public record Quote(int sourceRow,String part,String unit,String nature,String originalAmount,BigDecimal amount) {}
    record WorkbookQuote(Quote quote, String brand, String model, String originalRegion) {}
    private record PartKey(String name,String unit) {}
    private record ModelKey(long brand, String name) {}
    private final SampleMapper mapper;
    private final CatalogueService catalogue;
    private final AuditService audit;
    private final ObjectMapper json;

    public HistoricalPriceImportService(SampleMapper mapper,CatalogueService catalogue,AuditService audit,ObjectMapper json) {
        this.mapper=mapper;this.catalogue=catalogue;this.audit=audit;this.json=json;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public int importQuotes(UUID archiveId,String brandName,List<Quote> quotes) {
        if(quotes.isEmpty())return 0;
        mapper.lockSource(CATALOGUE_LOCK);
        String key="archive:"+archiveId;
        return saveBatch("HISTORICAL_ARCHIVE", key, null, archiveId,
            quotes.stream().map(quote -> new WorkbookQuote(quote, brandName, "", "")).toList());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Set<SampleMapper.SourceQuote> directWorkbookQuotes(String hash) {
        PricingAccess.read();
        mapper.lockSource(CATALOGUE_LOCK);
        return Set.copyOf(mapper.existingWorkbookQuotes(hash, false));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public int importWorkbook(String hash, String fileName, List<WorkbookQuote> quotes) {
        if (quotes.isEmpty()) return 0;
        return saveBatch("XIAOBANGBANG", WORKBOOK_PREFIX + hash, fileName, null, quotes);
    }

    private int saveBatch(String kind, String key, String fileName, UUID archiveId, List<WorkbookQuote> quotes) {
        var actor=PricingAccess.write();
        var existing=mapper.existing(kind,key);
        if(archiveId!=null&&existing!=null)return 0;
        UUID batch=UUID.randomUUID();
        if(existing==null)mapper.batch(batch,kind,key,fileName,null,actor.id());
        else batch=(UUID)existing.get("id");
        var brands=new HashMap<String,Long>();
        var models=new HashMap<ModelKey,Long>();
        var parts=new HashMap<PartKey,Long>();
        var relations=new HashSet<CatalogueRequests.Relation>();
        var originals=new ArrayList<Map<String,Object>>();
        var reviews=new ArrayList<Map<String,Object>>();
        for(var row:quotes) {
            var quote=row.quote();
            long brand=brands.computeIfAbsent(row.brand(),this::brand);
            long model=models.computeIfAbsent(new ModelKey(brand,row.model()),this::model);
            var partKey=new PartKey(quote.part(),quote.unit());
            long part=parts.computeIfAbsent(partKey,this::part);
            var relation=new CatalogueRequests.Relation(part,model);
            if(relations.add(relation)&&!mapper.applicable(part,model,quote.unit()))catalogue.addRelation(relation);
            UUID id=UUID.randomUUID();
            var evidence=new LinkedHashMap<String,Object>(Map.of("lineNumber",quote.sourceRow(),"partName",quote.part(),"unit",quote.unit(),
                "amount",quote.originalAmount(),"vehicleBrand",row.brand(),"priceNature",quote.nature()));
            int number=originals.size()+1;
            String region="";
            if(archiveId!=null)evidence.put("historicalCaseId",archiveId);
            else {
                evidence.put("sheetName",PriceWorkbookParser.SHEET);
                evidence.put("vehicleModel",row.model());
                evidence.put("region",row.originalRegion());
                region=row.originalRegion().isEmpty()?"全国":row.originalRegion();
                number=quote.sourceRow()*2+("CP_QUOTED".equals(quote.nature())?1:0);
            }
            originals.add(Map.of("id",id,"number",number,"original",evidence));
            boolean original=quote.originalAmount().matches("[0-9]{1,16}(\\.[0-9]{1,2})?")
                &&new BigDecimal(quote.originalAmount()).compareTo(quote.amount())==0;
            String reason=original?(archiveId!=null?"正式历史档案原报价":"工作簿报价已确认"):"用户确认更正报价，原金额保留";
            reviews.add(Map.of("id",id,"part",part,"model",model,"amount",quote.amount(),"unit",quote.unit(),
                "nature",quote.nature(),"region",region,"reason",reason));
        }
        for(int offset=0;offset<originals.size();offset+=500) {
            int end=Math.min(offset+500,originals.size());
            mapper.samples(batch,json.writeValueAsString(originals.subList(offset,end)));
            mapper.historicalReviews(json.writeValueAsString(reviews.subList(offset,end)),actor.id());
        }
        mapper.advanceBatch(batch);
        audit.record(actor,AuditService.Action.PRICE_SAMPLE_CAPTURE,"PRICE_SAMPLE_BATCH",batch,
            (archiveId==null?"Workbook price import":"Historical archive="+archiveId)+"; confirmed prices="+quotes.size());
        return quotes.size();
    }

    private long brand(String name) {
        var existing=mapper.brandsNamed(name);
        if(existing.size()>1)throw ApiException.invalid("存在多个同名车辆品牌，请先核对品牌资料");
        return existing.isEmpty()?catalogue.saveBrand(null,new CatalogueRequests.Brand(code("HIST-B-",name),name,true)).getId():existing.getFirst();
    }

    private long part(PartKey key) {
        var existing=mapper.partsNamed(key.name(),key.unit());
        if(existing.size()>1)throw ApiException.invalid("存在多个同名同单位的配件，请先核对配件资料");
        return existing.isEmpty()?catalogue.savePart(null,new CatalogueRequests.Part(code("HIST-P-",key.name()+"\n"+key.unit()),key.name(),true,key.unit(),null,null)).getId():existing.getFirst();
    }

    private long model(ModelKey key) {
        if(key.name().isEmpty()) {
            Long found=mapper.historicalModel(key.brand());
            return found==null?catalogue.saveModel(null,new CatalogueRequests.Model(key.brand(),"HIST-UNSPECIFIED","车型未注明",true)).getId():found;
        }
        var existing=mapper.modelsNamed(key.brand(),key.name());
        if(existing.size()>1)throw ApiException.invalid("存在多个同名车型，请先核对车型资料");
        return existing.isEmpty()?catalogue.saveModel(null,new CatalogueRequests.Model(key.brand(),
            code("HIST-M-",key.name()),key.name(),true)).getId():existing.getFirst();
    }

    private String code(String prefix,String value) {
        return prefix+FileService.hash(value.getBytes(StandardCharsets.UTF_8)).substring(0,24);
    }
}
