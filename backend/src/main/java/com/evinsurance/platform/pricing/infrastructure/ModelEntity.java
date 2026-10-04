package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("price_model")
public class ModelEntity extends CatalogueRow {
 private Long brandId;
 public Long getBrandId(){return brandId;} public void setBrandId(Long v){brandId=v;}
 private String code;
 public String getCode(){return code;} public void setCode(String v){code=v;}
 private String name;
 public String getName(){return name;} public void setName(String v){name=v;}
 private Boolean enabled;
 public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
}
