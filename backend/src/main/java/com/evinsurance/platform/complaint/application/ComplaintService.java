package com.evinsurance.platform.complaint.application;

import com.evinsurance.platform.complaint.api.ComplaintRequests;
import com.evinsurance.platform.complaint.infrastructure.ComplaintMapper;
import com.evinsurance.platform.document.application.*;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.document.infrastructure.FileMapper;
import com.evinsurance.platform.repair.infrastructure.RepairMapper;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.audit.application.AuditService;
import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplaintService {
    private final ComplaintMapper mapper;private final FileAccess access;private final FileMapper files;private final WorkOrderMapper orders;private final RepairMapper repairs;private final AuditService audit;private final ObjectMapper json;
    public ComplaintService(ComplaintMapper mapper,FileAccess access,FileMapper files,WorkOrderMapper orders,RepairMapper repairs,AuditService audit,ObjectMapper json){this.mapper=mapper;this.access=access;this.files=files;this.orders=orders;this.repairs=repairs;this.audit=audit;this.json=json;}
    private String text(String value,String field){if(value==null||value.isBlank()||value.length()>2000)throw ApiException.invalid(field+" must be 1..2000 characters");return value.trim();}
    private Map<String,Object> visible(UUID id,boolean lock){
        var row=lock?mapper.lock(id):mapper.find(id);if(row==null)throw ApiException.missing("Complaint");UUID caseId=(UUID)row.get("work_order_id");access.order(caseId,false);var a=CurrentUser.require();
        if(access.staff(a))return row;
        if(a.roles().contains(Role.OWNER)&&((Number)row.get("owner_id")).longValue()==a.id())return row;
        var assignment=orders.activeAssignment(caseId);
        if(a.roles().contains(Role.REPAIR_SHOP)&&Objects.equals(a.shopId(),((Number)row.get("shop_id")).longValue())&&assignment!=null&&assignment.getAssignmentVersion()==((Number)row.get("assignment_version")).intValue())return row;
        throw ApiException.denied();
    }
    private JsonNode view(Map<String,Object> row){
        var out=json.createObjectNode().put("id",row.get("id").toString()).put("caseId",row.get("work_order_id").toString()).put("status",(String)row.get("status")).put("version",((Number)row.get("version")).intValue()).put("description",(String)row.get("description")).put("assignmentVersion",((Number)row.get("assignment_version")).intValue()).put("createdAt",row.get("created_at").toString());
        out.set("photos",json.readTree((String)row.get("photo_snapshot")));return out;
    }
    public JsonNode get(UUID id){return view(visible(id,false));}
    public PageResponse<JsonNode> list(UUID caseId,int page,int size){
        var order=access.order(caseId,false);if("DRAFT".equals(order.getStatus()))throw ApiException.denied();int offset=PageResponse.offset(page,size);var a=CurrentUser.require();Long shop=null;Integer assignment=null;
        if(!access.staff(a)&&a.roles().contains(Role.REPAIR_SHOP)){shop=a.shopId();assignment=access.assignment(caseId);}
        return new PageResponse<>(page,size,mapper.count(caseId,shop,assignment),mapper.list(caseId,shop,assignment,offset,size).stream().map(this::view).toList());
    }
    public PageResponse<JsonNode> history(UUID id,int page,int size){
        visible(id,false);int offset=PageResponse.offset(page,size);boolean staff=access.staff(CurrentUser.require());
        var rows=mapper.history(id,offset,size).stream().map(row->{var out=json.createObjectNode().put("id",((Number)row.get("id")).longValue()).put("kind",(String)row.get("kind")).put("publicNote",(String)row.get("public_note")).put("createdAt",row.get("created_at").toString());if(staff)out.set("internalNote",json.valueToTree(row.get("internal_note")));return (JsonNode)out;}).toList();
        return new PageResponse<>(page,size,mapper.historyCount(id),rows);
    }
    private JsonNode command(UUID id,String op,String key,Object request,Supplier<JsonNode> action){
        FileService.checkKey(key);var actor=CurrentUser.require();String hash=FileService.hash((id+":"+json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));repairs.lockCommand("complaint:"+actor.id()+":"+op+":"+key);
        var prior=mapper.command(actor.id(),op,key);if(prior!=null){if(!hash.equals(prior.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Key belongs to another complaint request");return json.readTree((String)prior.get("response_json"));}
        var result=action.get();mapper.commandSave(actor.id(),op,key,hash,json.writeValueAsString(result));return result;
    }
    @Transactional public JsonNode create(UUID caseId,ComplaintRequests.Create request,String key){
        var order=access.order(caseId,true);var actor=CurrentUser.require();if(!actor.roles().contains(Role.OWNER)||!Objects.equals(order.getOwnerUserId(),actor.id()))throw ApiException.denied();
        return command(caseId,"CREATE",key,request,()->{
            if(Set.of("CANCELLED","CLOSED").contains(order.getStatus())||!repairs.started(caseId))throw ApiException.conflict("COMPLAINT_STATE_CONFLICT","Create complaints after a recorded repair start, excluding cancelled cases");
            var assignment=orders.activeAssignment(caseId);if(assignment==null)throw ApiException.conflict("COMPLAINT_ASSIGNMENT_MISSING","An assigned repair shop is required");
            var ids=request.photoIds()==null?List.<UUID>of():request.photoIds();if(ids.size()>20||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw ApiException.invalid("Select at most 20 distinct photo IDs");
            var photos=new ArrayList<Map<String,Object>>();for(var fileId:ids){var file=files.find(fileId);if(file==null||!caseId.equals(file.workOrderId())||!FileCategory.valueOf(file.category()).photo())throw ApiException.invalid("Use authorized case photos only");access.readable(file);photos.add(Map.of("id",file.id(),"version",file.versionNo(),"category",file.category(),"contentType",file.contentType()));}
            UUID id=UUID.randomUUID();mapper.create(id,caseId,actor.id(),assignment.getShopId(),assignment.getAssignmentVersion(),text(request.description(),"Complaint description"),json.writeValueAsString(photos));audit.record(actor,AuditService.Action.COMPLAINT_CREATE,"COMPLAINT",id,"Independent complaint submitted; work order and rating unchanged");return view(mapper.find(id));
        });
    }
    private void expected(Map<String,Object> row,Integer version){if(version==null||version!=((Number)row.get("version")).intValue())throw ApiException.conflict("COMPLAINT_VERSION_CONFLICT","Refresh complaint version");}
    @Transactional public JsonNode handle(UUID id,ComplaintRequests.Handle request,String key){
        CurrentUser.require().requireAdmin();var row=visible(id,true);return command(id,"HANDLE",key,request,()->{
            expected(row,request.expectedVersion());String next=switch((String)row.get("status")){case "SUBMITTED"->"PROCESSING";case "PROCESSING"->"RESOLVED";case "RESOLVED"->"CLOSED";default->"";};
            if(next.isEmpty()||!next.equals(request.status()))throw ApiException.conflict("COMPLAINT_STATE_CONFLICT","Follow SUBMITTED -> PROCESSING -> RESOLVED -> CLOSED");
            String internal=request.internalNote();if(internal!=null&&internal.length()>2000)throw ApiException.invalid("Internal note is limited to 2000 characters");
            mapper.event(id,next,text(request.publicNote(),"Public processing note"),internal,CurrentUser.require().id());
            if(mapper.advance(id,request.expectedVersion(),next)!=1)throw ApiException.conflict("COMPLAINT_VERSION_CONFLICT","Complaint changed concurrently");
            audit.record(CurrentUser.require(),AuditService.Action.COMPLAINT_HANDLE,"COMPLAINT",id,"Complaint status="+next+"; internal note withheld from external output");return view(mapper.find(id));
        });
    }
    @Transactional public JsonNode correct(UUID id,ComplaintRequests.Correct request,String key){
        if(!CurrentUser.require().roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();var row=visible(id,true);return command(id,"CORRECT",key,request,()->{
            expected(row,request.expectedVersion());mapper.event(id,"CORRECTION",text(request.publicNote(),"Correction note"),null,CurrentUser.require().id());
            if(mapper.advance(id,request.expectedVersion(),(String)row.get("status"))!=1)throw ApiException.conflict("COMPLAINT_VERSION_CONFLICT","Complaint changed concurrently");
            audit.record(CurrentUser.require(),AuditService.Action.COMPLAINT_CORRECT,"COMPLAINT",id,"Customer service correction appended; owner original retained");return view(mapper.find(id));
        });
    }
}
