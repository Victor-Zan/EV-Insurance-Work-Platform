package com.evinsurance.platform.funds.application;

import com.evinsurance.platform.funds.api.FundsRequests;
import com.evinsurance.platform.funds.domain.FundsMoney;
import com.evinsurance.platform.funds.infrastructure.FundsMapper;
import com.evinsurance.platform.document.application.*;
import com.evinsurance.platform.workorder.infrastructure.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundsService {
    private final FundsMapper mapper;private final FileAccess access;private final WorkOrderMapper orders;private final AuditService audit;private final ObjectMapper json;
    public FundsService(FundsMapper mapper,FileAccess access,WorkOrderMapper orders,AuditService audit,ObjectMapper json){this.mapper=mapper;this.access=access;this.orders=orders;this.audit=audit;this.json=json;}
    public void writer(){access.customerService(CurrentUser.require());}
    private WorkOrderEntity visible(UUID id,boolean lock){
        var actor=CurrentUser.require();if(actor.roles().contains(Role.OWNER)&&!access.staff(actor))throw ApiException.denied();
        var row=access.order(id,lock);if("DRAFT".equals(row.getStatus()))throw ApiException.denied();
        if(!access.staff(actor)){var a=orders.activeAssignment(id);if(a==null||!Objects.equals(a.getShopId(),actor.shopId()))throw ApiException.denied();}return row;
    }
    public int version(UUID id){var v=mapper.version(id);return v==null?0:v;}
    private void expected(UUID id,Integer v){if(v==null||v!=version(id))throw ApiException.conflict("FUNDS_VERSION_CONFLICT","Refresh the funds version");}
    private void advance(UUID id){int v=version(id);mapper.ensure(id);if(mapper.advance(id,v)!=1)throw ApiException.conflict("FUNDS_VERSION_CONFLICT","Funds changed concurrently");}
    private String direction(String value){if(!Set.of("RECEIVE","PAY").contains(Objects.toString(value,"")))throw ApiException.invalid("Direction must be RECEIVE or PAY");return value;}
    private String text(String value,String name,int limit,boolean required){if(value==null||value.isBlank()){if(required)throw ApiException.invalid(name+" is required");return "";}if(value.length()>limit)throw ApiException.invalid(name+" is too long");return value.trim();}
    private BigDecimal target(UUID id,String dir){var row=mapper.target(id,dir);return row==null?null:(BigDecimal)row.get("amount");}
    private BigDecimal net(UUID id,String dir){return mapper.net(id,dir,null,null).setScale(2);}
    private ObjectNode directionView(BigDecimal target,BigDecimal net){var out=json.createObjectNode().put("net",net.toPlainString()).put("status",FundsMoney.status(target,net));if(target==null)out.putNull("target");else out.put("target",target.toPlainString());return out;}
    private JsonNode view(WorkOrderEntity order){
        UUID id=order.getId();var out=json.createObjectNode().put("caseId",id.toString()).put("currency","CNY").put("unit","yuan").put("version",version(id));var actor=CurrentUser.require();
        if(access.staff(actor))out.set("receivable",directionView(target(id,"RECEIVE"),net(id,"RECEIVE")));
        var payable=mapper.target(id,"PAY");BigDecimal amount=payable==null?null:(BigDecimal)payable.get("amount");
        Long shop=null;Integer assignment=null;if(!access.staff(actor)){var current=orders.activeAssignment(id);shop=actor.shopId();assignment=current.getAssignmentVersion();if(payable!=null&&(!Objects.equals(shop,((Number)payable.get("shop_id")).longValue())||!Objects.equals(assignment,((Number)payable.get("assignment_version")).intValue())))amount=null;}
        out.set("payable",directionView(amount,mapper.net(id,"PAY",shop,assignment).setScale(2)));return out;
    }
    public JsonNode get(UUID id){return view(visible(id,false));}
    private JsonNode command(UUID id,String op,String key,Object body,Supplier<JsonNode> action){
        FileService.checkKey(key);var actor=CurrentUser.require();String hash=FileService.hash((id+":"+json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));mapper.commandLock("funds-command:"+actor.id()+":"+op+":"+key);
        var prior=mapper.command(actor.id(),op,key);if(prior!=null){if(!hash.equals(prior.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Key belongs to another funds request");return json.readTree((String)prior.get("response_json"));}
        var result=action.get();mapper.commandSave(actor.id(),op,key,hash,json.writeValueAsString(result));return result;
    }
    @Transactional public JsonNode setTarget(UUID id,FundsRequests.Target request,String key){
        writer();var order=visible(id,true);return command(id,"TARGET",key,request,()->{
            expected(id,request.expectedVersion());if("CANCELLED".equals(order.getStatus()))throw ApiException.conflict("FUNDS_READ_ONLY","Cancelled targets are read-only");String dir=direction(request.direction());
            BigDecimal amount=request.amount()==null||request.amount().isNull()?null:FundsMoney.parse(request.amount(),false);BigDecimal net=net(id,dir);
            if((amount==null&&(dir.equals("RECEIVE")||net.signum()!=0))||(amount!=null&&amount.compareTo(net)<0))throw ApiException.conflict("FUNDS_TARGET_CONFLICT","Reverse historical entries before reducing below net or clearing a paid target");
            var assignment=orders.activeAssignment(id);if(dir.equals("PAY")&&(assignment==null||!"ACCEPTED".equals(assignment.getStatus())))throw ApiException.conflict("FUNDS_ASSIGNMENT_REQUIRED","Payable requires a current accepted shop");
            mapper.targetSave(id,dir,amount,dir.equals("PAY")?assignment.getShopId():null,dir.equals("PAY")?assignment.getAssignmentVersion():null,text(request.reason(),"Target version reason",2000,true),CurrentUser.require().id());advance(id);
            audit.record(CurrentUser.require(),AuditService.Action.FUNDS_TARGET,"WORK_ORDER",id,"Manual "+dir+" target version appended");return view(order);
        });
    }
    @Transactional public JsonNode record(UUID id,FundsRequests.Entry request,String key){
        writer();var order=visible(id,true);return command(id,"ENTRY",key,request,()->{
            String dir=direction(request.direction()),number=text(request.transactionNo(),"Transaction number",128,true),note=text(request.note(),"Note",2000,false);BigDecimal amount=FundsMoney.parse(request.amount(),true);Instant time;
            try{time=Instant.parse(request.occurredAt());}catch(Exception invalid){throw ApiException.invalid("occurredAt must be an ISO-8601 timestamp with offset");}
            String hash=FileService.hash(json.writeValueAsBytes(List.of(id.toString(),dir,number,amount.toPlainString(),time.toString(),note)));
            mapper.commandLock("funds-transaction:"+dir+":"+number);var duplicate=mapper.transaction(dir,number);
            if(duplicate!=null){if(!hash.equals(duplicate.get("content_hash")))throw ApiException.conflict("TRANSACTION_CONFLICT","Direction and transaction number already belong to different content");return json.createObjectNode().put("entryId",duplicate.get("id").toString()).put("duplicate",true).set("funds",view(order));}
            expected(id,request.expectedVersion());if("CANCELLED".equals(order.getStatus()))throw ApiException.conflict("FUNDS_READ_ONLY","Cancelled cases cannot receive new entries");
            BigDecimal target=target(id,dir);if(target==null)throw ApiException.conflict("FUNDS_TARGET_UNSET","Set a manual target first");
            if(net(id,dir).add(amount).compareTo(target)>0)throw ApiException.conflict("FUNDS_EXCESS","Actual entry would exceed the target");
            var assignment=orders.activeAssignment(id);
            if(dir.equals("PAY")){
                if(!"COMPLETED".equals(order.getStatus())||!mapper.received(id))throw ApiException.conflict("FUNDS_RECEIPT_REQUIRED","Current confirmed vehicle receipt is required");
                if(!"SETTLED".equals(FundsMoney.status(target(id,"RECEIVE"),net(id,"RECEIVE"))))throw ApiException.conflict("FUNDS_INSURER_UNSETTLED","Insurance receipts must be settled before recording shop payment");
                var pay=mapper.target(id,"PAY");if(assignment==null||!Objects.equals(assignment.getShopId(),((Number)pay.get("shop_id")).longValue())||assignment.getAssignmentVersion()!=((Number)pay.get("assignment_version")).intValue())throw ApiException.conflict("FUNDS_ASSIGNMENT_CONFLICT","Payable version must belong to the current assignment");
            }
            UUID record=UUID.randomUUID();mapper.entrySave(record,id,dir,number,amount,time,dir.equals("PAY")?assignment.getShopId():null,dir.equals("PAY")?assignment.getAssignmentVersion():null,note,hash,CurrentUser.require().id());advance(id);
            audit.record(CurrentUser.require(),AuditService.Action.FUNDS_ENTRY,"FUNDS_ENTRY",record,"Recorded "+dir+"; no real transfer");return json.createObjectNode().put("entryId",record.toString()).put("duplicate",false).set("funds",view(order));
        });
    }
    @Transactional public JsonNode reverse(UUID id,UUID entry,FundsRequests.Reversal request,String key){
        writer();var order=visible(id,true);return command(id,"REVERSE",key,Map.of("entry",entry,"request",request),()->{
            expected(id,request.expectedVersion());var row=mapper.entry(entry);if(row==null||!id.equals(row.get("work_order_id")))throw ApiException.missing("Funds entry");if(mapper.reversed(entry))throw ApiException.conflict("FUNDS_ALREADY_REVERSED","Entry already reversed");
            mapper.reverse(UUID.randomUUID(),entry,text(request.reason(),"Reversal reason",2000,true),CurrentUser.require().id());advance(id);audit.record(CurrentUser.require(),AuditService.Action.FUNDS_REVERSE,"FUNDS_ENTRY",entry,"Full reversal appended; original entry retained");return view(order);
        });
    }
    private JsonNode entryView(Map<String,Object> row,boolean staff){
        var out=json.createObjectNode().put("id",row.get("id").toString()).put("direction",(String)row.get("direction")).put("amount",((BigDecimal)row.get("amount")).toPlainString()).put("occurredAt",row.get("occurred_at").toString()).put("createdAt",row.get("created_at").toString()).put("reversed",row.get("reversal_id")!=null);
        if(staff){out.put("transactionNo",(String)row.get("transaction_no")).put("note",(String)row.get("note"));out.set("reversalReason",json.valueToTree(row.get("reversal_reason")));}return out;
    }
    public PageResponse<JsonNode> history(UUID id,int page,int size){visible(id,false);int offset=PageResponse.offset(page,size);boolean staff=access.staff(CurrentUser.require());Long shop=staff?null:CurrentUser.require().shopId();Integer assignment=staff?null:orders.activeAssignment(id).getAssignmentVersion();return new PageResponse<>(page,size,mapper.count(id,shop,assignment),mapper.entries(id,shop,assignment,offset,size).stream().map(x->entryView(x,staff)).toList());}
    public PageResponse<JsonNode> targetHistory(UUID id,int page,int size){visible(id,false);if(!access.staff(CurrentUser.require()))throw ApiException.denied();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,mapper.targetCount(id),mapper.targets(id,offset,size).stream().map(x->{var n=json.createObjectNode().put("id",((Number)x.get("id")).longValue()).put("direction",(String)x.get("direction")).put("reason",(String)x.get("reason")).put("createdAt",x.get("created_at").toString());n.set("amount",x.get("amount")==null?json.nullNode():json.valueToTree(((BigDecimal)x.get("amount")).toPlainString()));return (JsonNode)n;}).toList());}
    public String export(UUID id,int page,int size){var rows=history(id,page,size);boolean staff=access.staff(CurrentUser.require());StringBuilder csv=new StringBuilder("id,direction,amount,occurredAt,reversed"+(staff?",transactionNo":"")+"\r\n");for(var row:rows.records()){for(String field:staff?List.of("id","direction","amount","occurredAt","reversed","transactionNo"):List.of("id","direction","amount","occurredAt","reversed")){String value=row.path(field).asText();if(value.matches("^[=+@-].*"))value="'"+value;csv.append('"').append(value.replace("\"","\"\"")).append("\",");}csv.setLength(csv.length()-1);csv.append("\r\n");}audit.record(CurrentUser.require(),AuditService.Action.FUNDS_EXPORT,"WORK_ORDER",id,"Scoped paginated ledger export; rows="+rows.records().size());return csv.toString();}
}
