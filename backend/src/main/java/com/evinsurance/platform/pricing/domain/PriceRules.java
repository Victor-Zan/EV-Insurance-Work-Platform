package com.evinsurance.platform.pricing.domain;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.evinsurance.platform.foundation.api.ApiException;
import org.springframework.http.HttpStatus;
public final class PriceRules {
 private PriceRules(){}
 public static void validate(PriceDraft r){
  if(r.partId()<1||r.modelId()<1||r.sourceId()<1||r.priceType()==null||r.scope()==null)throw ApiException.invalid("Price identity and source are required");
  if(r.amount()==null||r.amount().signum()<=0||r.amount().scale()>2||r.amount().compareTo(new BigDecimal("9999999999999999.99"))>0)throw ApiException.invalid("amount must be positive CNY with at most 16 integer and 2 decimal digits");
  if(r.effectiveFrom()==null||r.effectiveFrom().getYear()<1||r.effectiveFrom().getYear()>9999||
    r.effectiveTo()!=null&&(r.effectiveTo().isBefore(r.effectiveFrom())||r.effectiveTo().getYear()>9999))
   throw ApiException.invalid("Effective dates must be valid inclusive ISO natural dates, with end on or after start");
  boolean valid=switch(r.scope()){
   case NATIONAL->r.regionId()==null&&r.shopId()==null;
   case REGION->r.regionId()!=null&&r.regionId()>0&&r.shopId()==null;
   case SHOP->r.shopId()!=null&&r.shopId()>0&&r.regionId()==null;
  };
  if(!valid)throw ApiException.invalid("Scope requires either no organization, one region or one shop");
 }
 public static void follows(LocalDate from,LocalDate lastFrom,LocalDate lastTo){
  if(lastFrom!=null&&!from.isAfter(lastFrom))throw conflict("New start date must be later than the latest version start");
  if(lastTo!=null&&!from.isAfter(lastTo))throw conflict("Effective interval overlaps an existing finite version");
 }
 public static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"PRICE_VERSION_CONFLICT",message);}
 public static ApiException immutable(){return new ApiException(HttpStatus.CONFLICT,"HISTORICAL_PRICE_IMMUTABLE","Historical price cannot be updated or deleted; create a new version");}
}
