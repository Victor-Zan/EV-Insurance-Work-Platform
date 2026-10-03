package com.evinsurance.platform.audit.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("audit_log")
public class AuditEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private String actorRoles;
    private String action;
    private String objectType;
    private String objectId;
    private String summary;
    private String traceId;
    private Instant occurredAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long value) { this.actorId = value; }
    public String getActorRoles() { return actorRoles; }
    public void setActorRoles(String value) { this.actorRoles = value; }
    public String getAction() { return action; }
    public void setAction(String value) { this.action = value; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String value) { this.objectType = value; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String value) { this.objectId = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { this.summary = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { this.traceId = value; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant value) { this.occurredAt = value; }
}
