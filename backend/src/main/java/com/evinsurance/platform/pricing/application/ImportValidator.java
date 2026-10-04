package com.evinsurance.platform.pricing.application;
import com.evinsurance.platform.pricing.domain.*;
import com.evinsurance.platform.pricing.domain.ImportData.*;
import com.evinsurance.platform.pricing.domain.ImportData.Error;
import com.evinsurance.platform.pricing.infrastructure.*;
import com.evinsurance.platform.foundation.api.ApiException;
import java.time.LocalDate;
import java.time.format.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
@Component public class ImportValidator {
 private final PriceMapper mapper;private final PriceService price;
 public ImportValidator(PriceMapper mapper,PriceService price){this.mapper=mapper;this.price=price;}
 public List<Result> validate(List<RawRow> rows,boolean lock){
  List<Map<String,Object>> lookup=rows.stream().map(r->{Map<String,Object> m=new HashMap<>(r.values());m.put("rowNumber",r.rowNumber());return m;}).toList();
  Map<Integer,ResolvedImportRow> resolved=new HashMap<>();for(var row:mapper.resolve(price.encode(lookup)))resolved.put(row.getRowNumber(),row);
  var results=new ArrayList<Result>();
  for(var row:rows){
   var errors=new ArrayList<>(row.parseErrors());var m=row.values();var x=resolved.get(row.rowNumber());
   for(String field:List.of("brandCode","modelCode","internalCode","priceType","scope","amount","sourceCode","effectiveFrom"))if(m.getOrDefault(field,"").isBlank())error(errors,row,field,"Required field");
   check(errors,row,"brandCode",x.getBrandId()!=null,"Unknown brand code");check(errors,row,"modelCode",x.getModelId()!=null,"Unknown model under this brand");
   check(errors,row,"internalCode",x.getPartId()!=null,"Unknown internal part code");
   if(!m.getOrDefault("partName","").isEmpty())check(errors,row,"partName",Objects.equals(m.get("partName"),x.getPartName()),"Name must match standard part; aliases do not replace its name");
   check(errors,row,"alias",Boolean.TRUE.equals(x.getAliasValid()),"Alias must already belong to this standard part");
   check(errors,row,"modelCode",Boolean.TRUE.equals(x.getPartModelValid()),"Part-model applicability is not configured");
   check(errors,row,"sourceCode",x.getSourceId()!=null&&Boolean.TRUE.equals(x.getSourceEnabled()),"Unknown or disabled source");
   PriceType type=null;PriceScope scope=null;BigDecimal amount=null;LocalDate from=null,to=null;
   try{type=PriceType.valueOf(m.getOrDefault("priceType",""));}catch(IllegalArgumentException e){error(errors,row,"priceType","Unknown price type");}
   try{scope=PriceScope.valueOf(m.getOrDefault("scope",""));}catch(IllegalArgumentException e){error(errors,row,"scope","Expected NATIONAL, REGION or SHOP");}
   try{String raw=m.getOrDefault("amount","");if(!raw.matches("[0-9]+(\\.[0-9]{1,2})?"))throw new IllegalArgumentException();amount=new BigDecimal(raw);
    if(amount.signum()<=0||amount.compareTo(new BigDecimal("9999999999999999.99"))>0)throw new IllegalArgumentException();
   }catch(IllegalArgumentException e){error(errors,row,"amount","Positive decimal CNY required; at most 16 integer and 2 decimal digits, no rounding");}
   try{from=date(m.getOrDefault("effectiveFrom",""));}catch(IllegalArgumentException e){error(errors,row,"effectiveFrom","Expected a valid yyyy-MM-dd China natural date");}
   if(!m.getOrDefault("effectiveTo","").isBlank())try{to=date(m.get("effectiveTo"));}catch(IllegalArgumentException e){error(errors,row,"effectiveTo","Expected a valid yyyy-MM-dd China natural date");}
   if(from!=null&&to!=null&&to.isBefore(from))error(errors,row,"effectiveTo","End must be on or after start");
   String region=m.getOrDefault("regionCode",""),shop=m.getOrDefault("shopCode","");
   if(scope==PriceScope.NATIONAL&&(!region.isBlank()||!shop.isBlank()))error(errors,row,"scope","National scope has neither region nor shop");
   if(scope==PriceScope.REGION){check(errors,row,"regionCode",x.getRegionId()!=null,"Region scope requires an existing region code");if(!shop.isBlank())error(errors,row,"shopCode","Region scope must not have a shop");}
   if(scope==PriceScope.SHOP){check(errors,row,"shopCode",x.getShopId()!=null,"Shop scope requires an existing shop code");if(!region.isBlank())error(errors,row,"regionCode","Shop scope must not have a region");}
   PriceDraft draft=null;if(errors.isEmpty())draft=new PriceDraft(row.rowNumber(),x.getPartId(),x.getModelId(),type,scope,
    scope==PriceScope.REGION?x.getRegionId():null,scope==PriceScope.SHOP?x.getShopId():null,amount,x.getSourceId(),from,to);
   results.add(new Result(row.rowNumber(),m,errors,"INVALID",draft));
  }
  var valid=results.stream().filter(x->x.draft()!=null).sorted(Comparator.comparing((Result x)->x.draft().key()).thenComparing(x->x.draft().effectiveFrom()).thenComparingInt(Result::rowNumber)).toList();
  if(valid.isEmpty())return results;
  var drafts=valid.stream().map(Result::draft).toList();if(lock)mapper.lockKeys(drafts.stream().map(PriceDraft::key).distinct().sorted().toList());
  var latest=new HashMap<String,LatestPrice>();for(var row:mapper.latest(price.encode(drafts)))latest.put(row.key(),row);
  var duplicate=new HashMap<String,List<Result>>();for(var row:valid)duplicate.computeIfAbsent(row.draft().key()+"|"+row.draft().effectiveFrom(),k->new ArrayList<>()).add(row);
  var actions=new HashMap<Integer,String>();
  for(var group:duplicate.values())if(group.size()>1)for(var row:group)row.errors().add(new Error(row.rowNumber(),"effectiveFrom","Duplicate identity/start date within file",row.draft().effectiveFrom().toString()));
  for(var row:valid){
   if(!row.errors().isEmpty())continue;var d=row.draft();var last=latest.get(d.key());
   try{PriceRules.validate(d);if(last!=null)PriceRules.follows(d.effectiveFrom(),last.getEffectiveFrom(),last.getEffectiveTo());
    actions.put(row.rowNumber(),last==null?"CREATE":last.getEffectiveTo()==null?"NEW_VERSION_CLOSE_PREVIOUS":"NEW_VERSION");
    var next=new LatestPrice();next.setPartId(d.partId());next.setModelId(d.modelId());next.setPriceType(d.priceType().name());next.setScope(d.scope().name());next.setRegionId(d.regionId());next.setShopId(d.shopId());
    next.setEffectiveFrom(d.effectiveFrom());next.setEffectiveTo(d.effectiveTo());latest.put(d.key(),next);
   }catch(ApiException e){row.errors().add(new Error(row.rowNumber(),"effectiveFrom",e.getMessage(),d.effectiveFrom().toString()));}
  }
  return results.stream().map(row->new Result(row.rowNumber(),row.values(),row.errors(),row.errors().isEmpty()?actions.get(row.rowNumber()):"INVALID",row.errors().isEmpty()?row.draft():null)).toList();
 }
 private static LocalDate date(String s){
  if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();
  try{var d=LocalDate.parse(s,DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT));if(d.getYear()<1)throw new IllegalArgumentException();return d;}
  catch(DateTimeParseException e){throw new IllegalArgumentException();}
 }
 private static void check(List<Error> errors,RawRow row,String field,boolean valid,String reason){if(!valid)error(errors,row,field,reason);}
 private static void error(List<Error> errors,RawRow row,String field,String reason){errors.add(new Error(row.rowNumber(),field,reason,ImportData.context(row.values().get(field))));}
}
