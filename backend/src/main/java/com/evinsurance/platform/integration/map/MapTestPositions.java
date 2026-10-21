package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.organization.infrastructure.ShopMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;

/** Explicitly enabled dev-only short-lived provider positions; never administrator-owned coordinates. */
@Service
public class MapTestPositions {
    private final Environment env;private final MapProvider provider;private final MapLocationService locations;
    private final ShopMapper shops;private final MapStore store;
    private final Map<UUID,MapData.Location> confirmed=new HashMap<>();private final Map<UUID,Pending> pending=new HashMap<>();private final Map<Long,Position> positions=new HashMap<>();
    private record Pending(long actor,long shop,long district,String address,MapProvider.AddressCandidate candidate,Instant expires){}
    private record Position(long shop,String address,MapProvider.Point point,Instant expires){}
    public record Choice(UUID token,MapProvider.AddressCandidate candidate){}
    public MapTestPositions(Environment env,MapProvider provider,MapLocationService locations,ShopMapper shops,MapStore store){this.env=env;this.provider=provider;this.locations=locations;this.shops=shops;this.store=store;}
    private void access(long shop){
        CurrentUser.require().requireAdmin();
        if(!env.acceptsProfiles(Profiles.of("dev"))||!env.getProperty("MAP_TEST_SESSION_ENABLED",Boolean.class,false)||!"AMAP_WEB_V1".equals(provider.providerId()))throw ApiException.denied();
        var row=shops.selectById(shop);
        if(row==null||!row.getName().startsWith("【测试／非真实合作方】"))throw ApiException.denied();
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public synchronized void purgeExpired(){
        pending.values().removeIf(value->value.expires().isBefore(Instant.now()));
        confirmed.keySet().retainAll(pending.keySet());
        positions.values().removeIf(value->value.expires().isBefore(Instant.now()));
    }
    public List<Choice> resolve(long shop,long district,String detail){
        access(shop);var row=shops.selectById(shop);var address=locations.address(district,detail);
        var candidates=provider.resolve(address); // no business transaction / lock during HTTP
        synchronized(this){
            purgeExpired();
            if(pending.size()+candidates.size()>1000)throw ApiException.conflict("MAP_SESSION_LIMIT","测试位置会话已满");
            return candidates.stream().map(candidate->{var token=UUID.randomUUID();pending.put(token,new Pending(CurrentUser.require().id(),shop,district,row.getAddress(),candidate,Instant.now().plusSeconds(600)));return new Choice(token,candidate);}).toList();
        }
    }
    public synchronized Choice choice(long shop,UUID token){
        access(shop);var value=pending.get(token);
        if(value==null||value.shop()!=shop||value.actor()!=CurrentUser.require().id()||value.expires().isBefore(Instant.now()))throw ApiException.conflict("MAP_LOCATION_EXPIRED","Temporary location expired");
        return new Choice(token,value.candidate());
    }
    public synchronized MapData.Location confirm(long shop,UUID token){
        access(shop);var value=pending.get(token);
        if(value==null||value.shop()!=shop||value.actor()!=CurrentUser.require().id()||value.expires().isBefore(Instant.now()))throw ApiException.conflict("MAP_LOCATION_EXPIRED","测试位置已失效");
        if(value.candidate().mock()||!"REQUIRES_CONFIRMATION".equals(value.candidate().status()))throw ApiException.invalid("需明确且行政归属相符的入口候选");
        if(confirmed.containsKey(token))return confirmed.get(token);
        var location=store.testLocation(shop,value.district(),value.address(),token,value.expires());
        positions.values().removeIf(position->position.expires().isBefore(Instant.now()));
        positions.put(location.id(),new Position(shop,value.address(),value.candidate().point(),value.expires()));
        confirmed.put(token,location);
        return location;
    }
    public synchronized MapProvider.Point point(MapData.Shop shop){
        if("ADMIN_CONFIRMED_GCJ02".equals(shop.source()))return new MapProvider.Point(shop.latitude(),shop.longitude());
        if(!"AMAP_TEST_SESSION".equals(shop.source())||!env.acceptsProfiles(Profiles.of("dev"))||!env.getProperty("MAP_TEST_SESSION_ENABLED",Boolean.class,false))throw MapFailure.of("COORDINATE_SOURCE_UNKNOWN");
        var value=positions.get(shop.locationId());
        if(value==null||value.shop()!=shop.id()||!Objects.equals(value.address(),shop.address())||value.expires().isBefore(Instant.now()))throw MapFailure.of("LOCATION_EXPIRED");
        return value.point();
    }
}
