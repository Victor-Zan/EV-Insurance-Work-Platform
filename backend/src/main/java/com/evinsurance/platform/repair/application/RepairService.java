package com.evinsurance.platform.repair.application;

import com.evinsurance.platform.repair.api.RepairRequests;
import com.evinsurance.platform.repair.domain.RepairPhotoPolicy;
import com.evinsurance.platform.repair.infrastructure.RepairMapper;
import com.evinsurance.platform.document.infrastructure.*;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.quotation.infrastructure.QuotationMapper;
import com.evinsurance.platform.quotation.domain.RepairAuthorizationGate;
import com.evinsurance.platform.workorder.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.audit.application.AuditService;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import tools.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepairService {
    private final RepairMapper mapper;
    private final WorkOrderMapper orders;
    private final FileMapper files;
    private final QuotationMapper quotations;
    private final AuditService audit;
    private final ObjectMapper json;
    public RepairService(RepairMapper mapper,WorkOrderMapper orders,FileMapper files,QuotationMapper quotations,AuditService audit,ObjectMapper json){
        this.mapper=mapper;this.orders=orders;this.files=files;this.quotations=quotations;this.audit=audit;this.json=json;
    }
    private boolean staff(CurrentUser a){return a.roles().contains(Role.ADMIN)||a.roles().contains(Role.CUSTOMER_SERVICE);}
    private WorkOrderEntity visible(UUID id,boolean lock){
        var row=lock?orders.lock(id):orders.find(id);if(row==null)throw ApiException.missing("Work order");
        var a=CurrentUser.require();if("DRAFT".equals(row.getStatus()))throw ApiException.denied();
        if(staff(a) || (a.roles().contains(Role.OWNER)&&Objects.equals(row.getOwnerUserId(),a.id()))
            || (a.roles().contains(Role.REPAIR_SHOP)&&a.shopId()!=null&&a.shopId().equals(row.getShopId())))return row;
        throw ApiException.denied();
    }
    private WorkOrderRows.Assignment shop(WorkOrderEntity order,Integer expected){
        var a=CurrentUser.require();var assignment=orders.activeAssignment(order.getId());
        if((!a.roles().contains(Role.CUSTOMER_SERVICE)&&(!a.roles().contains(Role.REPAIR_SHOP)||a.shopId()==null||!a.shopId().equals(order.getShopId())))
            ||assignment==null||!Objects.equals(order.getShopId(),assignment.getShopId())||!"ACCEPTED".equals(assignment.getStatus())
            ||!Objects.equals(expected,assignment.getAssignmentVersion()))throw ApiException.denied();
        return assignment;
    }
    private int version(UUID id){var v=mapper.version(id);return v==null?0:v;}
    private void editable(WorkOrderEntity order,Integer expected){
        if(!"REPAIRING".equals(order.getStatus()))throw ApiException.conflict("REPAIR_STATE_CONFLICT","Progress and completion require REPAIRING");
        var c=quotations.context(order.getId());
        if(c==null || !mapper.started(order.getId()) || !RepairAuthorizationGate.allowed(new RepairAuthorizationGate.Evidence(
            files.activeCount(order.getId(),"LOSS_ASSESSMENT")>0,c.assessmentId!=null,c.insurerConfirmed,c.serviceConfirmed)))
            throw ApiException.conflict("REPAIR_GATE_INCOMPLETE","A valid recorded repair start and all four prerequisites are required");
        if(expected==null||expected!=version(order.getId()))throw ApiException.conflict("REPAIR_VERSION_CONFLICT","Refresh the repair version before submitting");
    }
    private List<FileRow> photos(UUID id,WorkOrderRows.Assignment a,List<UUID> ids,String category,boolean required){
        if(ids==null)ids=List.of();
        if((required&&ids.isEmpty())||ids.size()>20||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())
            throw ApiException.invalid("Select distinct photo IDs, at most 20; completion requires at least one");
        var result=new ArrayList<FileRow>();
        for(UUID fileId:ids){var file=files.find(fileId);RepairPhotoPolicy.require(file,id,a.getShopId(),a.getAssignmentVersion(),category,
            file!=null&&mapper.shopUploaded(fileId,file.uploadedBy()));result.add(file);}
        return result;
    }
    private String snapshot(List<FileRow> rows){return json.writeValueAsString(rows.stream().map(f->Map.of(
        "id",f.id(),"version",f.versionNo(),"category",f.category(),"contentType",f.contentType())).toList());}
    private JsonNode entry(Map<String,Object> row){
        if(row==null)return json.nullNode();
        var out=json.createObjectNode();out.put("id",row.get("id").toString());
        out.put("assignmentVersion",((Number)row.get("assignment_version")).intValue());
        out.put("createdAt",row.get("created_at").toString());
        if(row.containsKey("note"))out.put("note",(String)row.get("note"));
        out.set("photos",json.readTree((String)row.get("photo_snapshot")));return out;
    }
    private JsonNode view(WorkOrderEntity row){
        var out=json.createObjectNode();out.put("caseId",row.getId().toString()).put("status",row.getStatus()).put("version",version(row.getId()));
        var assignment=orders.activeAssignment(row.getId());if(assignment!=null)out.put("assignmentVersion",assignment.getAssignmentVersion());
        var completion=mapper.completion(row.getId());
        var actor=CurrentUser.require();
        if(completion!=null&&actor.roles().contains(Role.REPAIR_SHOP)&&!staff(actor)
            &&(assignment==null||!Objects.equals(((Number)completion.get("assignment_version")).intValue(),assignment.getAssignmentVersion())))completion=null;
        out.set("completion",entry(completion));
        var receipt=mapper.receiptLatest(row.getId());var receiptView=json.createObjectNode();
        if(receipt!=null){receiptView.put("id",((Number)receipt.get("id")).longValue()).put("confirmedBy",(String)receipt.get("actor_role")).put("createdAt",receipt.get("created_at").toString());}
        if(receipt!=null)receiptView.put("withdrawn",mapper.withdrawn(((Number)receipt.get("id")).longValue()));
        out.set("receipt",receipt==null?json.nullNode():receiptView);out.set("review",reviewView(row,receipt));return out;
    }
    public JsonNode get(UUID id){return view(visible(id,false));}
    public PageResponse<JsonNode> history(UUID id,int page,int size){
        visible(id,false);int offset=PageResponse.offset(page,size);var a=CurrentUser.require();
        Long shop=null;Integer assignment=null;
        if(a.roles().contains(Role.REPAIR_SHOP)&&!staff(a)){var current=orders.activeAssignment(id);if(current==null)throw ApiException.denied();shop=a.shopId();assignment=current.getAssignmentVersion();}
        return new PageResponse<>(page,size,mapper.count(id,shop,assignment),mapper.history(id,shop,assignment,size,offset).stream().map(this::entry).toList());
    }
    private JsonNode command(UUID id,String op,String key,Object request,Supplier<JsonNode> action){
        FileService.checkKey(key);var actor=CurrentUser.require();
        String hash=FileService.hash((id+":"+json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
        mapper.lockCommand(actor.id()+":"+op+":"+key);
        var prior=mapper.command(actor.id(),op,key);
        if(prior!=null){
            if(!hash.equals(prior.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Key belongs to a different repair request");
            var response=json.readTree((String)prior.get("response_json"));
            // Role changes invalidate old JWTs, but a freshly authenticated actor may replay an old key.
            // Filter the stored snapshot for the current role instead of returning a staff-only reason.
            if(!staff(actor)&&response.path("review").path("current") instanceof tools.jackson.databind.node.ObjectNode current)current.remove("reason");
            return response;
        }
        var response=action.get();mapper.commandSave(actor.id(),op,key,hash,json.writeValueAsString(response));return response;
    }
    private void advance(UUID id,int version){mapper.ensure(id);if(mapper.advance(id,version)!=1)throw ApiException.conflict("REPAIR_VERSION_CONFLICT","Repair context changed concurrently");}
    @Transactional public JsonNode progress(UUID id,RepairRequests.Progress request,String key){
        var order=visible(id,true);var a=shop(order,request.assignmentVersion());
        return command(id,"PROGRESS",key,request,()->{
            editable(order,request.expectedVersion());String note=request.note();
            if(note==null||note.isBlank()||note.length()>2000)throw ApiException.invalid("Progress note must contain 1..2000 characters");
            var photos=photos(id,a,request.photoIds(),"PROGRESS_PHOTO",false);UUID record=UUID.randomUUID();
            mapper.progress(record,id,a.getShopId(),a.getAssignmentVersion(),note.trim(),snapshot(photos),CurrentUser.require().id());
            advance(id,request.expectedVersion());audit.record(CurrentUser.require(),AuditService.Action.REPAIR_PROGRESS,"WORK_ORDER",id,"Progress appended; record="+record);
            return view(order);
        });
    }
    @Transactional public JsonNode complete(UUID id,RepairRequests.Complete request,String key){
        var order=visible(id,true);var a=shop(order,request.assignmentVersion());
        return command(id,"COMPLETE",key,request,()->{
            editable(order,request.expectedVersion());var photos=photos(id,a,request.photoIds(),"COMPLETION_PHOTO",true);UUID record=UUID.randomUUID();
            var actor=CurrentUser.require();mapper.complete(record,id,a.getShopId(),a.getAssignmentVersion(),snapshot(photos),actor.id());
            for(var f:photos)mapper.evidence(record,f.id(),f.versionNo());
            var now=Instant.now();String next="WAITING_OWNER_CONFIRMATION";
            if(orders.transition(id,order.getVersion(),order.getStatus(),next,order.getShopId(),false,actor.id(),now)!=1)
                throw ApiException.conflict("WORK_ORDER_STATE_CONFLICT","Work order changed concurrently");
            orders.insertHistory(id,order.getStatus(),next,"REPAIR_COMPLETE",null,actor.id(),actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")),a.getAssignmentVersion(),TraceContext.currentTraceId()==null?UUID.randomUUID().toString():TraceContext.currentTraceId(),now);
            order.setStatus(next);order.setVersion(order.getVersion()+1);advance(id,request.expectedVersion());
            audit.record(actor,AuditService.Action.REPAIR_COMPLETE,"WORK_ORDER",id,"Completion submitted; evidence="+photos.size()+"; record="+record);
            return view(order);
        });
    }
    @Transactional public JsonNode receive(UUID id,RepairRequests.Receive request,String key){
        var order=visible(id,true);var actor=CurrentUser.require();
        boolean customerService=actor.roles().contains(Role.CUSTOMER_SERVICE);
        if(!customerService&&!(actor.roles().contains(Role.OWNER)&&Objects.equals(order.getOwnerUserId(),actor.id())))throw ApiException.denied();
        return command(id,"RECEIVE",key,request,()->{
            if(!"WAITING_OWNER_CONFIRMATION".equals(order.getStatus()))throw ApiException.conflict("RECEIPT_STATE_CONFLICT","Confirm receipt only after completion submission");
            if(request.expectedVersion()==null||request.expectedVersion()!=version(id))throw ApiException.conflict("REPAIR_VERSION_CONFLICT","Refresh before confirming receipt");
            var completion=mapper.completion(id);if(completion==null)throw ApiException.conflict("COMPLETION_EVIDENCE_MISSING","A recorded completion with frozen photo evidence is required");
            mapper.receipt(id,(UUID)completion.get("id"),actor.id(),customerService?"CUSTOMER_SERVICE":"OWNER");
            var now=Instant.now();
            if(orders.transition(id,order.getVersion(),order.getStatus(),"COMPLETED",order.getShopId(),false,actor.id(),now)!=1)throw ApiException.conflict("WORK_ORDER_STATE_CONFLICT","Work order changed concurrently");
            var assignment=orders.activeAssignment(id);
            orders.insertHistory(id,order.getStatus(),"COMPLETED","REPAIR_RECEIPT_CONFIRM",null,actor.id(),actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")),assignment==null?null:assignment.getAssignmentVersion(),TraceContext.currentTraceId()==null?UUID.randomUUID().toString():TraceContext.currentTraceId(),now);
            order.setStatus("COMPLETED");order.setVersion(order.getVersion()+1);advance(id,request.expectedVersion());
            audit.record(actor,AuditService.Action.REPAIR_RECEIPT_CONFIRM,"WORK_ORDER",id,"Receipt confirmed; work order completed; source="+(customerService?"CUSTOMER_SERVICE":"OWNER"));
            return view(order);
        });
    }
    private String requiredText(String text,String field){if(text==null||text.isBlank()||text.length()>2000)throw ApiException.invalid(field+" must be 1..2000 characters");return text.trim();}
    private void expected(UUID id,Integer expected){if(expected==null||expected!=version(id))throw ApiException.conflict("REPAIR_VERSION_CONFLICT","Refresh the current repair version");}
    @Transactional public JsonNode withdraw(UUID id,RepairRequests.Withdraw request,String key){
        if(!CurrentUser.require().roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
        var order=visible(id,true);
        return command(id,"WITHDRAW",key,request,()->{
            expected(id,request.expectedVersion());var receipt=mapper.receiptLatest(id);
            if(!"COMPLETED".equals(order.getStatus())||receipt==null||mapper.withdrawn(((Number)receipt.get("id")).longValue()))throw ApiException.conflict("RECEIPT_STATE_CONFLICT","Withdraw a current confirmed receipt only");
            var actor=CurrentUser.require();String reason=requiredText(request.reason(),"Withdrawal reason");
            mapper.withdraw(((Number)receipt.get("id")).longValue(),reason,actor.id());var now=Instant.now();
            if(orders.transition(id,order.getVersion(),"COMPLETED","WAITING_OWNER_CONFIRMATION",order.getShopId(),false,actor.id(),now)!=1)throw ApiException.conflict("WORK_ORDER_STATE_CONFLICT","Work order changed concurrently");
            var assignment=orders.activeAssignment(id);
            orders.insertHistory(id,"COMPLETED","WAITING_OWNER_CONFIRMATION","REPAIR_RECEIPT_WITHDRAW",reason,actor.id(),"CUSTOMER_SERVICE",assignment==null?null:assignment.getAssignmentVersion(),TraceContext.currentTraceId()==null?UUID.randomUUID().toString():TraceContext.currentTraceId(),now);
            order.setStatus("WAITING_OWNER_CONFIRMATION");order.setVersion(order.getVersion()+1);advance(id,request.expectedVersion());
            audit.record(actor,AuditService.Action.REPAIR_RECEIPT_WITHDRAW,"WORK_ORDER",id,"Receipt withdrawn; original completion and review retained");return view(order);
        });
    }
    private JsonNode reviewEntry(Map<String,Object> row,boolean correction){
        if(row==null)return json.nullNode();var out=json.createObjectNode();out.put("id",row.get("id").toString()).put("source",correction?"CUSTOMER_SERVICE_CORRECTION":"OWNER").put("createdAt",row.get("created_at").toString());
        out.set("text",json.valueToTree(row.get("review_text")));out.set("score",json.valueToTree(row.get("score")));
        if(correction&&staff(CurrentUser.require()))out.put("reason",(String)row.get("reason"));return out;
    }
    private JsonNode reviewView(WorkOrderEntity order,Map<String,Object> receipt){
        var original=mapper.review(order.getId());if(original==null)return json.nullNode();var latest=mapper.revision((UUID)original.get("id"));var current=latest==null?original:latest;
        boolean active="COMPLETED".equals(order.getStatus())&&receipt!=null&&!mapper.withdrawn(((Number)receipt.get("id")).longValue())&&Objects.equals(((Number)current.get("receipt_id")).longValue(),((Number)receipt.get("id")).longValue());
        return json.createObjectNode().put("eligibleForCurrentRating",active).set("original",reviewEntry(original,false)).set("current",reviewEntry(current,latest!=null));
    }
    @Transactional public JsonNode review(UUID id,RepairRequests.Review request,String key,boolean correction){
        var order=visible(id,true);var actor=CurrentUser.require();
        if(correction){if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();}
        else if(!actor.roles().contains(Role.OWNER)||!Objects.equals(order.getOwnerUserId(),actor.id()))throw ApiException.denied();
        return command(id,correction?"REVIEW_CORRECT":"REVIEW",key,request,()->{
            expected(id,request.expectedVersion());var receipt=mapper.receiptLatest(id);
            if(!"COMPLETED".equals(order.getStatus())||receipt==null||mapper.withdrawn(((Number)receipt.get("id")).longValue()))throw ApiException.conflict("REVIEW_STATE_CONFLICT","Review requires a current confirmed receipt");
            String text=request.text();if(text!=null){if(text.length()>2000)throw ApiException.invalid("Review text is limited to 2000 characters");text=text.trim();if(text.isEmpty())text=null;}
            Integer score=null;var input=request.score();if(input!=null&&!input.isNull()){if(!input.isIntegralNumber()||!input.canConvertToInt()||input.asInt()<1||input.asInt()>5)throw ApiException.invalid("Score must be an integer from 1 to 5");score=input.asInt();}
            if(score==null&&text==null)throw ApiException.invalid("Provide review text or score, or skip the review entirely");
            var original=mapper.review(id);long receiptId=((Number)receipt.get("id")).longValue();
            if(correction){if(original==null)throw ApiException.conflict("REVIEW_MISSING","Customer service may correct an existing owner review only");mapper.revisionSave((UUID)original.get("id"),receiptId,text,score,requiredText(request.reason(),"Correction reason"),actor.id());}
            else {if(original!=null)throw ApiException.conflict("REVIEW_ALREADY_SUBMITTED","Owner review is submitted once and cannot be changed");mapper.reviewSave(UUID.randomUUID(),id,receiptId,actor.id(),text,score);}
            advance(id,request.expectedVersion());audit.record(actor,correction?AuditService.Action.REPAIR_REVIEW_CORRECT:AuditService.Action.REPAIR_REVIEW,"WORK_ORDER",id,correction?"Review correction appended; original retained":"Optional owner review submitted");return view(order);
        });
    }
    public PageResponse<JsonNode> reviewHistory(UUID id,int page,int size){
        visible(id,false);int offset=PageResponse.offset(page,size);var original=mapper.review(id);if(original==null)return new PageResponse<>(page,size,0,List.of());UUID reviewId=(UUID)original.get("id");
        return new PageResponse<>(page,size,mapper.revisionCount(reviewId),mapper.revisions(reviewId,offset,size).stream().map(x->reviewEntry(x,true)).toList());
    }
}
