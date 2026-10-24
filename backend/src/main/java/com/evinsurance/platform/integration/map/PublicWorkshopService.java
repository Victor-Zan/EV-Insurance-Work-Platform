package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.application.CaseWorkshopRegistry;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import com.evinsurance.platform.workorder.api.WorkOrderViews;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;

/** Public discovery is independent from cooperation and formal dispatch. No network transaction. */
@Service
public class PublicWorkshopService {
    private final MapProvider provider;private final MapLocationService locations;private final MapCallBudget budget;
    private final WorkOrderService orders;private final CaseWorkshopRegistry registry;private final AuditService audit;
    private final Map<UUID,Snapshot> cache=new ConcurrentHashMap<>();private final Set<Long> running=ConcurrentHashMap.newKeySet();
    public PublicWorkshopService(MapProvider provider,MapLocationService locations,MapCallBudget budget,WorkOrderService orders,CaseWorkshopRegistry registry,AuditService audit){this.provider=provider;this.locations=locations;this.budget=budget;this.orders=orders;this.registry=registry;this.audit=audit;}
    public record Row(UUID id,String source,String name,String address,String classification,String reason,MapProvider.Point point,Long metres,Long seconds,Instant calculatedAt,String status){}
    private record Snapshot(UUID id,UUID caseId,long actor,long version,UUID locationToken,Instant expires,int page,boolean hasMore,int excluded,List<Row> rows){}
    public record Result(UUID queryId,String provider,String scope,Instant expiresAt,int page,boolean hasMore,int excluded,List<Row> rows){}
    public record Settings(boolean enabled,int radiusMetres,int maximumPublicCandidates,Integer searchRemaining,Integer routesRemaining,boolean providerManaged){}
    private CurrentUser access(){var actor=CurrentUser.require();if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();return actor;}
    public Settings settings(){access();var a=budget.availability();return new Settings(registry.enabled(),10000,20,remaining(a,"POI_SEARCH"),remaining(a,"DRIVING"),a.providerManaged());}
    private static Integer remaining(MapCallBudget.Availability a,String op){return a.operations().stream().filter(r->op.equals(r.operation())).map(MapCallBudget.OperationAllowance::remaining).findFirst().orElse(null);}
    private WorkOrderViews.WorkOrder order(UUID id){access();registry.requireEnabled();var order=orders.get(id);if(!Set.of("PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(order.status()))throw ApiException.conflict("PUBLIC_WORKSHOP_READ_ONLY","当前案件不在派单或改派阶段");return order;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void purge(){cache.values().removeIf(s->s.expires().isBefore(Instant.now()));}
    private void enter(){purge();if(cache.size()>=200)throw ApiException.conflict("PUBLIC_WORKSHOP_LIMIT","当前候选会话较多，请稍后再试；未外呼");if(!running.add(access().id()))throw ApiException.conflict("PUBLIC_WORKSHOP_BUSY","已有门店操作进行中，请等待；未重复外呼");}
    private Snapshot own(UUID id){var s=cache.get(id);if(s==null||s.actor()!=access().id())throw ApiException.missing("Workshop candidates");var order=order(s.caseId());if(!s.expires().isAfter(Instant.now())||order.version()!=s.version())throw ApiException.conflict("PUBLIC_WORKSHOP_EXPIRED","停车位置、案件版本已变化或候选已过期，请明确重新操作；未自动查询");locations.session(s.locationToken(),true);return s;}
    private Instant expires(MapLocationService.Session location){return location.expires().isBefore(Instant.now().plusSeconds(600))?location.expires():Instant.now().plusSeconds(600);}
    private Result replay(CaseWorkshopRegistry.Operation op){if(!"READY".equals(op.status())||!cache.containsKey(op.id()))throw ApiException.conflict("PUBLIC_WORKSHOP_RESULT_UNAVAILABLE","该操作已尝试，结果仍在处理或已失效；不会自动重复调用");return result(op.id());}
    private static String hash(String value){return MapCallBudget.fingerprint("public-workshop",Map.of("value",value));}
    public Result search(UUID caseId,UUID locationToken,int page,String key){
        var order=order(caseId);var location=locations.session(locationToken,true);if(location.candidate().mock()||!"AMAP_WEB_V1".equals(provider.providerId()))throw ApiException.conflict("PUBLIC_WORKSHOP_REAL_ONLY","公开门店搜索仅在明确真实高德模式可用，不会返回Mock冒充结果");if(page<1||page>10)throw ApiException.invalid("page must be 1..10");
        enter();UUID operation=null;
        try{var begun=registry.begin(caseId,"SEARCH",key,hash(caseId+":"+order.version()+":"+locationToken+":"+page));if(!begun.created())return replay(begun.operation());operation=begun.operation().id();
            var pageResult=provider.workshops(location.candidate().point(),page);var rows=new ArrayList<Row>();int excluded=0;var seen=new HashSet<String>();
            for(var shop:pageResult.records()){if(!seen.add(shop.id()))continue;var assessment=EbikeWorkshopClassification.assess(shop.name(),shop.type());if(!assessment.included()){excluded++;continue;}rows.add(new Row(UUID.randomUUID(),"AMAP_PUBLIC",shop.name(),shop.address(),assessment.label(),assessment.reason(),shop.point(),null,null,null,"NOT_CALCULATED"));}
            var current=order(caseId);if(current.version()!=order.version())throw ApiException.conflict("PUBLIC_WORKSHOP_EXPIRED","查询期间案件已变化；不自动重查");
            cache.put(operation,new Snapshot(operation,caseId,access().id(),order.version(),locationToken,expires(location),page,pageResult.hasMore(),excluded,List.copyOf(rows)));registry.finish(operation,true);
            audit.record(access(),AuditService.Action.MAP_RECOMMENDATION_QUERY,"PUBLIC_WORKSHOP",operation,"caseId="+caseId+"; page="+page+"; radius=10000; retained="+rows.size()+"; excluded="+excluded+"; no cooperation/capability claim");return result(operation);
        }catch(RuntimeException failure){if(operation!=null)registry.finish(operation,false);throw failure;}finally{running.remove(access().id());}
    }
    public Result manual(UUID caseId,UUID queryId,UUID parkingToken,UUID shopLocationToken,String name,String key){
        var order=order(caseId);var parking=locations.session(parkingToken,true);var target=locations.session(shopLocationToken,true);
        if(target.candidate().mock())throw ApiException.invalid("不能用Mock位置建立真实门店候选");String label=name==null?"":name.trim();if(label.length()<2||label.length()>80)throw ApiException.invalid("请输入2至80个字符的核实门店名称");
        Snapshot base=queryId==null?null:own(queryId);if(base!=null&&(!caseId.equals(base.caseId())||!parkingToken.equals(base.locationToken())))throw ApiException.invalid("门店候选不属于当前停车位置或案件");
        if(base!=null&&(base.rows().size()>=25||base.rows().stream().filter(r->r.source().equals("MANUAL")).count()>=5))throw ApiException.invalid("每次最多额外添加5家人工门店；如需更换，请明确重新查询以重建候选集合");
        enter();UUID operation=null;
        try{var begun=registry.begin(caseId,"MANUAL",key,hash(caseId+":"+queryId+":"+parkingToken+":"+shopLocationToken+":"+label));if(!begun.created())return replay(begun.operation());operation=begun.operation().id();
            var rows=new ArrayList<Row>(base==null?List.of():base.rows());
            if(rows.stream().anyMatch(r->r.name().equals(label)&&r.point().equals(target.candidate().point())))throw ApiException.conflict("PUBLIC_WORKSHOP_DUPLICATE","该名称与位置已在当前候选中，不会重复添加");
            rows.add(new Row(UUID.randomUUID(),"MANUAL",label,target.candidate().address(),"人工补充（待核实）","须核实可维修两轮电动自行车；不代表合作关系，可能不在本次10公里公开搜索范围内",target.candidate().point(),null,null,null,"NOT_CALCULATED"));
            cache.put(operation,new Snapshot(operation,caseId,access().id(),order.version(),parkingToken,earlier(expires(parking),expires(target)),base==null?1:base.page(),base!=null&&base.hasMore(),base==null?0:base.excluded(),List.copyOf(rows)));registry.finish(operation,true);
            audit.record(access(),AuditService.Action.MAP_RECOMMENDATION_QUERY,"PUBLIC_WORKSHOP_MANUAL",operation,"caseId="+caseId+"; source=MANUAL; candidateCount="+rows.size()+"; no formal shop or assignment created");return result(operation);
        }catch(RuntimeException failure){if(operation!=null)registry.finish(operation,false);throw failure;}finally{running.remove(access().id());}
    }
    private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    public Result routes(UUID queryId,List<UUID> selected,String key){
        var base=own(queryId);if(selected==null||selected.isEmpty()||selected.size()>25||new HashSet<>(selected).size()!=selected.size())throw ApiException.invalid("请明确选择1至25家要比较的候选门店");
        var ids=new HashSet<>(selected);if(base.rows().stream().filter(r->ids.contains(r.id())).count()!=ids.size())throw ApiException.invalid("选择包含其他会话的门店");
        enter();UUID operation=null;
        try{var begun=registry.begin(base.caseId(),"ROUTES",key,hash(queryId+":"+selected.stream().sorted().toList()));if(!begun.created())return replay(begun.operation());operation=begun.operation().id();
            var available=budget.availability();Integer remaining=remaining(available,"DRIVING");if(!available.providerManaged()&&(!available.callsEnabled()||remaining==null||remaining<selected.size()))throw ApiException.conflict("PUBLIC_WORKSHOP_ROUTE_BUDGET","所选门店超过剩余路线额度；请人工调整比较集合，未截断或外呼");
            var origin=locations.session(base.locationToken(),true).candidate().point();var rows=new ArrayList<Row>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60),last=0;String blocked=null;
            try(var allowance=budget.reserveDriving(selected.size())){for(var row:base.rows()){
                if(!ids.contains(row.id())){rows.add(row);continue;}Long metres=null,seconds=null;Instant calculated=null;String status="READY";
                if(blocked!=null)status=blocked;else if(System.nanoTime()>deadline)status="QUERY_TIME_BUDGET";
                else try{own(queryId);long pause=TimeUnit.MILLISECONDS.toNanos(550)-(System.nanoTime()-last);if(pause>0)LockSupport.parkNanos(pause);if(Thread.currentThread().isInterrupted())throw MapFailure.of("TIMEOUT");last=System.nanoTime();var route=provider.driving(origin,row.point(),32);if(route.mock())throw MapFailure.of("BAD_RESPONSE");metres=route.metres();seconds=route.seconds();calculated=Instant.now();}
                catch(MapFailure failure){status=failure.code();if(Set.of("MAP_CREDENTIALS","MAP_PERMISSION","MAP_QUOTA","MAP_LOCAL_BUDGET","MAP_RATE_LIMIT").contains(status))blocked=status;}
                rows.add(new Row(row.id(),row.source(),row.name(),row.address(),row.classification(),row.reason(),row.point(),metres,seconds,calculated,status));
            }}
            own(queryId);cache.put(operation,new Snapshot(operation,base.caseId(),base.actor(),base.version(),base.locationToken(),base.expires(),base.page(),base.hasMore(),base.excluded(),List.copyOf(rows)));registry.finish(operation,true);
            audit.record(access(),AuditService.Action.MAP_RECOMMENDATION_QUERY,"PUBLIC_WORKSHOP_ROUTE",operation,"caseId="+base.caseId()+"; candidates="+selected.size()+"; direction=VEHICLE_TO_SHOP; strategy=32");return result(operation);
        }catch(RuntimeException failure){if(operation!=null)registry.finish(operation,false);throw failure;}finally{running.remove(access().id());}
    }
    public Result result(UUID queryId){
        var s=own(queryId);var rows=s.rows().stream().map(r->r.calculatedAt()!=null&&!r.calculatedAt().plusSeconds(120).isAfter(Instant.now())?new Row(r.id(),r.source(),r.name(),r.address(),r.classification(),r.reason(),r.point(),null,null,r.calculatedAt(),"ROUTE_EXPIRED"):r).sorted(Comparator.comparing(Row::metres,Comparator.nullsLast(Long::compareTo)).thenComparing(Row::name).thenComparing(Row::id)).toList();
        return new Result(s.id(),provider.providerId(),"仅本次停车点周边10公里第"+s.page()+"页、供应商最多20家结果筛选后及人工补充集合；不是全省最近或完整收录",s.expires(),s.page(),s.hasMore(),s.excluded(),rows);
    }
    public CaseWorkshopRegistry.TemporaryShop temporary(UUID queryId,UUID candidateId,String name,String address,boolean verified,String key){
        if(!verified)throw ApiException.invalid("请先核实门店身份、地址以及两轮电动自行车维修能力");var s=own(queryId);var order=order(s.caseId());
        var row=s.rows().stream().filter(r->r.id().equals(candidateId)).findFirst().orElseThrow(()->ApiException.missing("Workshop candidate"));
        if(name==null||name.trim().length()<2||name.trim().length()>80||address==null||address.trim().length()<4||address.trim().length()>255)throw ApiException.invalid("请输入核实门店名称（2至80字）及地址（4至255字）");
        if(order.accidentRegionId()==null)throw ApiException.conflict("MAP_ACCIDENT_REGION_REQUIRED","正式登记/派单前须人工核对事故服务区县；不会从停车位置推断");
        String identity=hash(s.caseId()+":"+name.trim()+":"+address.trim());
        return registry.create(s.caseId(),s.version(),order.accidentRegionId(),name.trim(),address.trim(),row.source().equals("MANUAL")?"MANUAL_VERIFIED":"PUBLIC_POI_VERIFIED",identity,key);
    }
}
