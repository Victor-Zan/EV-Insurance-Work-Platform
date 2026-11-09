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
    public record Quote(int sourceRow,String part,String unit,String nature,String originalAmount,BigDecimal amount) {}
    private record PartKey(String name,String unit) {}
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
        var actor=PricingAccess.write();
        mapper.lockSource("historical-price-catalogue");
        String key="archive:"+archiveId;
        if(mapper.existing("HISTORICAL_ARCHIVE",key)!=null)return 0;
        long brand=brand(brandName);
        Long model=mapper.historicalModel(brand);
        if(model==null)model=catalogue.saveModel(null,new CatalogueRequests.Model(brand,"HIST-UNSPECIFIED","车型未注明",true)).getId();
        UUID batch=UUID.randomUUID();
        mapper.batch(batch,"HISTORICAL_ARCHIVE",key,null,null,actor.id());
        var parts=new HashMap<PartKey,Long>();
        var originals=new ArrayList<Map<String,Object>>();
        var reviews=new ArrayList<Map<String,Object>>();
        for(var quote:quotes) {
            var partKey=new PartKey(quote.part(),quote.unit());
            long part=parts.computeIfAbsent(partKey,this::part);
            if(!mapper.applicable(part,model,quote.unit()))catalogue.addRelation(new CatalogueRequests.Relation(part,model));
            UUID id=UUID.randomUUID();
            originals.add(Map.of("id",id,"number",originals.size()+1,"original",Map.of(
                "historicalCaseId",archiveId,"lineNumber",quote.sourceRow(),"partName",quote.part(),"unit",quote.unit(),
                "amount",quote.originalAmount(),"vehicleBrand",brandName,"priceNature",quote.nature())));
            String reason=new BigDecimal(quote.originalAmount().strip()).compareTo(quote.amount())==0
                ? "正式历史档案原报价" : "用户确认更正配件商报价，原金额保留";
            reviews.add(Map.of("id",id,"part",part,"model",model,"amount",quote.amount(),"unit",quote.unit(),"nature",quote.nature(),"reason",reason));
        }
        for(int offset=0;offset<originals.size();offset+=500) {
            int end=Math.min(offset+500,originals.size());
            mapper.samples(batch,json.writeValueAsString(originals.subList(offset,end)));
            mapper.historicalReviews(json.writeValueAsString(reviews.subList(offset,end)),actor.id());
        }
        mapper.advanceBatch(batch);
        audit.record(actor,AuditService.Action.PRICE_SAMPLE_CAPTURE,"PRICE_SAMPLE_BATCH",batch,"Historical archive="+archiveId+"; confirmed prices="+quotes.size());
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

    private String code(String prefix,String value) {
        return prefix+FileService.hash(value.getBytes(StandardCharsets.UTF_8)).substring(0,24);
    }
}
