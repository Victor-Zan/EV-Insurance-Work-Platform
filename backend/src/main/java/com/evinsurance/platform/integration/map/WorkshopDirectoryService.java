package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.application.CaseWorkshopRegistry;
import com.evinsurance.platform.workorder.api.*;
import com.evinsurance.platform.workorder.application.*;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Candidate browsing is read-only; only an explicit case-bound confirmation creates eligibility. */
@Service
public class WorkshopDirectoryService {
    public record Entry(long id,String name,String address,String contactPhone,long districtId,String district){}
    public record Selection(UUID token,MapProvider.AddressCandidate candidate){}
    public record Prepared(Entry shop,List<Selection> choices){}
    public record Dispatch(UUID planId,long sourceShopId,UUID shopLocationToken,boolean verified,boolean confirmPossibleDuplicate,String duplicateReason){}
    private record BoundSelection(UUID caseId,long actor,long version,Entry entry,Instant expires){}
    private final WorkshopDirectoryMapper directory;private final AutomaticDispatchService automatic;
    private final MapLocationService locations;private final WorkOrderService orders;private final WorkOrderMapper workOrders;
    private final CaseWorkshopRegistry registry;private final MapDataMapper mapData;
    private final Map<UUID,BoundSelection> selections=new ConcurrentHashMap<>();
    public WorkshopDirectoryService(WorkshopDirectoryMapper directory,AutomaticDispatchService automatic,MapLocationService locations,WorkOrderService orders,WorkOrderMapper workOrders,CaseWorkshopRegistry registry,MapDataMapper mapData){
        this.directory=directory;this.automatic=automatic;this.locations=locations;this.orders=orders;this.workOrders=workOrders;this.registry=registry;this.mapData=mapData;
    }
    private CurrentUser access(UUID caseId){
        var actor=CurrentUser.require();if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();registry.requireEnabled();
        var row=workOrders.find(caseId);if(row==null)throw ApiException.missing("Work order");WorkOrderWriteAccess.require(row,actor);
        orders.get(caseId);return actor;
    }
    public PageResponse<Entry> search(UUID caseId,String query,Long district,int page,int size){
        access(caseId);int offset=PageResponse.offset(page,size);String term=Objects.toString(query,"").trim();
        if(term.length()>100)throw ApiException.invalid("网点搜索最多100字");
        return new PageResponse<>(page,size,directory.count(term,district),directory.rows(term,district,offset,size));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void purge(){selections.values().removeIf(s->!s.expires().isAfter(Instant.now()));}
    public Prepared prepare(UUID caseId,long sourceShopId,MapProvider.Point point){
        var actor=access(caseId);var order=orders.get(caseId);
        if(!"PENDING_DISPATCH".equals(order.status()))throw ApiException.conflict("MAP_CASE_READ_ONLY","当前入口仅用于初次派单");
        var entry=directory.find(sourceShopId);if(entry==null)throw ApiException.missing("Directory workshop");
        purge();if(selections.size()>=1000)throw ApiException.conflict("MAP_SESSION_LIMIT","选店会话较多，请稍后再试");
        var choices=point==null?locations.resolve(entry.districtId(),entry.address()):locations.selectedPoint(entry.districtId(),point);
        var result=new ArrayList<Selection>();
        for(var choice:choices){
            selections.put(choice.token(),new BoundSelection(caseId,actor.id(),order.version(),entry,locations.session(choice.token(),false).expires()));
            result.add(new Selection(choice.token(),choice.candidate()));
        }
        return new Prepared(entry,result);
    }
    @Transactional public WorkOrderViews.WorkOrder dispatch(UUID caseId,Dispatch request,String key){
        var actor=access(caseId);
        if(!request.verified())throw ApiException.invalid("请明确确认门店身份、位置、维修能力和本案承接意愿");
        if(key==null||key.isBlank()||key.length()>80)throw ApiException.invalid("Idempotency-Key is required (max 80)");
        if(workOrders.lock(caseId)==null)throw ApiException.missing("Work order");
        String fingerprint=hash(caseId+":"+request.planId()+":"+request.sourceShopId()+":"+request.shopLocationToken());
        var operation=registry.begin(caseId,"INTAKE","directory-final-"+key,fingerprint);
        if(!operation.created()&&operation.operation().resultShopId()!=null)
            return orders.dispatch(caseId,new WorkOrderRequests.Dispatch(operation.operation().resultShopId(),request.confirmPossibleDuplicate(),request.duplicateReason()),key);
        if(!operation.created())throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS","本次派单仍在处理中，请刷新案件核对");
        var context=automatic.directoryContext(caseId,request.planId());var order=orders.get(caseId);
        if(order.accidentRegionId()==null||order.accidentRegionId()!=context.accidentDistrict())throw ApiException.conflict("MAP_ACCIDENT_REGION_REQUIRED","请先在上方人工核实并保存事故区县，再重新定位");
        var bound=selections.get(request.shopLocationToken());var entry=directory.lock(request.sourceShopId());
        if(bound==null||bound.actor()!=actor.id()||!bound.caseId().equals(caseId)||bound.version()!=order.version()||!bound.expires().isAfter(Instant.now())||entry==null||!entry.equals(bound.entry()))
            throw ApiException.conflict("DIRECTORY_SELECTION_EXPIRED","门店资料、案件或核对会话已变化，请重新选择核对");
        var target=locations.confirm(request.shopLocationToken());
        if(target.candidate().mock()||target.districtId()!=entry.districtId())throw ApiException.invalid("门店位置必须为对应区县的真实核对位置");
        if(context.accidentAddress()!=null)order=orders.supplementDispatchAccidentAddress(caseId,order.version(),context.accidentAddress());
        order=orders.confirmDispatchLocation(caseId,new WorkOrderRequests.DispatchLocation(order.version(),context.parkingDistrict(),context.location().address(),true));
        String name=displayName(entry.name());if(name.length()>80)name=name.substring(0,80);
        String address=target.candidate().address();
        var temporary=registry.create(caseId,order.version(),context.accidentDistrict(),name,address,"PUBLIC_POI_VERIFIED",hash("directory:"+entry.id()+":"+name+":"+address),"directory-shop-"+key);
        var result=orders.dispatch(caseId,new WorkOrderRequests.Dispatch(temporary.shopId(),request.confirmPossibleDuplicate(),request.duplicateReason()),key);
        if(mapData.dispatchSnapshot(result.currentAssignment().getId(),temporary.shopId(),temporary.name(),address,context.approximate()?"ROAD":"PRECISE")!=1)throw ApiException.conflict("MAP_ASSIGNMENT_CHANGED","派单快照保存失败，请刷新核对");
        registry.completeDirectory(operation.operation().id(),temporary.shopId());return result;
    }
    static String displayName(String value){return value.startsWith("【待核验】")?value.substring(5):value;}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
