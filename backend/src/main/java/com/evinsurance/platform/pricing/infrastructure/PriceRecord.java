package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
@TableName("price_record")
public class PriceRecord {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;} public void setId(Long v){id=v;}
 private Long partId;
 public Long getPartId(){return partId;} public void setPartId(Long v){partId=v;}
 private Long modelId;
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 private String priceType;
 public String getPriceType(){return priceType;} public void setPriceType(String v){priceType=v;}
 private String scope;
 public String getScope(){return scope;} public void setScope(String v){scope=v;}
 private Long regionId;
 public Long getRegionId(){return regionId;} public void setRegionId(Long v){regionId=v;}
 private Long shopId;
 public Long getShopId(){return shopId;} public void setShopId(Long v){shopId=v;}
 private Instant createdAt;
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
 private Long createdBy;
 public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
}
