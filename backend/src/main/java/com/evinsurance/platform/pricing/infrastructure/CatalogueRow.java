package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
public class CatalogueRow {
 @TableId(type=IdType.AUTO) private Long id;
 private Instant createdAt,updatedAt;
 private Long createdBy,updatedBy;
 public Long getId(){return id;} public void setId(Long v){id=v;}
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
 public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
 public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
 public Long getUpdatedBy(){return updatedBy;} public void setUpdatedBy(Long v){updatedBy=v;}
}
