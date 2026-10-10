package com.evinsurance.platform.notification.application;
import com.evinsurance.platform.notification.infrastructure.NotificationMapper;
import com.evinsurance.platform.document.application.FileAccess;
import com.evinsurance.platform.complaint.infrastructure.ComplaintMapper;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import java.util.*;
import java.time.*;
import tools.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class NotificationService {
    public record Deadline(String taskKey,Integer expectedVersion,String deadline,String reason) {}
    private final NotificationMapper mapper;private final NotificationProjector projector;private final FileAccess access;private final ComplaintMapper complaints;private final AuditService audit;private final ObjectMapper json;
    public NotificationService(NotificationMapper mapper,NotificationProjector projector,FileAccess access,ComplaintMapper complaints,AuditService audit,ObjectMapper json){this.mapper=mapper;this.projector=projector;this.access=access;this.complaints=complaints;this.audit=audit;this.json=json;}
    private String time(Object value){if(value==null)return null;if(value instanceof java.sql.Timestamp stamp)return stamp.toInstant().toString();if(value instanceof OffsetDateTime offset)return offset.toInstant().toString();return value.toString();}
    private String title(String kind){return switch(kind){
        case "DISPATCHED","ACCEPT_SHOP"->"新派单待接单";case "ARRIVED"->"车辆已到店";case "ARRIVE_SHOP"->"待确认车辆到店";
        case "QUOTE_REVIEW","REVIEW_QUOTE"->"原始报价待客服审核";case "QUOTE_SHOP"->"待提交网点原始报价";
        case "ASSESSMENT_CONFIRM","CONFIRM_ASSESSMENT"->"核损待客服处理/确认";case "START_REPAIR_SHOP"->"授权已满足，待网点开修";case "REPAIR_SHOP"->"维修进度待更新/完工";
        case "OWNER_RECEIPT","RECEIVE_OWNER"->"维修完工待确认收车";case "OWNER_RECEIVED"->"已确认收车";case "RECEIPT_WITHDRAWN"->"收车确认已撤回";
        case "COMPLAINT_NEW","HANDLE_COMPLAINT"->"独立投诉待管理员处理";case "COMPLAINT_UPDATED"->"投诉处理记录已更新";
        case "SET_RECEIVABLE"->"应收目标待录入";case "COLLECT_FUNDS"->"保险回款待登记";case "SET_PAYABLE"->"网点应付金额待确认";case "PAY_SHOP"->"网点实付待登记";
        case "FUNDS_UPDATED","PAY_UPDATED"->"资金记录已更新";default->"案件事件";
    };}
    private JsonNode notice(Map<String,Object> row){return json.createObjectNode().put("id",((Number)row.get("id")).longValue()).put("caseId",row.get("work_order_id").toString()).put("kind",(String)row.get("kind")).put("title",title((String)row.get("kind"))).put("createdAt",time(row.get("created_at"))).put("read",row.get("read_at")!=null);}
    public PageResponse<JsonNode> inbox(int page,int size){int offset=PageResponse.offset(page,size);long user=CurrentUser.require().id();projector.project();return new PageResponse<>(page,size,mapper.count(user),mapper.inbox(user,offset,size).stream().map(this::notice).toList());}
    @Transactional public JsonNode read(long id){long user=CurrentUser.require().id();var row=mapper.visible(user,id);if(row==null)throw ApiException.denied();if(mapper.read(id,user)==1)audit.record(CurrentUser.require(),AuditService.Action.NOTIFICATION_READ,"NOTIFICATION",id,"Station notification read by its recipient");row.put("read_at",Instant.now());return notice(row);}
    private JsonNode task(Map<String,Object> row){String deadline=time(row.get("deadline"));var out=json.createObjectNode().put("taskKey",(String)row.get("task_key")).put("caseId",row.get("work_order_id").toString()).put("kind",(String)row.get("kind")).put("title",title((String)row.get("kind"))).put("version",((Number)row.get("version")).intValue()).put("overdue",deadline!=null&&Instant.parse(deadline).isBefore(Instant.now()));if(deadline==null)out.putNull("deadline");else out.put("deadline",deadline);return out;}
    public PageResponse<JsonNode> todos(UUID caseId,int page,int size){int offset=PageResponse.offset(page,size);boolean all=false;if(caseId!=null){access.order(caseId,false);all=access.staff(CurrentUser.require());}long user=CurrentUser.require().id();return new PageResponse<>(page,size,mapper.todoCount(user,all,caseId,null),mapper.todos(user,all,caseId,null,offset,size).stream().map(this::task).toList());}
    private String key(String key){if(key==null||key.isBlank()||key.length()>512)throw ApiException.invalid("Use the published current task key");return key;}
    @Transactional public JsonNode deadline(Deadline request){
        access.customerService(CurrentUser.require());String key=key(request.taskKey());var candidates=mapper.todos(CurrentUser.require().id(),true,null,key,0,1);if(candidates.isEmpty())throw ApiException.conflict("TODO_INACTIVE","Task is no longer active");UUID caseId=(UUID)candidates.getFirst().get("work_order_id");access.order(caseId,true);
        if("HANDLE_COMPLAINT".equals(candidates.getFirst().get("kind")))complaints.lock(UUID.fromString(key.split(":")[1]));
        if(mapper.todos(CurrentUser.require().id(),true,caseId,key,0,1).isEmpty())throw ApiException.conflict("TODO_INACTIVE","Task is no longer active");
        if(request.reason()==null||request.reason().isBlank()||request.reason().length()>2000)throw ApiException.invalid("Deadline changes require a reason, at most 2000 characters");
        Instant deadline=null;if(request.deadline()!=null&&!request.deadline().isBlank()){try{var date=OffsetDateTime.parse(request.deadline());if(date.getOffset().getTotalSeconds()!=28800)throw new IllegalArgumentException();deadline=date.toInstant();}catch(Exception invalid){throw ApiException.invalid("Use China timezone ISO date/time with +08:00");}}
        mapper.ensure(key,caseId);var current=mapper.deadline(key);int version=((Number)current.get("version")).intValue();if(request.expectedVersion()==null||version!=request.expectedVersion())throw ApiException.conflict("TODO_VERSION_CONFLICT","Refresh the task version");
        if(mapper.change(key,version,deadline)!=1)throw ApiException.conflict("TODO_VERSION_CONFLICT","Deadline changed concurrently");mapper.event(key,deadline,request.reason().trim(),CurrentUser.require().id());audit.record(CurrentUser.require(),AuditService.Action.TODO_DEADLINE,"WORK_ORDER",caseId,"Task deadline appended or cleared; automatic reminders disabled");return task(mapper.todos(CurrentUser.require().id(),true,caseId,key,0,1).getFirst());
    }
    public PageResponse<JsonNode> history(String taskKey,int page,int size){if(!access.staff(CurrentUser.require()))throw ApiException.denied();String key=key(taskKey);int offset=PageResponse.offset(page,size);var row=mapper.deadlineRead(key);if(row==null)throw ApiException.missing("Deadline history");access.order((UUID)row.get("work_order_id"),false);return new PageResponse<>(page,size,mapper.historyCount(key),mapper.history(key,offset,size).stream().map(event->{var out=json.createObjectNode().put("id",((Number)event.get("id")).longValue()).put("reason",(String)event.get("reason")).put("createdAt",time(event.get("created_at")));String deadline=time(event.get("deadline"));if(deadline==null)out.putNull("deadline");else out.put("deadline",deadline);return (JsonNode)out;}).toList());}
    public JsonNode settings(){return json.createObjectNode().put("automaticTimeoutEnabled",false).put("repeatedRemindersEnabled",false).put("escalationEnabled",false).put("smsEnabled",false).put("deadlineTimezone","Asia/Shanghai");}
}
