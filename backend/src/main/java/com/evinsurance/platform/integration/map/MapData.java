package com.evinsurance.platform.integration.map;

import java.time.Instant;
import java.util.UUID;

public final class MapData {
    private MapData(){}
    public record Location(long id,long shopId,int version,long districtId,String addressSnapshot,
                           Double longitude,Double latitude,String source,Instant createdAt){}
    public record Model(long id,String brand,String name){}
    public record Shop(long id,String name,String address,String status,Long locationId,
                       Double longitude,Double latitude,String source,Long cityId){}
    public record Query(UUID id,long actorId,UUID workOrderId,String fingerprint,long modelId,String status,Instant expiresAt,long workOrderVersion){}
}
