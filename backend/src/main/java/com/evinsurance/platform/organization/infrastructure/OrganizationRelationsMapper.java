package com.evinsurance.platform.organization.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationRelationsMapper {
    List<Long> serviceRegions(@Param("shopId") long shopId, @Param("offset") int offset, @Param("size") int size);
    long serviceRegionCount(@Param("shopId") long shopId);
    void deleteServiceRegions(@Param("shopId") long shopId);
    void addServiceRegion(@Param("shopId") long shopId, @Param("regionId") long regionId, @Param("actorId") long actorId);
    ShopEntity lockShop(@Param("id") long id);
    RegionEntity lockRegion(@Param("id") long id);
    long childCount(@Param("id") long id);
}
