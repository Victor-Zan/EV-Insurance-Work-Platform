package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("price_part")
public class PartEntity extends CatalogueRow {
 private String internalCode;
 public String getInternalCode(){return internalCode;} public void setInternalCode(String v){internalCode=v;}
 private String name;
 public String getName(){return name;} public void setName(String v){name=v;}
 private Boolean enabled;
 public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
}
