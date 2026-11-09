package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("price_part")
public class PartEntity extends CatalogueRow {
 private String internalCode;
 public String getInternalCode(){return internalCode;} public void setInternalCode(String v){internalCode=v;}
 private String name;
 public String getName(){return name;} public void setName(String v){name=v;}
 private Boolean enabled;
 private String unit;
 public String getUnit(){return unit;} public void setUnit(String v){unit=v;}
 private String oeCode;
 public String getOeCode(){return oeCode;} public void setOeCode(String v){oeCode=v;}
 private String manufacturerCode;
 public String getManufacturerCode(){return manufacturerCode;} public void setManufacturerCode(String v){manufacturerCode=v;}
 public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
}
