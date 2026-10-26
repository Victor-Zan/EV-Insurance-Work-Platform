package com.evinsurance.platform.integration.map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.infrastructure.*;
import com.evinsurance.platform.workorder.api.*;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Development destinations are route-comparison fixtures, never real repair capabilities. */
@Service
public class SimulationDispatchService {
    private final Environment env;private final MapDataMapper data;private final MapProvider provider;private final MapCallBudget budget;
    private final MapTestPositions positions;private final WorkOrderService orders;private final ShopMapper shops;private final OrganizationRelationsMapper organization;private final AuditService audit;
    private final Map<UUID,Snapshot> results=new ConcurrentHashMap<>();
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    private record Snapshot(UUID id,UUID caseId,long actor,long version,List<MapData.Shop> source,List<MapRecommendationService.Row> rows,Instant expires){}
    public SimulationDispatchService(Environment env,MapDataMapper data,MapProvider provider,MapCallBudget budget,MapTestPositions positions,WorkOrderService orders,ShopMapper shops,OrganizationRelationsMapper organization,AuditService audit){this.env=env;this.data=data;this.provider=provider;this.budget=budget;this.positions=positions;this.orders=orders;this.shops=shops;this.organization=organization;this.audit=audit;}
    public boolean enabled(){return env.acceptsProfiles(Profiles.of("dev"))&&env.getProperty("MAP_SIMULATION_DESTINATIONS_ENABLED",Boolean.class,false);}
    private CurrentUser access(){var actor=CurrentUser.require();if(!enabled()||!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();return actor;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000) public void purgeExpired(){results.values().removeIf(s->s.expires().isBefore(Instant.now()));}
    private List<MapData.Shop> source(){if(data.simulationShopCount()>50)throw ApiException.conflict("MAP_CANDIDATE_LIMIT","模拟网点超过50家，未截断或调用路线");return data.simulationShops(0,50);}
    private MapRecommendationService.Row row(MapData.Shop shop){
        MapProvider.Point point=null;String status=shop.status();
        if("ELIGIBLE".equals(status))try{point=positions.point(shop);GuangdongAddress.point(point);status="NOT_CALCULATED";}catch(MapFailure invalid){status=invalid.code();}
        return new MapRecommendationService.Row(shop.id(),shop.name(),shop.address(),"本机模拟目的地；不代表真实维修或车型能力",status,null,null,null,shop.locationId(),false,point);
    }
    public PageResponse<MapRecommendationService.Row> catalog(int page,int size){access();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,data.simulationShopCount(),data.simulationShops(offset,size).stream().map(this::row).toList());}
    public MapRecommendationService.Result query(UUID caseId,long version,MapProvider.AddressCandidate location){
        var actor=access();if(!"AMAP_WEB_V1".equals(provider.providerId())||location.mock())throw ApiException.conflict("MAP_MOCK_LOCATION","模拟目的地也必须使用真实地址及道路距离，当前供应商不可用");
        if(!running.add(actor.id()))throw ApiException.conflict("MAP_QUERY_IN_PROGRESS","该客服已有模拟距离比较进行中，请等待");
        try{
            var source=source();if(source.isEmpty())throw ApiException.conflict("MAP_NO_ELIGIBLE_SHOP","尚未维护模拟维修网点地址");
            var initial=source.stream().map(this::row).toList();var rows=new ArrayList<MapRecommendationService.Row>();
            try(var allowance=budget.reserveDriving((int)initial.stream().filter(r->r.point()!=null).count())){
                long started=System.nanoTime(),lastCall=started;String blocked=null;
                for(var original:initial){
                    String status=original.status();Long metres=null,seconds=null;Instant calculated=null;
                    if(original.point()!=null){
                        if(blocked!=null)status=blocked;
                        else if(System.nanoTime()-started>TimeUnit.SECONDS.toNanos(60))status="QUERY_TIME_BUDGET";
                        else try{
                            long pause=TimeUnit.MILLISECONDS.toNanos(550)-(System.nanoTime()-lastCall);if(pause>0)LockSupport.parkNanos(pause);
                            if(Thread.currentThread().isInterrupted())throw MapFailure.of("TIMEOUT");
                            lastCall=System.nanoTime();var route=provider.driving(location.point(),original.point(),32);
                            if(route.mock())throw MapFailure.of("BAD_RESPONSE");metres=route.metres();seconds=route.seconds();calculated=Instant.now();status="READY";
                        }catch(MapFailure error){status=error.code();if(Set.of("MAP_QUOTA","MAP_RATE_LIMIT","MAP_CREDENTIALS","MAP_PERMISSION","MAP_LOCAL_BUDGET").contains(status))blocked=status;}
                    }
                    rows.add(new MapRecommendationService.Row(original.shopId(),original.name(),original.address(),original.qualification(),status,metres,seconds,calculated,original.locationId(),false,original.point()));
                }
            }
            if(!source.equals(source()))throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","模拟网点资料已变化，请明确重新计算");
            rows.sort(Comparator.comparing(MapRecommendationService.Row::metres,Comparator.nullsLast(Long::compareTo)).thenComparingLong(MapRecommendationService.Row::shopId));
            purgeExpired();if(results.size()>=1000)throw ApiException.conflict("MAP_QUERY_LIMIT","模拟比较任务过多，请稍后重试");
            UUID id=UUID.randomUUID();results.put(id,new Snapshot(id,caseId,actor.id(),version,List.copyOf(source),List.copyOf(rows),Instant.now().plusSeconds(120)));
            audit.record(actor,AuditService.Action.MAP_RECOMMENDATION_QUERY,"MAP_SIMULATION",id,"Development destinations only; candidates="+rows.size()+"; direction=VEHICLE_TO_SHOP; strategy=32");
            return result(id,1,20);
        }finally{running.remove(actor.id());}
    }
    private Snapshot current(UUID id){
        var actor=access();var snapshot=results.get(id);
        if(snapshot==null||snapshot.actor()!=actor.id())throw ApiException.missing("Simulation recommendation");
        var order=orders.get(snapshot.caseId());
        if(snapshot.expires().isBefore(Instant.now()))throw ApiException.conflict("MAP_QUERY_EXPIRED","模拟网点距离已过期，请明确重新计算");
        if(order.version()!=snapshot.version()||!"PENDING_DISPATCH".equals(order.status())||!snapshot.source().equals(source()))throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","案件或模拟网点资料已变化，请重新计算");
        for(var shop:snapshot.source())if("ELIGIBLE".equals(shop.status()))positions.point(shop);
        return snapshot;
    }
    public MapRecommendationService.Result result(UUID id,int page,int size){
        int offset=PageResponse.offset(page,size);var snapshot=current(id);
        return new MapRecommendationService.Result(id,provider.providerId(),false,"VEHICLE_TO_SHOP",32,"本机已导入模拟网点；统一由开发网点端接单",!snapshot.rows().isEmpty()&&snapshot.rows().stream().allMatch(r->"READY".equals(r.status())),snapshot.expires(),new PageResponse<>(page,size,snapshot.rows().size(),snapshot.rows().stream().skip(offset).limit(size).toList()));
    }
    @Transactional public WorkOrderViews.WorkOrder dispatch(UUID id,WorkOrderRequests.Dispatch request,String key,long accidentDistrict,long parkingDistrict,String parkingAddress,String accidentAddress,boolean approximate){
        var snapshot=current(id);var actor=access();
        if(snapshot.rows().stream().noneMatch(r->r.shopId()==request.shopId()&&"READY".equals(r.status())))throw ApiException.invalid("请选择本次计算成功的模拟网点");
        // Only this explicit dev mode redirects fixture destinations; shop accounts keep their original scope.
        var receiver=shops.selectOne(new QueryWrapper<ShopEntity>().eq("code","DEV-SHOP").eq("enabled",true));
        if(receiver==null||!receiver.getName().startsWith("【测试／非真实合作方】"))throw ApiException.conflict("MAP_TEST_RECEIVER_REQUIRED","开发接单网点未配置或未启用");
        for(long shopId:new TreeSet<>(List.of(request.shopId(),receiver.getId())))organization.lockShop(shopId);
        current(id);
        var order=orders.get(snapshot.caseId());
        if(accidentAddress!=null)order=orders.supplementDispatchAccidentAddress(order.id(),order.version(),accidentAddress);
        if(!Objects.equals(order.accidentRegionId(),accidentDistrict))order=orders.confirmAccidentDistrict(order.id(),new WorkOrderRequests.AccidentDistrict(order.version(),accidentDistrict,"核对模拟派单的事故服务区县",true));
        orders.confirmDispatchLocation(order.id(),new WorkOrderRequests.DispatchLocation(order.version(),parkingDistrict,parkingAddress,true));
        var done=orders.dispatch(order.id(),new WorkOrderRequests.Dispatch(receiver.getId(),request.confirmPossibleDuplicate(),request.duplicateReason()),key);
        audit.record(actor,AuditService.Action.WORK_ORDER_DISPATCH,"MAP_SIMULATION_DESTINATION",order.id(),"Development destination="+request.shopId()+"; receivingShop="+receiver.getId()+"; recommendation="+id+"; accuracy="+(approximate?"ROAD":"PRECISE"));
        return done;
    }
}
