package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.workorder.api.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.workorder.domain.CaseTextCandidates;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;

@Service
public class TextDraftService {
    private final WorkOrderService orders;private final JdbcTemplate db;private final ObjectMapper json;private final AuditService audit;
    public TextDraftService(WorkOrderService orders,JdbcTemplate db,ObjectMapper json,AuditService audit){this.orders=orders;this.db=db;this.json=json;this.audit=audit;}
    private void validate(String text){var roles=CurrentUser.require().roles();if(!roles.contains(Role.CUSTOMER_SERVICE)&&!roles.contains(Role.ADMIN))throw ApiException.denied();if(text==null||text.isBlank()||text.indexOf(0)>=0||text.length()>50000||text.lines().count()>1000)throw ApiException.invalid("文字须为1—50000字符且不超过1000行");}
    public JsonNode extract(String text){validate(text);return json.valueToTree(new CaseTextCandidates().extract(text));}
    public JsonNode source(UUID id){var actor=CurrentUser.require();if(!actor.roles().contains(Role.ADMIN)&&!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();orders.get(id);var rows=db.queryForList("SELECT original_text,candidate_json,adopted_json,created_at FROM text_draft_source WHERE work_order_id=? ORDER BY created_at DESC,id LIMIT 1",id);if(rows.isEmpty())return json.nullNode();var row=rows.getFirst();var result=json.createObjectNode();result.put("text",row.get("original_text").toString());result.set("candidate",json.readTree(row.get("candidate_json").toString()));result.set("adopted",json.readTree(row.get("adopted_json").toString()));return result;}
    @Transactional public WorkOrderViews.WorkOrder create(String key,String text,boolean confirmed,WorkOrderRequests.Draft draft){
        validate(text);FileService.checkKey(key);if(!confirmed)throw ApiException.conflict("TEXT_REVIEW_REQUIRED","请先核对原文和采用字段");
        var actor=CurrentUser.require();String adopted=json.writeValueAsString(draft),hash=FileService.hash((text+"|"+adopted).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",actor.id());var previous=db.queryForList("SELECT request_hash,work_order_id FROM text_draft_source WHERE actor_id=? AND command_key=?",actor.id(),key);
        if(!previous.isEmpty()){if(!hash.equals(previous.getFirst().get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","文字建单请求已变化");return orders.get((UUID)previous.getFirst().get("work_order_id"));}
        var order=orders.createDraft(draft);var sourceId=UUID.randomUUID();db.update("INSERT INTO text_draft_source(id,actor_id,command_key,request_hash,original_text,candidate_json,adopted_json,work_order_id) VALUES(?,?,?,?,?,?,?,?)",sourceId,actor.id(),key,hash,text,json.writeValueAsString(extract(text)),adopted,order.id());
        audit.record(actor,AuditService.Action.WORK_ORDER_DRAFT_CREATE,"TEXT_DRAFT_SOURCE",sourceId,"Human reviewed text linked to draft="+order.id()+"; contents excluded");return order;
    }
}
