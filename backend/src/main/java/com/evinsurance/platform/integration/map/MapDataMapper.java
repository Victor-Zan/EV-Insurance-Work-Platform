package com.evinsurance.platform.integration.map;

import java.util.*;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MapDataMapper {
    @Update("UPDATE work_order_assignment SET destination_shop_id=#{shopId},destination_name=#{name},destination_address=#{address},location_precision=#{precision} WHERE id=#{assignmentId} AND destination_shop_id IS NULL")
    int dispatchSnapshot(@Param("assignmentId") long assignmentId,@Param("shopId") long shopId,@Param("name") String name,@Param("address") String address,@Param("precision") String precision);
    long simulationShopCount();
    List<MapData.Shop> simulationShops(@Param("offset") int offset,@Param("size") int size);
    String candidateRevision(@Param("regionId") long regionId,@Param("modelId") long modelId);
    List<MapData.Model> matchingModels(@Param("brand") String brand,@Param("model") String model);
    List<Long> capabilities(@Param("shopId") long shopId,@Param("offset") int offset,@Param("size") int size);
    long capabilityCount(@Param("shopId") long shopId);
    long enabledModel(@Param("modelId") long modelId);
    void clearCapabilities(long shopId);
    void addCapability(@Param("shopId") long shopId,@Param("modelId") long modelId,@Param("actorId") long actorId);
    MapData.Location latestLocation(long shopId);
    void addLocation(@Param("shopId") long shopId,@Param("districtId") long districtId,@Param("address") String address,
                     @Param("longitude") double longitude,@Param("latitude") double latitude,@Param("source") String source,@Param("actorId") long actorId);
    void addTestLocation(@Param("shopId") long shopId,@Param("districtId") long districtId,@Param("address") String address,@Param("session") UUID session,@Param("expires") Instant expires,@Param("actorId") long actorId);
    long candidateCount(@Param("regionId") long regionId,@Param("modelId") long modelId,@Param("cityId") Long cityId,@Param("eligible") boolean eligible);
    List<MapData.Shop> candidates(@Param("regionId") long regionId,@Param("modelId") long modelId,@Param("cityId") Long cityId,
                                  @Param("eligible") boolean eligible,@Param("offset") int offset,@Param("size") int size);
    MapData.Shop candidate(@Param("regionId") long regionId,@Param("modelId") long modelId,@Param("shopId") long shopId);
    int beginQuery(@Param("id") UUID id,@Param("actorId") long actorId,@Param("caseId") UUID caseId,@Param("key") String key,
                   @Param("fingerprint") String fingerprint,@Param("modelId") long modelId,@Param("orderVersion") long orderVersion,@Param("expires") Instant expires);
    MapData.Query queryByKey(@Param("actorId") long actorId,@Param("key") String key);
    MapData.Query query(UUID id);
    void queryShop(@Param("queryId") UUID queryId,@Param("shopId") long shopId,@Param("locationId") long locationId);
    Long queryLocation(@Param("queryId") UUID queryId,@Param("shopId") long shopId);
    void attempted(UUID id);
    void finish(@Param("id") UUID id,@Param("status") String status);
}
