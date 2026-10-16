package com.evinsurance.platform.funds.application;

import com.evinsurance.platform.funds.api.FundsRequests;
import com.evinsurance.platform.funds.domain.FundsMoney;
import com.evinsurance.platform.funds.infrastructure.FundsMapper;
import com.evinsurance.platform.document.application.*;
import com.evinsurance.platform.workorder.infrastructure.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.foundation.web.TraceContext;
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
    private String basis(UUID id,String dir){
        var target=mapper.target(id,dir);var receive=dir.equals("PAY")?mapper.confirmation(id,"RECEIVE"):null;
        return FileService.hash((mapper.receiptId(id)+":"+(target==null?"unset":target.get("id"))+":"+mapper.ledgerBasis(id,dir)+":"+(receive==null?"":receive.get("id"))).getBytes(StandardCharsets.UTF_8));
    }
    private boolean confirmed(UUID id,String dir){
        var saved=mapper.confirmation(id,dir);
        return saved!=null&&mapper.receiptId(id)!=null&&basis(id,dir).equals(saved.get("basis_hash"))&&(!dir.equals("PAY")||(confirmed(id,"RECEIVE")&&currentPay(id)));
    }
    private boolean currentPay(UUID id){
        var pay=mapper.target(id,"PAY");var assignment=orders.activeAssignment(id);
        return pay!=null&&assignment!=null&&"ACCEPTED".equals(assignment.getStatus())&&Objects.equals(assignment.getShopId(),((Number)pay.get("shop_id")).longValue())&&assignment.getAssignmentVersion()==((Number)pay.get("assignment_version")).intValue();
    }
    private boolean canConfirm(WorkOrderEntity order,String dir){
        UUID id=order.getId();
        return mapper.staged(id)&&mapper.received(id)&&order.getStatus().equals(dir.equals("RECEIVE")?"WAITING_INSURER_PAYMENT":"WAITING_SHOP_SETTLEMENT")
            &&"SETTLED".equals(FundsMoney.status(target(id,dir),net(id,dir)))&&(!dir.equals("PAY")||(confirmed(id,"RECEIVE")&&currentPay(id)));
    }
    private boolean canPay(WorkOrderEntity order){
        UUID id=order.getId();return mapper.received(id)&&(mapper.staged(id)?"WAITING_SHOP_SETTLEMENT".equals(order.getStatus())&&confirmed(id,"RECEIVE"):"COMPLETED".equals(order.getStatus()));
    }
    private void refreshStage(WorkOrderEntity order){
        UUID id=order.getId();if(!mapper.staged(id)||!mapper.received(id)||!Set.of("WAITING_INSURER_PAYMENT","WAITING_SHOP_SETTLEMENT","COMPLETED").contains(order.getStatus()))return;
        String next=!confirmed(id,"RECEIVE")?"WAITING_INSURER_PAYMENT":!confirmed(id,"PAY")?"WAITING_SHOP_SETTLEMENT":"COMPLETED";
        if(next.equals(order.getStatus()))return;
        var actor=CurrentUser.require();var now=Instant.now();var assignment=orders.activeAssignment(id);
        if(orders.transition(id,order.getVersion(),order.getStatus(),next,order.getShopId(),false,actor.id(),now)!=1)throw ApiException.conflict("WORK_ORDER_STATE_CONFLICT","Work order changed concurrently");
        orders.insertHistory(id,order.getStatus(),next,"FUNDS_WORKFLOW",null,actor.id(),"CUSTOMER_SERVICE",assignment==null?null:assignment.getAssignmentVersion(),TraceContext.currentTraceId()==null?UUID.randomUUID().toString():TraceContext.currentTraceId(),now);
        order.setStatus(next);order.setVersion(order.getVersion()+1);
    }
    @Transactional public JsonNode confirm(UUID id,FundsRequests.Confirmation request,String key){
        writer();var order=visible(id,true);return command(id,"CONFIRM",key,request,()->{
            expected(id,request.expectedVersion());String dir=direction(request.direction());
            if(!canConfirm(order,dir))throw ApiException.conflict("FUNDS_CONFIRMATION_BLOCKED","请先确认收车，按顺序补齐目标金额与流水，再确认该笔资金完成");
            mapper.confirm(id,dir,basis(id,dir),version(id),CurrentUser.require().id());advance(id);refreshStage(order);
            audit.record(CurrentUser.require(),AuditService.Action.FUNDS_CONFIRM,"WORK_ORDER",id,"Manually confirmed "+dir+"; amount="+net(id,dir).toPlainString()+"; receipt="+mapper.receiptId(id)+"; immutable target and entry snapshot retained; no real transfer");return view(order);
        });
    }
    private ObjectNode directionView(BigDecimal target,BigDecimal net){var out=json.createObjectNode().put("net",net.toPlainString()).put("status",FundsMoney.status(target,net));if(target==null)out.putNull("target");else out.put("target",target.toPlainString());return out;}
    private JsonNode view(WorkOrderEntity order){
        UUID id=order.getId();var out=json.createObjectNode().put("caseId",id.toString()).put("currency","CNY").put("unit","yuan").put("version",version(id));var actor=CurrentUser.require();
        if(access.staff(actor))out.set("receivable",directionView(target(id,"RECEIVE"),net(id,"RECEIVE")));
        var payable=mapper.target(id,"PAY");BigDecimal amount=payable==null?null:(BigDecimal)payable.get("amount");
        Long shop=null;Integer assignment=null;if(!access.staff(actor)){var current=orders.activeAssignment(id);shop=actor.shopId();assignment=current.getAssignmentVersion();if(payable!=null&&(!Objects.equals(shop,((Number)payable.get("shop_id")).longValue())||!Objects.equals(assignment,((Number)payable.get("assignment_version")).intValue())))amount=null;}
        var paid=mapper.net(id,"PAY",shop,assignment).setScale(2);out.set("payable",directionView(amount,paid));
        if(access.staff(actor)){
            var receivable=target(id,"RECEIVE");var received=net(id,"RECEIVE");var current=orders.activeAssignment(id);boolean mutable=!"CANCELLED".equals(order.getStatus());
            boolean currentPay=current!=null&&payable!=null&&Objects.equals(current.getShopId(),((Number)payable.get("shop_id")).longValue())&&current.getAssignmentVersion()==((Number)payable.get("assignment_version")).intValue();
            out.put("canSetPayTarget",mutable&&current!=null&&"ACCEPTED".equals(current.getStatus()));
            out.put("canRecordReceive",mutable&&(!mapper.staged(id)||"WAITING_INSURER_PAYMENT".equals(order.getStatus()))&&receivable!=null&&receivable.compareTo(received)>0);
            out.put("canRecordPay",mutable&&canPay(order)&&currentPay&&amount!=null&&amount.compareTo(paid)>0&&"SETTLED".equals(FundsMoney.status(receivable,received)));
            out.put("flowStage",order.getStatus()).put("stagedFlow",mapper.staged(id)).put("receiveConfirmed",confirmed(id,"RECEIVE")).put("payConfirmed",confirmed(id,"PAY")).put("canConfirmReceive",canConfirm(order,"RECEIVE")).put("canConfirmPay",canConfirm(order,"PAY"));
        }return out;
    }
    private String filter(String value) {if(value==null||value.isBlank())return null;if(value.length()>120)throw ApiException.invalid("Filter is too long");return value.trim();}
    private String fundsStatus(String value) {value=filter(value);if(value!=null&&!Set.of("UNSET","UNPAID","PARTIAL","SETTLED").contains(value))throw ApiException.invalid("Unknown funds status");return value;}
    public PageResponse<JsonNode> workbench(int page,int size,String query,String status,String receive,String pay) {
        if(!access.staff(CurrentUser.require()))throw ApiException.denied();int offset=PageResponse.offset(page,size);
        query=filter(query);status=filter(status);receive=fundsStatus(receive);pay=fundsStatus(pay);
        if(status!=null){try{com.evinsurance.platform.workorder.domain.WorkOrderStatus.valueOf(status);}catch(IllegalArgumentException invalid){throw ApiException.invalid("Unknown work order status");}}
        var rows=mapper.workbench(query,status,receive,pay,offset,size).stream().map(row->{
            var n=json.createObjectNode().put("caseId",row.get("id").toString()).put("businessNo",(String)row.get("business_no")).put("claimNo",(String)row.get("claim_no")).put("status",(String)row.get("status"));
            for(String dir:List.of("receive","pay")) {
                var amount=(BigDecimal)row.get(dir+"_target");var balance=json.createObjectNode().put("net",((BigDecimal)row.get(dir+"_net")).setScale(2).toPlainString()).put("status",(String)row.get(dir+"_status"));
                if(amount==null)balance.putNull("target");else balance.put("target",amount.setScale(2).toPlainString());n.set(dir.equals("receive")?"receivable":"payable",balance);
            }return (JsonNode)n;
        }).toList();
        return new PageResponse<>(page,size,mapper.workbenchCount(query,status,receive,pay),rows);
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
            mapper.targetSave(id,dir,amount,dir.equals("PAY")?assignment.getShopId():null,dir.equals("PAY")?assignment.getAssignmentVersion():null,text(request.reason(),"Target version reason",2000,true),CurrentUser.require().id());advance(id);refreshStage(order);
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
            if(dir.equals("RECEIVE")&&mapper.staged(id)&&!"WAITING_INSURER_PAYMENT".equals(order.getStatus()))throw ApiException.conflict("FUNDS_SEQUENCE_REQUIRED","先回到保险到账步骤再补记到账流水");
            if(dir.equals("PAY")){
                if(!canPay(order))throw ApiException.conflict("FUNDS_RECEIPT_REQUIRED","Current confirmed vehicle receipt is required");
                if(!"SETTLED".equals(FundsMoney.status(target(id,"RECEIVE"),net(id,"RECEIVE"))))throw ApiException.conflict("FUNDS_INSURER_UNSETTLED","Insurance receipts must be settled before recording shop payment");
                var pay=mapper.target(id,"PAY");if(assignment==null||!Objects.equals(assignment.getShopId(),((Number)pay.get("shop_id")).longValue())||assignment.getAssignmentVersion()!=((Number)pay.get("assignment_version")).intValue())throw ApiException.conflict("FUNDS_ASSIGNMENT_CONFLICT","Payable version must belong to the current assignment");
            }
            UUID record=UUID.randomUUID();mapper.entrySave(record,id,dir,number,amount,time,dir.equals("PAY")?assignment.getShopId():null,dir.equals("PAY")?assignment.getAssignmentVersion():null,note,hash,CurrentUser.require().id());advance(id);refreshStage(order);
            audit.record(CurrentUser.require(),AuditService.Action.FUNDS_ENTRY,"FUNDS_ENTRY",record,"Recorded "+dir+"; no real transfer");return json.createObjectNode().put("entryId",record.toString()).put("duplicate",false).set("funds",view(order));
        });
    }
    @Transactional public JsonNode reverse(UUID id,UUID entry,FundsRequests.Reversal request,String key){
        writer();var order=visible(id,true);return command(id,"REVERSE",key,Map.of("entry",entry,"request",request),()->{
            expected(id,request.expectedVersion());var row=mapper.entry(entry);if(row==null||!id.equals(row.get("work_order_id")))throw ApiException.missing("Funds entry");if(mapper.reversed(entry))throw ApiException.conflict("FUNDS_ALREADY_REVERSED","Entry already reversed");
            mapper.reverse(UUID.randomUUID(),entry,text(request.reason(),"Reversal reason",2000,true),CurrentUser.require().id());advance(id);refreshStage(order);audit.record(CurrentUser.require(),AuditService.Action.FUNDS_REVERSE,"FUNDS_ENTRY",entry,"Full reversal appended; original entry retained");return view(order);
        });
    }
    private JsonNode entryView(Map<String,Object> row,boolean staff){
        var out=json.createObjectNode().put("id",row.get("id").toString()).put("direction",(String)row.get("direction")).put("amount",((BigDecimal)row.get("amount")).toPlainString()).put("occurredAt",row.get("occurred_at").toString()).put("createdAt",row.get("created_at").toString()).put("reversed",row.get("reversal_id")!=null);
        if(staff){out.put("transactionNo",(String)row.get("transaction_no")).put("note",(String)row.get("note"));out.set("reversalReason",json.valueToTree(row.get("reversal_reason")));}return out;
    }
    public PageResponse<JsonNode> history(UUID id,int page,int size){visible(id,false);int offset=PageResponse.offset(page,size);boolean staff=access.staff(CurrentUser.require());Long shop=staff?null:CurrentUser.require().shopId();Integer assignment=staff?null:orders.activeAssignment(id).getAssignmentVersion();return new PageResponse<>(page,size,mapper.count(id,shop,assignment),mapper.entries(id,shop,assignment,offset,size).stream().map(x->entryView(x,staff)).toList());}
    public PageResponse<JsonNode> targetHistory(UUID id,int page,int size){visible(id,false);if(!access.staff(CurrentUser.require()))throw ApiException.denied();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,mapper.targetCount(id),mapper.targets(id,offset,size).stream().map(x->{var n=json.createObjectNode().put("id",((Number)x.get("id")).longValue()).put("direction",(String)x.get("direction")).put("reason",(String)x.get("reason")).put("actorId",((Number)x.get("actor_id")).longValue()).put("createdAt",x.get("created_at").toString());n.set("amount",x.get("amount")==null?json.nullNode():json.valueToTree(((BigDecimal)x.get("amount")).toPlainString()));return (JsonNode)n;}).toList());}
    public String export(UUID id,int page,int size){var rows=history(id,page,size);boolean staff=access.staff(CurrentUser.require());StringBuilder csv=new StringBuilder("id,direction,amount,occurredAt,reversed"+(staff?",transactionNo":"")+"\r\n");for(var row:rows.records()){for(String field:staff?List.of("id","direction","amount","occurredAt","reversed","transactionNo"):List.of("id","direction","amount","occurredAt","reversed")){String value=row.path(field).asText();if(value.matches("^[=+@-].*"))value="'"+value;csv.append('"').append(value.replace("\"","\"\"")).append("\",");}csv.setLength(csv.length()-1);csv.append("\r\n");}audit.record(CurrentUser.require(),AuditService.Action.FUNDS_EXPORT,"WORK_ORDER",id,"Scoped paginated ledger export; rows="+rows.records().size());return csv.toString();}
}
