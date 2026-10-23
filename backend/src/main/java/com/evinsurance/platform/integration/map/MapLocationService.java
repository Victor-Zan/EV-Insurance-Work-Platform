package com.evinsurance.platform.integration.map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.infrastructure.*;
import java.util.List;
import java.util.*;
import java.time.Instant;
import com.evinsurance.platform.audit.application.AuditService;
import org.springframework.stereotype.Service;

@Service
public class MapLocationService {
    private final MapProvider provider;
    private final RegionMapper regions;
    private final AuditService audit;
    private final Map<UUID,Session> sessions=new HashMap<>();
    public MapLocationService(MapProvider provider,RegionMapper regions,AuditService audit){this.provider=provider;this.regions=regions;this.audit=audit;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public synchronized void purgeExpired(){sessions.values().removeIf(session->session.expires().isBefore(Instant.now()));}
    public record Configuration(String provider,boolean mock,String coordinateSystem,boolean recommendationsAvailable){}
    public record RegionOption(long id,String name,String adcode){}
    private static void access(){if(!CurrentUser.require().roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();}
    public Configuration configuration(){access();return new Configuration(provider.providerId(),"MOCK_V1".equals(provider.providerId()),"GCJ-02",true);}
    public PageResponse<RegionOption> regions(Long cityId,int page,int size){
        access();return regionOptions(cityId,page,size);
    }
    public PageResponse<RegionOption> adminRegions(Long cityId,int page,int size){CurrentUser.require().requireAdmin();return regionOptions(cityId,page,size);}
    private PageResponse<RegionOption> regionOptions(Long cityId,int page,int size){
        int offset=PageResponse.offset(page,size);
        RegionEntity parent=cityId==null?province():city(cityId);
        if(parent==null)return new PageResponse<>(page,size,0,List.of());
        var query=new QueryWrapper<RegionEntity>().eq("parent_id",parent.getId()).eq("enabled",true).eq("level",cityId==null?2:3);
        long total=regions.selectCount(query);
        var rows=regions.selectList(query.orderByAsc("sort_order","id").last("LIMIT "+size+" OFFSET "+offset));
        return new PageResponse<>(page,size,total,rows.stream().map(r->new RegionOption(r.getId(),r.getName(),r.getCode())).toList());
    }
    // No database transaction is held while sending the structured address to the provider.
    public record Choice(UUID token,MapProvider.AddressCandidate candidate){}
    public record Session(UUID token,long actorId,long districtId,MapProvider.AddressCandidate candidate,boolean confirmed,Instant expires,String provider){}
    public List<Choice> resolve(long districtId,String detail){
        access();var address=address(districtId,detail);var result=provider.resolve(address);
        return remember(districtId,result);
    }
    public record PlaceChoices(int page,int size,boolean hasMore,List<Choice> records){}
    public PlaceChoices places(long districtId,String keywords,int page){
        access();var expected=address(districtId,keywords);
        if(keywords.length()>80||page<1||page>20)throw ApiException.invalid("地点名称最多80个字符，页码范围1至20");
        ensurePlaceCapacity();
        var result=provider.places(expected,page);
        return new PlaceChoices(result.page(),result.size(),result.hasMore(),remember(districtId,result.records()));
    }
    private synchronized void ensurePlaceCapacity(){
        sessions.values().removeIf(s->s.expires().isBefore(Instant.now()));long actor=CurrentUser.require().id();
        if(sessions.size()+10>1000||sessions.values().stream().filter(s->s.actorId()==actor).count()+10>40)throw ApiException.conflict("MAP_SESSION_LIMIT","当前位置候选过多，请稍后再搜索；尚未调用高德");
    }
    public List<Choice> selectedPoint(long districtId,MapProvider.Point point){
        access();var expected=address(districtId,"地图选点人工核对");GuangdongAddress.point(point);
        var result=provider.locate(point);
        boolean same=GuangdongAddress.inProvince(result)&&expected.city().equals(result.city())&&expected.district().equals(result.district())&&expected.districtAdcode().equals(result.adcode());
        String status=!same?"REGION_MISMATCH":result.address()==null||result.address().isBlank()?"NEEDS_DETAIL":"REQUIRES_CONFIRMATION";
        return remember(districtId,List.of(new MapProvider.AddressCandidate(result.address(),point,result.province(),result.city(),result.district(),result.adcode(),"地图选点·行政核验",status,result.mock())));
    }
    synchronized List<Choice> remember(long district,List<MapProvider.AddressCandidate> candidates){
        sessions.values().removeIf(s->s.expires().isBefore(Instant.now()));
        long actor=CurrentUser.require().id();
        if(sessions.size()+candidates.size()>1000||sessions.values().stream().filter(s->s.actorId()==actor).count()+candidates.size()>40)throw ApiException.conflict("MAP_SESSION_LIMIT","当前位置候选过多，请稍后再查询");
        return candidates.stream().map(c->{UUID token=UUID.randomUUID();sessions.put(token,new Session(token,actor,district,c,false,Instant.now().plusSeconds(600),provider.providerId()));return new Choice(token,c);}).toList();
    }
    public synchronized Session confirm(UUID token){
        access();var s=session(token,false);
        if(!"REQUIRES_CONFIRMATION".equals(s.candidate().status()))throw ApiException.invalid("位置有地域冲突或不明确，请补充详细地址");
        if(!s.confirmed()){s=new Session(s.token(),s.actorId(),s.districtId(),s.candidate(),true,s.expires(),s.provider());sessions.put(token,s);
            audit.record(CurrentUser.require(),AuditService.Action.MAP_LOCATION_CONFIRM,"MAP_LOCATION",token,"districtId="+s.districtId()+"; provider="+s.provider()+"; address/coordinates omitted");}
        return s;
    }
    public synchronized Session session(UUID token,boolean confirmed){
        access();var s=sessions.get(token);
        if(s==null||s.actorId()!=CurrentUser.require().id())throw ApiException.missing("Location confirmation");
        if(s.expires().isBefore(Instant.now())||!s.provider().equals(provider.providerId()))throw ApiException.conflict("MAP_LOCATION_EXPIRED","车辆位置确认已失效，请重新确认");
        if(confirmed&&!s.confirmed())throw ApiException.invalid("请先确认车辆当前停放位置");return s;
    }
    MapProvider.Address address(long districtId,String detail){
        var district=regions.selectById(districtId);
        if(district==null||district.getLevel()!=3||!Boolean.TRUE.equals(district.getEnabled()))throw ApiException.invalid("请选择启用的广东区县");
        var city=city(district.getParentId());
        var address=new MapProvider.Address(city.getName(),city.getCode(),district.getName(),district.getCode(),detail);
        GuangdongAddress.validate(address);return address;
    }
    private RegionEntity province(){return regions.selectOne(new QueryWrapper<RegionEntity>().eq("code","440000").eq("name","广东省").eq("level",1).eq("enabled",true));}
    private RegionEntity city(long id){
        var row=regions.selectById(id);var province=province();
        if(row==null||province==null||row.getLevel()!=2||!Boolean.TRUE.equals(row.getEnabled())||!province.getId().equals(row.getParentId())||!row.getCode().matches("44[0-9]{2}00"))throw ApiException.invalid("请选择启用的广东城市");
        return row;
    }
    void validateCity(long id){city(id);}
    String regionLabel(long id){var row=regions.selectById(id);if(row==null)return "区域资料不存在";var parent=row.getParentId()==null?null:regions.selectById(row.getParentId());return (parent==null?"":parent.getName()+" / ")+row.getName();}
}
