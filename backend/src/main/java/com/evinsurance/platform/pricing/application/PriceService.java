package com.evinsurance.platform.pricing.application;
import com.evinsurance.platform.pricing.api.*;
import com.evinsurance.platform.pricing.domain.*;
import com.evinsurance.platform.pricing.infrastructure.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class PriceService {
 private final PriceMapper mapper;private final SourceMapper sources;private final AuditService audit;private final ObjectMapper json;
 public PriceService(PriceMapper mapper,SourceMapper sources,AuditService audit,ObjectMapper json){this.mapper=mapper;this.sources=sources;this.audit=audit;this.json=json;}
 public String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Cannot encode price data");}}
 public PageResponse<PriceView> list(PriceFilter f,int page,int size){PricingAccess.read();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,mapper.count(f),mapper.list(f,offset,size));}
 public PriceView detail(long id){PricingAccess.read();var v=mapper.detail(id);if(v==null)throw ApiException.missing("Price version");return v;}
 public PageResponse<PriceView> history(long id,int page,int size){PricingAccess.read();if(mapper.record(id)==null)throw ApiException.missing("Price record");int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,mapper.historyCount(id),mapper.history(id,offset,size));}
 @Transactional public PriceView create(PriceRequests.Create r){
  var draft=new PriceDraft(1,r.partId(),r.modelId(),r.priceType(),r.scope(),r.regionId(),r.shopId(),r.amount(),r.sourceId(),r.effectiveFrom(),r.effectiveTo());
  return append(draft,true);
 }
 @Transactional public PriceView version(long id,PriceRequests.Version v){
  PricingAccess.write();var r=mapper.record(id);if(r==null)throw ApiException.missing("Price record");
  return append(new PriceDraft(1,r.getPartId(),r.getModelId(),PriceType.valueOf(r.getPriceType()),PriceScope.valueOf(r.getScope()),r.getRegionId(),r.getShopId(),v.amount(),v.sourceId(),v.effectiveFrom(),v.effectiveTo()),false);
 }
 private PriceView append(PriceDraft draft,boolean first){
  var actor=PricingAccess.write();PriceRules.validate(draft);
  var source=sources.selectById(draft.sourceId());if(source==null)throw ApiException.missing("Data source");if(!source.getEnabled())throw ApiException.invalid("Data source is disabled");
  mapper.lockKeys(List.of(draft.key()));String rows=encode(List.of(draft));
  var existing=mapper.latest(rows);if(first&&!existing.isEmpty())throw PriceRules.conflict("Price identity already exists; use its new-version endpoint");
  if(!existing.isEmpty()){var last=existing.getFirst();PriceRules.follows(draft.effectiveFrom(),last.getEffectiveFrom(),last.getEffectiveTo());}
  mapper.ensureRecords(rows,actor.id());
  String roles=actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
  String trace=TraceContext.currentTraceId();if(trace==null)trace=UUID.randomUUID().toString();
  var inserted=mapper.apply(rows,actor.id(),roles,trace,null);
  audit.record(actor,first?Action.PRICE_CREATE:Action.PRICE_VERSION_CREATE,"PRICE_VERSION",inserted.getFirst().getId(),"Record ID "+inserted.getFirst().getRecordId()+"; version "+inserted.getFirst().getVersionNo());
  return mapper.detail(inserted.getFirst().getId());
 }
 public void rejectMutation(){PricingAccess.write();throw PriceRules.immutable();}
}
