package com.evinsurance.platform.organization.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("repair_shop")
public class ShopEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private Boolean enabled;
    private String contactName;
    private String contactPhone;
    private String address;
    private Instant createdAt;
    private Instant updatedAt;
    private Long createdBy;
    private Long updatedBy;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    public String getCode() { return code; }
    public void setCode(String value) { this.code = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { this.enabled = value; }
    public String getContactName() { return contactName; }
    public void setContactName(String value) { this.contactName = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { this.contactPhone = value; }
    public String getAddress() { return address; }
    public void setAddress(String value) { this.address = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { this.createdBy = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long value) { this.updatedBy = value; }
}
