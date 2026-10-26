package com.evinsurance.platform.integration.map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.infrastructure.*;
import com.evinsurance.platform.workorder.api.*;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomaticDispatchService {
    private final WorkOrderService orders;
    private final WorkOrderMapper workOrders;
    private final RegionMapper regions;
    private final MapLocationService locations;
    private final MapRecommendationService recommendations;
    private final MapDataMapper data;
    private final MapProvider provider;
    private final AutomaticDispatchStore store;
    private final SimulationDispatchService simulations;
    private final Map<UUID,Plan> plans=new ConcurrentHashMap<>();
    public AutomaticDispatchService(WorkOrderService orders,WorkOrderMapper workOrders,RegionMapper regions,MapLocationService locations,MapRecommendationService recommendations,MapDataMapper data,MapProvider provider,AutomaticDispatchStore store,SimulationDispatchService simulations){
        this.orders=orders;this.workOrders=workOrders;this.regions=regions;this.locations=locations;this.recommendations=recommendations;this.data=data;this.provider=provider;this.store=store;this.simulations=simulations;
    }
    public record Input(String address,boolean corrected,Long districtId,MapProvider.Point point,boolean retry,UUID requestKey){}
    public record Result(UUID id,String status,String message,String inputAddress,String source,String accidentRegion,String parkingRegion,MapProvider.AddressCandidate location,MapRecommendationService.Result recommendation,Long selectedShopId,Instant expiresAt,boolean approximate,boolean simulation){}
    private static final class Plan {
        final UUID id,caseId;final long actor,version;final String address,source;final Instant expires;
        volatile String status="RUNNING",message="正在定位并计算合作网点道路距离";
        long accidentDistrict,parkingDistrict,model;String revision;MapProvider.AddressCandidate location;MapRecommendationService.Result recommendation;
        boolean approximate,simulation,comparisonComplete;String accidentAddress;
        Plan(UUID id,UUID caseId,long actor,long version,String address,String source,Instant expires){this.id=id;this.caseId=caseId;this.actor=actor;this.version=version;this.address=address;this.source=source;this.expires=expires;}
    }
    private CurrentUser access(){var actor=CurrentUser.require();if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();return actor;}
    private WorkOrderViews.WorkOrder order(UUID id){access();var order=orders.get(id);if(!"PENDING_DISPATCH".equals(order.status()))throw ApiException.conflict("MAP_CASE_READ_ONLY","自动推荐仅用于初次派单；请使用原改派流程");return order;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void purgeExpired(){plans.values().removeIf(p->p.expires.isBefore(Instant.now()));}
    public Result prepare(UUID caseId,Input input){
        var actor=access();var order=order(caseId);
        boolean accidentEdited=!input.corrected()&&input.address()!=null&&!input.address().trim().isEmpty()&&!input.address().trim().equals(Objects.toString(order.accidentAddress(),"").trim());
        String source=input.corrected()?"CORRECTED":order.dispatchAddressConfirmed()&&order.parkingAddress()!=null?"SAVED_PARKING":"ACCIDENT";
        String address=Objects.toString(input.corrected()?input.address():"SAVED_PARKING".equals(source)?order.parkingAddress():order.accidentAddress(),"").trim();
        if(accidentEdited&&!"SAVED_PARKING".equals(source)){address=input.address().trim();source="ACCIDENT_EDITED";}
        if(address.isBlank()&&!input.corrected()){address=Objects.toString(input.address(),"").trim();source="ACCIDENT_SUPPLEMENT";}
        if(address.length()<4||address.length()>200)throw ApiException.invalid("请补充4至200字的接车地址，包含门牌或明确地标入口");
        String fingerprint=hash("map-v2:"+simulations.enabled()+":"+order.version()+":"+provider.providerId()+":"+source+":"+address+":"+(accidentEdited?input.address():"")+":"+input.districtId()+":"+input.point());
        purgeExpired();if(plans.size()>=1000)throw ApiException.conflict("MAP_SESSION_LIMIT","当前自动推荐任务较多，请稍后重试");
        var begun=store.begin(actor.id(),caseId,fingerprint,input.requestKey(),input.retry());
        if(!begun.created())return readExisting(caseId,begun.attempt());
        var p=new Plan(begun.attempt().id(),caseId,actor.id(),order.version(),address,source,begun.attempt().expires());plans.put(p.id,p);
        p.simulation=simulations.enabled();p.accidentAddress=accidentEdited?input.address().trim():null;
        try{
            // Accident eligibility always comes from original facts, never from a moved pickup point.
            p.accidentDistrict=district(p.accidentAddress!=null?p.accidentAddress:"ACCIDENT_SUPPLEMENT".equals(source)?address:order.accidentAddress(),order.accidentRegionId());
            Long parkingContext="SAVED_PARKING".equals(source)?order.parkingRegionId():input.corrected()?input.districtId():Long.valueOf(p.accidentDistrict);
            p.parkingDistrict=district(address,parkingContext);
            var expected=locations.address(p.parkingDistrict,address);
            List<MapLocationService.Choice> choices=input.point()==null?locations.resolve(p.parkingDistrict,address):locations.selectedPoint(p.parkingDistrict,input.point());
            ensureCurrent(p);
            var unique=new LinkedHashMap<MapProvider.AddressCandidate,MapLocationService.Choice>();
            for(var choice:choices)unique.putIfAbsent(choice.candidate(),choice);
            if(unique.size()!=1)throw ApiException.conflict("MAP_ADDRESS_AMBIGUOUS","地址有多个可能位置，请补充门牌、地标或校正接车点后重试");
            var choice=unique.values().iterator().next();var candidate=choice.candidate();
            boolean sameRegion=GuangdongAddress.inProvince(candidate)&&expected.city().equals(candidate.city())&&expected.district().equals(candidate.district())&&expected.districtAdcode().equals(candidate.adcode());
            boolean precise="REQUIRES_CONFIRMATION".equals(candidate.status())&&("地图选点·行政核验".equals(candidate.level())||"REQUIRES_CONFIRMATION".equals(GuangdongAddress.status(expected,candidate)));
            p.approximate=!precise&&sameRegion&&Set.of("道路","街道","路段").contains(candidate.level());
            if(!precise&&!p.approximate)throw ApiException.conflict("MAP_ADDRESS_IMPRECISE","定位存在歧义或行政信息冲突，请补充地址或校正接车点");
            if(candidate.mock())throw ApiException.conflict("MAP_MOCK_LOCATION","当前为Mock定位，请使用人工流程；不能作为真实道路推荐");
            if(candidate.address()==null||candidate.address().length()>255)throw ApiException.invalid("定位地址过长，请更正接车位置");
            p.location=candidate;
            if(p.simulation){
                p.recommendation=simulations.query(caseId,order.version(),candidate);ensureCurrent(p);p.comparisonComplete=p.recommendation.complete();
                p.status=p.approximate?"APPROXIMATE":p.recommendation.complete()?"READY":"PARTIAL";
                p.message=p.approximate?"已粗略定位到路段，距离按路段位置估算；核对所选网点后可直接派单":p.recommendation.complete()?"已比较全部模拟网点并预选道路距离最近的一家，最终由开发网点端接单":"部分模拟网点距离未知，未自动选择最近网点，请核对列表提示";
                store.finish(p.id,p.status);return view(p);
            }
            var models=data.matchingModels(order.vehicleBrand(),order.vehicleModel());
            if(models.size()!=1)throw ApiException.conflict("MAP_MODEL_MAPPING_REQUIRED","案件品牌/车型无法唯一匹配，请管理员完善目录；位置与输入已保留");
            p.model=models.getFirst().id();p.revision=data.candidateRevision(p.accidentDistrict,p.model);
            long count=data.candidateCount(p.accidentDistrict,p.model,null,true),excluded=data.candidateCount(p.accidentDistrict,p.model,null,false);
            if(count==0)throw ApiException.conflict("MAP_NO_ELIGIBLE_SHOP","没有具备匹配区域、车型和有效坐标的合作网点，请维护资料或使用人工流程");
            if(count>50)throw ApiException.conflict("MAP_CANDIDATE_LIMIT","合格候选超过50家，未截断或调用路线；请使用原流程明确缩小范围");
            boolean incomplete=excluded>50||data.candidates(p.accidentDistrict,p.model,null,false,0,50).stream().anyMatch(s->!"MODEL_NOT_MATCHED".equals(s.status()));
            locations.confirmForDispatch(choice.token(),p.approximate); // Only the current candidate session is accepted; no work-order fields are written.
            ensureCurrent(p);
            p.recommendation=recommendations.draftQuery(caseId,choice.token(),p.accidentDistrict,"auto-"+p.id);
            ensureCurrent(p);
            if(!Objects.equals(p.revision,data.candidateRevision(p.accidentDistrict,p.model)))throw ApiException.conflict("MAP_QUALIFICATION_CHANGED","计算期间网点资料已变化，请明确重新计算");
            p.comparisonComplete=p.recommendation.complete()&&!incomplete;
            p.status=p.approximate?"APPROXIMATE":p.comparisonComplete?"READY":"PARTIAL";
            p.message=p.approximate?"已按路段位置估算道路距离，可选择计算成功的网点并派单":"READY".equals(p.status)?"已完成本次合格合作网点的道路比较，请核对位置和网点后派单":"部分网点资料缺失或道路计算失败，未确定全体最近；可核对已有距离并人工选店或使用原流程";
        }catch(RuntimeException error){p.status="NEEDS_REVIEW";p.message=error instanceof ApiException||error instanceof MapFailure?error.getMessage():"定位或推荐失败，输入已保留；请明确重试，系统未自动补查";}
        store.finish(p.id,p.status);return view(p);
    }
    private Result readExisting(UUID caseId,AutomaticDispatchStore.Attempt attempt){
        var p=plans.get(attempt.id());
        if(p!=null)return result(caseId,p.id);
        return new Result(attempt.id(),"EXPIRED","先前任务已失效或服务已重启，请明确重新计算；刷新不会再次调用高德",null,null,null,null,null,null,null,attempt.expires(),false,simulations.enabled());
    }
    public Result result(UUID caseId,UUID id){
        var p=owned(caseId,id);ensureCurrent(p);
        if(p.expires.isBefore(Instant.now())||(p.recommendation!=null&&p.recommendation.expiresAt().isBefore(Instant.now())))return expired(p,"推荐已过期，请明确重新计算；未自动调用高德");
        if(!"RUNNING".equals(p.status)&&p.recommendation!=null){
            if(!p.simulation&&!Objects.equals(p.revision,data.candidateRevision(p.accidentDistrict,p.model)))return expired(p,"网点资格或位置已变化，请明确重新计算");
            try{if(p.simulation)simulations.result(p.recommendation.queryId(),1,20);else recommendations.result(p.recommendation.queryId(),1,20);}catch(ApiException changed){return expired(p,changed.getMessage());}
        }
        return view(p);
    }
    private Result expired(Plan p,String message){return new Result(p.id,"EXPIRED",message,p.address,p.source,locations.regionLabel(p.accidentDistrict),locations.regionLabel(p.parkingDistrict),p.location,null,null,p.expires,p.approximate,p.simulation);}
    private Plan owned(UUID caseId,UUID id){var actor=access();var p=plans.get(id);if(p==null||p.actor!=actor.id()||!p.caseId.equals(caseId))throw ApiException.missing("Automatic dispatch plan");return p;}
    private void ensureCurrent(Plan p){
        var order=order(p.caseId);
        if(order.version()!=p.version||!store.current(p.actor,p.caseId,p.id))throw ApiException.conflict("MAP_PLAN_CHANGED","案件或接车地址已变化，本次旧结果不可使用");
    }
    private Result view(Plan p){
        Long selected=("READY".equals(p.status)||"APPROXIMATE".equals(p.status)&&p.comparisonComplete)&&p.recommendation!=null?p.recommendation.shops().records().stream().filter(r->"READY".equals(r.status())&&r.metres()!=null).map(MapRecommendationService.Row::shopId).findFirst().orElse(null):null;
        return new Result(p.id,p.status,p.message,p.address,p.source,p.accidentDistrict==0?null:locations.regionLabel(p.accidentDistrict),p.parkingDistrict==0?null:locations.regionLabel(p.parkingDistrict),p.location,p.recommendation,selected,p.recommendation==null?p.expires:p.recommendation.expiresAt(),p.approximate,p.simulation);
    }
    @Transactional public WorkOrderViews.WorkOrder dispatch(UUID caseId,UUID planId,WorkOrderRequests.Dispatch request,String key){
        var actor=access();
        if(workOrders.command(actor.id(),"DISPATCH",key)!=null)return orders.dispatch(caseId,request,key);
        if(workOrders.lock(caseId)==null)throw ApiException.missing("Work order");
        var p=owned(caseId,planId);
        if(!store.lockCurrent(actor.id(),caseId,planId))throw ApiException.conflict("MAP_PLAN_CHANGED","接车方案已变化");
        ensureCurrent(p);
        var current=result(caseId,planId);
        if(!Set.of("READY","PARTIAL","APPROXIMATE").contains(current.status())||p.recommendation==null||p.location==null)throw ApiException.conflict("MAP_PLAN_NOT_READY","推荐尚未可用或已过期，请核对提示");
        var result=p.simulation?simulations.result(p.recommendation.queryId(),1,50):recommendations.result(p.recommendation.queryId(),1,50);
        var selected=result.shops().records().stream().filter(r->r.shopId()==request.shopId()&&"READY".equals(r.status())&&r.metres()!=null).findFirst().orElseThrow(()->ApiException.invalid("请选择本次已有有效道路距离的网点"));
        var done=p.simulation?simulations.dispatch(p.recommendation.queryId(),request,key,p.accidentDistrict,p.parkingDistrict,p.location.address(),p.accidentAddress,p.approximate):recommendations.dispatchDraft(p.recommendation.queryId(),request,key,p.accidentDistrict,p.parkingDistrict,p.location.address(),p.accidentAddress);
        if(data.dispatchSnapshot(done.currentAssignment().getId(),selected.shopId(),selected.name(),selected.address(),p.approximate?"ROAD":"PRECISE")!=1)throw ApiException.conflict("MAP_ASSIGNMENT_CHANGED","派单快照写入失败，请刷新核对");
        return done;
    }
    private long district(String detail,Long context){
        var rows=regions.selectList(new QueryWrapper<RegionEntity>().eq("enabled",true).orderByAsc("id").last("LIMIT 5001"));
        if(rows.size()>5000)throw ApiException.conflict("MAP_REGION_DATA","行政资料超过安全读取上限，请使用人工流程");
        var byId=new HashMap<Long,RegionEntity>();for(var r:rows)byId.put(r.getId(),r);
        String text=Objects.toString(detail,"");var parent=context==null?null:byId.get(context);
        var matches=new ArrayList<RegionEntity>();
        for(var r:rows){
            if(r.getLevel()!=3)continue;var city=byId.get(r.getParentId());var province=city==null?null:byId.get(city.getParentId());
            if(city==null||province==null||city.getLevel()!=2||province.getLevel()!=1||!"440000".equals(province.getCode())||!"广东省".equals(province.getName()))continue;
            if(text.contains(r.getName())&&(parent==null||parent.getId().equals(r.getId())||parent.getId().equals(city.getId())||parent.getId().equals(province.getId())))matches.add(r);
        }
        // A different explicit district must never be overwritten by saved context.
        if(parent!=null&&parent.getLevel()==3){
            var parentCity=byId.get(parent.getParentId());
            if(parentCity==null||rows.stream().anyMatch(r->r.getLevel()==2&&text.contains(r.getName())&&!r.getId().equals(parentCity.getId())))throw ApiException.conflict("MAP_REGION_CONFLICT","地址城市与已保存区域冲突，请核实更正");
            if(rows.stream().anyMatch(r->r.getLevel()==3&&text.contains(r.getName())&&!r.getId().equals(parent.getId())))throw ApiException.conflict("MAP_REGION_CONFLICT","地址区县与已保存事故事实/接车区域冲突，请核实更正");
            locations.address(parent.getId(),text.length()<4?"区域资料核验":text);return parent.getId();
        }
        if(matches.size()!=1)throw ApiException.conflict("MAP_REGION_REQUIRED","无法唯一匹配广东省市区，请补充明确区县；东莞/中山等无区县资料时使用人工流程");
        var matchedCity=byId.get(matches.getFirst().getParentId());
        if(rows.stream().anyMatch(r->r.getLevel()==2&&text.contains(r.getName())&&!r.getId().equals(matchedCity.getId())))throw ApiException.conflict("MAP_REGION_CONFLICT","地址城市与区县冲突，请核实更正");
        return matches.getFirst().getId();
    }
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
