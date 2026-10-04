package com.evinsurance.platform.pricing.application;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evinsurance.platform.pricing.api.CatalogueRequests;
import com.evinsurance.platform.pricing.domain.PricingAccess;
import com.evinsurance.platform.pricing.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogueService {
 private final BrandMapper brands; private final ModelMapper models; private final PartMapper parts;
 private final AliasMapper aliases; private final SourceMapper sources; private final CatalogueRelationsMapper relations;
 private final AuditService audit;
 public CatalogueService(BrandMapper brands,ModelMapper models,PartMapper parts,AliasMapper aliases,SourceMapper sources,
  CatalogueRelationsMapper relations,AuditService audit){
  this.brands=brands;this.models=models;this.parts=parts;this.aliases=aliases;this.sources=sources;this.relations=relations;this.audit=audit;
 }
 private <T> PageResponse<T> page(BaseMapper<T> mapper,QueryWrapper<T> query,int page,int size){
  PricingAccess.read();int offset=PageResponse.offset(page,size);long count=mapper.selectCount(query);
  return new PageResponse<>(page,size,count,mapper.selectList(query.orderByAsc("id").last("LIMIT "+size+" OFFSET "+offset)));
 }
 private <T extends CatalogueRow> T save(BaseMapper<T> mapper,T row,Long id,String type){
  var actor=PricingAccess.write();
  if(id!=null && mapper.selectById(id)==null) throw ApiException.missing(type);
  row.setId(id);row.setUpdatedAt(Instant.now());row.setUpdatedBy(actor.id());
  if(id==null){row.setCreatedAt(row.getUpdatedAt());row.setCreatedBy(actor.id());mapper.insert(row);}
  else if(mapper.updateById(row)!=1) throw ApiException.missing(type);
  audit.record(actor,id==null?Action.PRICE_CATALOGUE_CREATE:Action.PRICE_CATALOGUE_UPDATE,type,row.getId(),"Catalogue fields maintained");
  return mapper.selectById(row.getId());
 }
 private <T> void delete(BaseMapper<T> mapper,long id,String type){
  var actor=PricingAccess.write();if(mapper.deleteById(id)!=1)throw ApiException.missing(type);
  audit.record(actor,Action.PRICE_CATALOGUE_DELETE,type,id,"Unreferenced catalogue item deleted");
 }
 private static <T> QueryWrapper<T> text(QueryWrapper<T> q,String column,String value){
  if(value!=null && !value.isBlank()) { if(value.length()>120)throw ApiException.invalid("Filter too long"); q.likeRight(column,escape(value.trim())); } return q;
 }
 private static String escape(String s){return s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
 public PageResponse<BrandEntity> brands(int page,int size,String name){return page(brands,text(new QueryWrapper<>(),"name",name),page,size);}
 @Transactional public BrandEntity saveBrand(Long id,CatalogueRequests.Brand r){var e=new BrandEntity();e.setCode(r.code().trim());e.setName(r.name().trim());e.setEnabled(r.enabled());return save(brands,e,id,"PRICE_BRAND");}
 @Transactional public void deleteBrand(long id){delete(brands,id,"PRICE_BRAND");}
 public PageResponse<ModelEntity> models(int page,int size,Long brandId,String name){var q=text(new QueryWrapper<ModelEntity>(),"name",name);if(brandId!=null)q.eq("brand_id",brandId);return page(models,q,page,size);}
 @Transactional public ModelEntity saveModel(Long id,CatalogueRequests.Model r){PricingAccess.write();if(brands.selectById(r.brandId())==null)throw ApiException.missing("Brand");
  var e=new ModelEntity();e.setBrandId(r.brandId());e.setCode(r.code().trim());e.setName(r.name().trim());e.setEnabled(r.enabled());return save(models,e,id,"PRICE_MODEL");}
 @Transactional public void deleteModel(long id){delete(models,id,"PRICE_MODEL");}
 public PageResponse<PartEntity> parts(int page,int size,String internalCode,String name){var q=text(new QueryWrapper<PartEntity>(),"name",name);if(internalCode!=null)q.eq("internal_code",internalCode);return page(parts,q,page,size);}
 @Transactional public PartEntity savePart(Long id,CatalogueRequests.Part r){var e=new PartEntity();e.setInternalCode(r.internalCode().trim());e.setName(r.name().trim());e.setEnabled(r.enabled());return save(parts,e,id,"PRICE_PART");}
 @Transactional public void deletePart(long id){delete(parts,id,"PRICE_PART");}
 public PageResponse<AliasEntity> aliases(int page,int size,Long partId,String name){var q=text(new QueryWrapper<AliasEntity>(),"name",name);if(partId!=null)q.eq("part_id",partId);return page(aliases,q,page,size);}
 @Transactional public AliasEntity saveAlias(Long id,CatalogueRequests.Alias r){PricingAccess.write();if(parts.selectById(r.partId())==null)throw ApiException.missing("Part");
  var e=new AliasEntity();e.setPartId(r.partId());e.setName(r.name().trim());return save(aliases,e,id,"PRICE_ALIAS");}
 @Transactional public void deleteAlias(long id){delete(aliases,id,"PRICE_ALIAS");}
 public PageResponse<SourceEntity> sources(int page,int size){return page(sources,new QueryWrapper<>(),page,size);}
 @Transactional public SourceEntity saveSource(Long id,CatalogueRequests.Source r){var e=new SourceEntity();e.setCode(r.code().trim());e.setName(r.name().trim());e.setKind(r.kind());e.setEnabled(r.enabled());return save(sources,e,id,"PRICE_SOURCE");}
 @Transactional public void deleteSource(long id){delete(sources,id,"PRICE_SOURCE");}
 public PageResponse<CatalogueRelationsMapper.Relation> relations(int page,int size,Long partId,Long modelId){
  PricingAccess.read();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,relations.count(partId,modelId),relations.list(partId,modelId,offset,size));}
 @Transactional public void addRelation(CatalogueRequests.Relation r){
  var actor=PricingAccess.write();if(parts.selectById(r.partId())==null||models.selectById(r.modelId())==null)throw ApiException.missing("Part or model");
  relations.add(r.partId(),r.modelId(),actor.id());audit.record(actor,Action.PRICE_APPLICABILITY_CHANGE,"PRICE_PART",r.partId(),"Added model ID "+r.modelId());}
 @Transactional public void deleteRelation(long partId,long modelId){
  var actor=PricingAccess.write();if(relations.delete(partId,modelId)!=1)throw ApiException.missing("Part-model relation");
  audit.record(actor,Action.PRICE_APPLICABILITY_CHANGE,"PRICE_PART",partId,"Removed model ID "+modelId);}
 public PageResponse<CatalogueRelationsMapper.Lookup> regions(int page,int size){PricingAccess.read();int offset=PageResponse.offset(page,size);
  return new PageResponse<>(page,size,relations.regionCount(),relations.regions(offset,size));}
 public PageResponse<CatalogueRelationsMapper.Lookup> shops(int page,int size){PricingAccess.read();int offset=PageResponse.offset(page,size);
  return new PageResponse<>(page,size,relations.shopCount(),relations.shops(offset,size));}
 public PageResponse<CatalogueRelationsMapper.Lookup> serviceRegions(long shopId,int page,int size){PricingAccess.read();int offset=PageResponse.offset(page,size);
  return new PageResponse<>(page,size,relations.serviceRegionCount(shopId),relations.serviceRegions(shopId,offset,size));}
}
