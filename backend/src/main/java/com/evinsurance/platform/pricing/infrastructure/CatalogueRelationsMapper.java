package com.evinsurance.platform.pricing.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface CatalogueRelationsMapper {
 record Relation(long partId,long modelId,String internalCode,String partName,String modelName,long brandId){}
 record Lookup(long id,String code,String name){}
 long count(@Param("partId") Long partId,@Param("modelId") Long modelId);
 List<Relation> list(@Param("partId") Long partId,@Param("modelId") Long modelId,@Param("offset") int offset,@Param("size") int size);
 int add(@Param("partId") long partId,@Param("modelId") long modelId,@Param("actorId") long actorId);
 int delete(@Param("partId") long partId,@Param("modelId") long modelId);
 long regionCount();
 List<Lookup> regions(@Param("offset") int offset,@Param("size") int size);
 long shopCount();
 List<Lookup> shops(@Param("offset") int offset,@Param("size") int size);
 long serviceRegionCount(@Param("shopId") long shopId);
 List<Lookup> serviceRegions(@Param("shopId") long shopId,@Param("offset") int offset,@Param("size") int size);
}
