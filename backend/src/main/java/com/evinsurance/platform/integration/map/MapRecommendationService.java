package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.infrastructure.OrganizationRelationsMapper;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import com.evinsurance.platform.workorder.api.*;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapRecommendationService {
    private static final int LIMIT=50;
    private final MapDataMapper data;private final MapStore store;private final MapLocationService locations;private final MapProvider provider;
    private final MapTestPositions testPositions;
    private final MapCallBudget budget;
    private final WorkOrderService orders;private final WorkOrderMapper workOrders;private final OrganizationRelationsMapper organization;
    private final Map<UUID,Snapshot> snapshots=new ConcurrentHashMap<>();
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    public MapRecommendationService(MapDataMapper data,MapStore store,MapLocationService locations,MapProvider provider,WorkOrderService orders,WorkOrderMapper workOrders,OrganizationRelationsMapper organization,MapTestPositions testPositions,MapCallBudget budget){this.budget=budget;this.testPositions=testPositions;this.data=data;this.store=store;this.locations=locations;this.provider=provider;this.orders=orders;this.workOrders=workOrders;this.organization=organization;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void purgeExpired(){snapshots.values().removeIf(snapshot->snapshot.expires().isBefore(Instant.now()));}
    public record Row(long shopId,String name,String address,String qualification,String status,Long metres,Long seconds,Instant calculatedAt,Long locationId,boolean mock){}
    private record Snapshot(UUID id,UUID caseId,long actorId,long modelId,Long cityId,Instant expires,List<Row> rows,String provider){}
    public record Result(UUID queryId,String provider,boolean mock,String direction,int strategy,String scope,boolean complete,Instant expiresAt,PageResponse<Row> shops){}
    public record Context(String accidentRegion,String vehicleBrand,String vehicleModel){}
    public Context context(UUID caseId){var order=order(caseId);return new Context(locations.regionLabel(order.accidentRegionId()),order.vehicleBrand(),order.vehicleModel());}
    public record Estimate(long candidates,long maximumRouteCalls,Integer routesRemaining,boolean fits){}
    public Estimate estimate(UUID caseId,Long cityId){
        var order=order(caseId);long modelId=model(order);if(cityId!=null)locations.validateCity(cityId);
        long count=data.candidateCount(order.accidentRegionId(),modelId,cityId,true);
        var available=budget.availability();Integer remaining=available.operations().stream().filter(row->"DRIVING".equals(row.operation())).map(MapCallBudget.OperationAllowance::remaining).findFirst().orElse(null);
        return new Estimate(count,count,remaining,count<=LIMIT&&(remaining==null||available.callsEnabled()&&count<=remaining));
    }
    private static CurrentUser access(){var actor=CurrentUser.require();if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();return actor;}
    private WorkOrderViews.WorkOrder order(UUID id){access();var order=orders.get(id);if(!Set.of("PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(order.status()))throw ApiException.conflict("MAP_CASE_READ_ONLY","当前案件不在客服派单/改派阶段");if(order.accidentRegionId()==null)throw ApiException.conflict("MAP_ACCIDENT_REGION_REQUIRED","请先人工核对事故服务区县；停车位置不会自动补写事故区域");
        try{locations.address(order.accidentRegionId(),"事故服务区县核验");}catch(ApiException invalid){throw ApiException.conflict("MAP_ACCIDENT_REGION_REQUIRED","事故服务区域须为已核对的广东区县，不能以停车城市代替");}
        return order;}
    private long model(WorkOrderViews.WorkOrder order){var matches=data.matchingModels(order.vehicleBrand(),order.vehicleModel());if(matches.size()!=1)throw ApiException.conflict("MAP_MODEL_MAPPING_REQUIRED","工单品牌/车型无法唯一匹配启用目录，请管理员完善目录；不会修改工单或猜车型");return matches.getFirst().id();}

    public Result query(UUID caseId,UUID location,Long cityId,String key){
        var actor=access();if(key==null||key.isBlank()||key.length()>120)throw ApiException.invalid("Idempotency-Key is required (max 120)");
        String fingerprint=hash(caseId+":"+location+":"+cityId);
        var existing=data.queryByKey(actor.id(),key);
        if(existing!=null){if(!existing.fingerprint().equals(fingerprint))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","同一查询键不能用于不同位置或范围");return result(existing.id(),1,20);}
        if(!running.add(actor.id()))throw ApiException.conflict("MAP_QUERY_IN_PROGRESS","该客服已有地图查询进行中，请等待");
        UUID queryId=null;MapCallBudget.DrivingAllowance allowance=null;
        try{
            var order=order(caseId);long modelId=model(order);if(cityId!=null)locations.validateCity(cityId);
            var center=locations.session(location,true);
            if(data.candidateCount(order.accidentRegionId(),modelId,cityId,true)>LIMIT)throw ApiException.conflict("MAP_CANDIDATE_LIMIT","合格网点超过50个。请明确选择网点所在城市收窄查询范围，或申请增加预算；不要修改真实事故区域或车型");
            var shops=data.candidates(order.accidentRegionId(),modelId,cityId,true,0,LIMIT);
            if("AMAP_WEB_V1".equals(provider.providerId()))allowance=budget.reserveDriving(shops.size());
            snapshots.values().removeIf(s->s.expires().isBefore(Instant.now()));
            if(snapshots.size()>=1000)throw ApiException.conflict("MAP_QUERY_LIMIT","当前地图查询较多，请稍后再试");
            var begun=store.begin(actor.id(),caseId,key,fingerprint,modelId,order.version(),shops);if(!begun.created())return result(begun.id(),1,20);queryId=begun.id();
            var rows=new ArrayList<Row>();long start=System.nanoTime(),lastCall=0;String supplierBlocked=null;
            for(var shop:shops){
                String status="READY";Long metres=null,seconds=null;Instant calculated=null;
                boolean mock="MOCK_V1".equals(provider.providerId());
                if(supplierBlocked!=null)status=supplierBlocked;
                else if(mock||center.candidate().mock()||!Set.of("ADMIN_CONFIRMED_GCJ02","AMAP_TEST_SESSION").contains(shop.source()))status=mock?"MOCK_NO_ROAD_DISTANCE":"COORDINATE_SOURCE_UNKNOWN";
                else if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(60))status="QUERY_TIME_BUDGET";
                else{
                    var current=data.candidate(order.accidentRegionId(),modelId,shop.id());
                    if(current==null||!"ELIGIBLE".equals(current.status())||!Objects.equals(current.locationId(),shop.locationId()))status="QUALIFICATION_CHANGED";
                    else try{
                        // Bounded pacing; no business locks/transactions during waits or HTTP.
                        long pause=TimeUnit.MILLISECONDS.toNanos(550)-(System.nanoTime()-lastCall);if(pause>0)LockSupport.parkNanos(pause);
                        if(Thread.currentThread().isInterrupted())throw MapFailure.of("TIMEOUT");
                        data.attempted(queryId);lastCall=System.nanoTime();
                        var route=provider.driving(center.candidate().point(),testPositions.point(shop),32);
                        if(route.mock())throw MapFailure.of("BAD_RESPONSE");metres=route.metres();seconds=route.seconds();calculated=Instant.now();
                    }catch(MapFailure failure){status=failure.code();if(Set.of("MAP_CREDENTIALS","MAP_PERMISSION","MAP_QUOTA","MAP_LOCAL_BUDGET","MAP_RATE_LIMIT").contains(status))supplierBlocked=status;}
                }
                rows.add(new Row(shop.id(),shop.name(),shop.address(),"事故区县服务匹配；启用；车型精确匹配",status,metres,seconds,calculated,shop.locationId(),mock));
            }
            rows.sort(Comparator.comparing(Row::metres,Comparator.nullsLast(Long::compareTo)).thenComparingLong(Row::shopId));
            var snapshot=new Snapshot(queryId,caseId,actor.id(),modelId,cityId,Instant.now().plusSeconds(120),List.copyOf(rows),provider.providerId());snapshots.put(queryId,snapshot);
            store.finish(queryId,"READY",shops.size());return result(queryId,1,20);
        }catch(RuntimeException error){if(queryId!=null){snapshots.remove(queryId);store.finish(queryId,"FAILED",0);}throw error;}
        finally{try{if(allowance!=null)allowance.close();}finally{running.remove(actor.id());}}
    }
    public Result result(UUID id,int page,int size){
        var actor=access();int offset=PageResponse.offset(page,size);var query=data.query(id);
        if(query==null||query.actorId()!=actor.id())throw ApiException.missing("Map query");var currentOrder=order(query.workOrderId());
        if(currentOrder.version()!=query.workOrderVersion())throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","Case or parking location changed");
        if("RUNNING".equals(query.status())&&!query.expiresAt().isBefore(Instant.now()))throw ApiException.conflict("MAP_QUERY_IN_PROGRESS","本次距离查询仍在进行，请等待后读取结果，不会重复外呼");
        var snapshot=snapshots.get(id);
        if(snapshot==null||query.expiresAt().isBefore(Instant.now())||snapshot.expires().isBefore(Instant.now()))throw ApiException.conflict("MAP_QUERY_EXPIRED","查询仍在进行或已失效；服务重启不会自动重复高德调用，请明确发起新查询");
        if(model(currentOrder)!=snapshot.modelId())throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","案件车型或目录已变化，请重新查询");
        for(var row:snapshot.rows()){var current=data.candidate(currentOrder.accidentRegionId(),snapshot.modelId(),row.shopId());if(current==null||!"ELIGIBLE".equals(current.status())||!Objects.equals(current.locationId(),row.locationId()))throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","网点资格、地址或位置已变化，旧推荐已失效，请重新查询");}
        for(var row:snapshot.rows()){
            var current=data.candidate(currentOrder.accidentRegionId(),snapshot.modelId(),row.shopId());
            if("AMAP_TEST_SESSION".equals(current.source()))try{testPositions.point(current);}catch(MapFailure failure){throw ApiException.conflict("MAP_LOCATION_EXPIRED","Temporary provider position expired or process restarted");}
        }
        boolean complete=snapshot.rows().stream().allMatch(r->"READY".equals(r.status()));
        return new Result(id,snapshot.provider(),"MOCK_V1".equals(snapshot.provider()),"VEHICLE_TO_SHOP",32,snapshot.cityId()==null?"事故区县服务资格内的广东合作网点（未设半径）":"事故区县服务资格内、网点所在城市ID="+snapshot.cityId()+"（未设半径）",complete,query.expiresAt(),new PageResponse<>(page,size,snapshot.rows().size(),snapshot.rows().stream().skip(offset).limit(size).toList()));
    }
    public PageResponse<MapData.Shop> excluded(UUID caseId,Long cityId,int page,int size){var order=order(caseId);long model=model(order);if(cityId!=null)locations.validateCity(cityId);int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,data.candidateCount(order.accidentRegionId(),model,cityId,false),data.candidates(order.accidentRegionId(),model,cityId,false,offset,size));}
    @Transactional public WorkOrderViews.WorkOrder dispatch(UUID queryId,WorkOrderRequests.Dispatch request,String key){var query=ownedQuery(queryId);if(workOrders.command(CurrentUser.require().id(),"DISPATCH",key)==null)validateSelection(queryId,request.shopId());return orders.dispatch(query.workOrderId(),request,key);}
    @Transactional public WorkOrderViews.WorkOrder reassign(UUID queryId,WorkOrderRequests.Reassign request,String key){var query=ownedQuery(queryId);if(workOrders.command(CurrentUser.require().id(),"REASSIGN",key)==null)validateSelection(queryId,request.shopId());return orders.reassign(query.workOrderId(),request,key);}
    private MapData.Query ownedQuery(UUID id){var actor=access();var query=data.query(id);if(query==null||query.actorId()!=actor.id())throw ApiException.missing("Map query");return query;}
    private MapData.Query validateSelection(UUID id,long shopId){
        var query=ownedQuery(id);if(!"READY".equals(query.status()))throw ApiException.conflict("MAP_QUERY_NOT_READY","本次推荐尚未完成");
        if(query.expiresAt().isBefore(Instant.now()))throw ApiException.conflict("MAP_QUERY_EXPIRED","推荐已过期，请重新查询或使用原人工派单");
        // Same lock order as work-order commands: case first, then shop; recheck before original service.
        if(workOrders.lock(query.workOrderId())==null)throw ApiException.missing("Work order");organization.lockShop(shopId);
        var order=order(query.workOrderId());
        var snapshot=snapshots.get(id);
        if(snapshot==null||snapshot.expires().isBefore(Instant.now()))throw ApiException.conflict("MAP_QUERY_EXPIRED","Recommendation expired; no automatic recalculation");
        if(order.version()!=query.workOrderVersion())throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","Case or parking location changed");
        if(model(order)!=query.modelId())throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","工单车型已变化，请重新查询");
        var selected=data.candidate(order.accidentRegionId(),query.modelId(),shopId);var originalLocation=data.queryLocation(id,shopId);
        if(originalLocation==null||selected==null||!"ELIGIBLE".equals(selected.status())||!originalLocation.equals(selected.locationId()))throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","网点资格、能力、服务区域或位置已变化，请重新查询");
        if("AMAP_WEB_V1".equals(provider.providerId())){
            var row=snapshot.rows().stream().filter(r->r.shopId()==shopId&&"READY".equals(r.status())).findFirst();
            if(row.isEmpty())throw ApiException.conflict("MAP_ROUTE_UNAVAILABLE","Selected shop has no successful route");
            testPositions.point(selected);
        }
        return query;
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}}
}
