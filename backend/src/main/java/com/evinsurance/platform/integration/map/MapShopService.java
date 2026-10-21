package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.organization.infrastructure.ShopMapper;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public class MapShopService {
    private final MapDataMapper data;private final MapStore store;private final ShopMapper shops;private final MapLocationService locations;private final MapProvider provider;private final Environment environment;
    public MapShopService(MapDataMapper data,MapStore store,ShopMapper shops,MapLocationService locations,MapProvider provider,Environment environment){this.data=data;this.store=store;this.shops=shops;this.locations=locations;this.provider=provider;this.environment=environment;}
    public PageResponse<Long> capabilities(long shop,int page,int size){CurrentUser.require().requireAdmin();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,data.capabilityCount(shop),data.capabilities(shop,offset,size));}
    public void capabilities(long shop,List<Long> models){store.capabilities(shop,models);}
    public MapData.Location location(long shop){CurrentUser.require().requireAdmin();return data.latestLocation(shop);}
    public void saveLocation(long shopId,long district,double longitude,double latitude,boolean confirmed,boolean synthetic){
        CurrentUser.require().requireAdmin();if(!confirmed)throw ApiException.invalid("请确认坐标来源为GCJ-02，经度在前纬度在后");
        var shop=shops.selectById(shopId);if(shop==null)throw ApiException.missing("Shop");
        if(shop.getAddress()==null||shop.getAddress().isBlank())throw ApiException.invalid("请先维护网点完整地址");
        var input=locations.address(district,shop.getAddress());var point=new MapProvider.Point(latitude,longitude);GuangdongAddress.point(point);
        if(synthetic){if(!environment.acceptsProfiles(Profiles.of("dev"))||!"MOCK_V1".equals(provider.providerId()))throw ApiException.invalid("合成坐标仅可用于dev Mock环境");}
        else{
            if(!"AMAP_WEB_V1".equals(provider.providerId()))throw ApiException.invalid("真实坐标须在显式高德模式下核实行政归属；不会用Mock验证真实位置");
            var checked=provider.locate(point);
            if(checked.mock()||!GuangdongAddress.inProvince(checked)||!input.city().equals(checked.city())||!input.district().equals(checked.district())||!input.districtAdcode().equals(checked.adcode()))throw MapFailure.of("REGION_MISMATCH");
        }
        // Store administrator input and original shop address; never persist upstream formatted address.
        store.location(shopId,district,shop.getAddress(),longitude,latitude,synthetic?"SYNTHETIC_TEST":"ADMIN_CONFIRMED_GCJ02");
    }
}
