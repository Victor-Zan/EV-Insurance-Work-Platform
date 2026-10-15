package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.organization.infrastructure.OrganizationRelationsMapper;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapStore {
    private final MapDataMapper data;private final OrganizationRelationsMapper organization;private final AuditService audit;
    public MapStore(MapDataMapper data,OrganizationRelationsMapper organization,AuditService audit){this.data=data;this.organization=organization;this.audit=audit;}
    @Transactional public void capabilities(long shopId,List<Long> modelIds){
        var actor=CurrentUser.require();actor.requireAdmin();if(organization.lockShop(shopId)==null)throw ApiException.missing("Shop");
        if(modelIds==null||modelIds.size()>100||new HashSet<>(modelIds).size()!=modelIds.size())throw ApiException.invalid("最多100个不重复车型");
        for(Long modelId:modelIds)if(modelId==null||data.enabledModel(modelId)!=1)throw ApiException.invalid("请选择启用的品牌/车型");
        var before=data.capabilities(shopId,0,100);data.clearCapabilities(shopId);
        modelIds.stream().sorted().forEach(id->data.addCapability(shopId,id,actor.id()));
        audit.record(actor,AuditService.Action.MAP_SHOP_CAPABILITY,"REPAIR_SHOP",shopId,"modelIds "+before+" -> "+modelIds.stream().sorted().toList());
    }
    @Transactional public void location(long shopId,long district,String address,double longitude,double latitude,String source){
        var actor=CurrentUser.require();actor.requireAdmin();var shop=organization.lockShop(shopId);
        if(shop==null)throw ApiException.missing("Shop");
        if(!Objects.equals(address,shop.getAddress()))throw ApiException.conflict("MAP_ADDRESS_CHANGED","网点地址已改变，请重新核对位置");
        data.addLocation(shopId,district,address,longitude,latitude,source,actor.id());var location=data.latestLocation(shopId);
        audit.record(actor,AuditService.Action.MAP_SHOP_LOCATION,"REPAIR_SHOP",shopId,"locationVersion="+location.version()+"; districtId="+district+"; source="+source+"; coordinates and address omitted");
    }
    public record Begin(UUID id,boolean created){}
    @Transactional public Begin begin(long actor,UUID caseId,String key,String fingerprint,long modelId,List<MapData.Shop> shops){
        UUID id=UUID.randomUUID();if(data.beginQuery(id,actor,caseId,key,fingerprint,modelId,Instant.now().plusSeconds(120))==0)return new Begin(data.queryByKey(actor,key).id(),false);
        for(var shop:shops)data.queryShop(id,shop.id(),shop.locationId());return new Begin(id,true);
    }
    @Transactional public void finish(UUID id,String status,int count){
        data.finish(id,status);audit.record(CurrentUser.require(),AuditService.Action.MAP_RECOMMENDATION_QUERY,"MAP_QUERY",id,"status="+status+"; candidates="+count+"; direction=VEHICLE_TO_SHOP; strategy=32; no addresses or coordinates");
    }
}
