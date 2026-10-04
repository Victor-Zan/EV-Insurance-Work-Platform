package com.evinsurance.platform.pricing;
import org.springframework.jdbc.core.JdbcTemplate;
/** Explicit TEST ONLY fixture. Never loaded by Spring or Flyway; no production initialization. */
public final class PriceDataFixture {
 private PriceDataFixture(){}
 public static void generate(JdbcTemplate db,String prefix,long model,long actor,long source,long region,long shop){
  for(int start=1;start<=100000;start+=5000){
   int end=start+4999;
   db.update("INSERT INTO price_part(internal_code,name,created_by,updated_by) SELECT ?||lpad(n::text,6,'0'),'开发性能测试配件',?,? FROM generate_series(?,?) n",prefix,actor,actor,start,end);
   String bounds="p.internal_code BETWEEN ? AND ?";
   String lower=prefix+String.format("%06d",start),upper=prefix+String.format("%06d",end);
   db.update("INSERT INTO price_alias(part_id,name,created_by,updated_by) SELECT p.id,'ALIAS-'||p.internal_code,?,? FROM price_part p WHERE "+bounds,actor,actor,lower,upper);
   db.update("INSERT INTO price_part_model(part_id,model_id,created_by) SELECT p.id,?,? FROM price_part p WHERE "+bounds,model,actor,lower,upper);
   db.update("""
    INSERT INTO price_record(part_id,model_id,price_type,scope,region_id,shop_id,created_by)
    SELECT p.id,?,(ARRAY['REPAIR_SHOP_RAW_REFERENCE','PLATFORM_EXTERNAL_REFERENCE','INSURER_HISTORICAL_ASSESSED','REPAIR_SHOP_SETTLEMENT_REFERENCE'])[right(p.internal_code,6)::integer%4+1],
    CASE right(p.internal_code,6)::integer%3 WHEN 0 THEN 'NATIONAL' WHEN 1 THEN 'REGION' ELSE 'SHOP' END,
    CASE WHEN right(p.internal_code,6)::integer%3=1 THEN ?::bigint END,
    CASE WHEN right(p.internal_code,6)::integer%3=2 THEN ?::bigint END,?
    FROM price_part p WHERE """+" "+bounds,model,region,shop,actor,lower,upper);
   db.update("""
    INSERT INTO price_version(record_id,version_no,amount,source_id,source_code,source_name,source_kind,effective_from,created_by)
    SELECT r.id,1,100.00::numeric+(right(p.internal_code,6)::integer%10000)::numeric/100,s.id,s.code,s.name,s.kind,DATE '2026-01-01',?
    FROM price_record r JOIN price_part p ON p.id=r.part_id CROSS JOIN price_source s WHERE s.id=? AND """+" "+bounds+" ORDER BY r.id",actor,source,lower,upper);
  }
  for(String table:new String[]{"price_part","price_alias","price_part_model","price_record","price_version","price_model"})db.execute("ANALYZE "+table);
 }
}
