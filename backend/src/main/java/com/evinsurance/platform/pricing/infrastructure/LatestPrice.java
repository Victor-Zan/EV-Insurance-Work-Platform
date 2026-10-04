package com.evinsurance.platform.pricing.infrastructure;
public class LatestPrice extends PriceVersion {
 private Long partId,modelId,regionId,shopId;
 private String priceType,scope;
 public Long getPartId(){return partId;} public void setPartId(Long v){partId=v;}
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 public Long getRegionId(){return regionId;} public void setRegionId(Long v){regionId=v;}
 public Long getShopId(){return shopId;} public void setShopId(Long v){shopId=v;}
 public String getPriceType(){return priceType;} public void setPriceType(String v){priceType=v;}
 public String getScope(){return scope;} public void setScope(String v){scope=v;}
 public String key(){return partId+"|"+modelId+"|"+priceType+"|"+scope+"|"+regionId+"|"+shopId;}
}
