package com.evinsurance.platform.pricing;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.evinsurance.platform.pricing.domain.ImportData;
import tools.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.apache.commons.csv.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class PricePostgresIT {
 private static final String PASSWORD=UUID.randomUUID().toString(),SCHEMA="price_it_"+UUID.randomUUID().toString().replace("-","");
 private static final String SECRET=secret();
 private static String secret(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return Base64.getEncoder().encodeToString(key);}
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
  String url=System.getenv("TEST_DB_URL");if(url==null||url.isBlank())throw new IllegalStateException("Dedicated TEST_DB_URL required");
  registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
  registry.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));registry.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
  registry.add("spring.flyway.schemas",()->SCHEMA);registry.add("spring.flyway.default-schema",()->SCHEMA);
  registry.add("JWT_SECRET_BASE64",()->SECRET);registry.add("JWT_TTL_SECONDS",()->"1800");
  for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))registry.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
 }
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;
 private String admin;private long source,region,shop;
 private record Fixture(long brand,long model,long part,String brandCode,String partCode,String alias){}
 @BeforeEach void setup()throws Exception{
  admin=login("dev_admin","ADMIN");source=db.queryForObject("select id from price_source where code='MANUAL'",Long.class);
  region=db.queryForObject("select id from region where code='DEV-CITY'",Long.class);shop=db.queryForObject("select id from repair_shop where code='DEV-SHOP'",Long.class);
 }
 private String login(String account,String portal)throws Exception{
  return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
   .content(json.writeValueAsString(Map.of("username",account,"password",PASSWORD,"portal",portal))))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();
 }
 private ResultActions request(MockHttpServletRequestBuilder r,Object body)throws Exception{
  r.header("Authorization","Bearer "+admin);if(body!=null)r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return mvc.perform(r);
 }
 private JsonNode ok(MockHttpServletRequestBuilder r,Object body)throws Exception{return json.readTree(request(r,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
 private Fixture fixture()throws Exception{
  String suffix=UUID.randomUUID().toString().substring(0,8),bc="IT-B-"+suffix,pc="IT-P-"+suffix,alias="测试别名-"+suffix;
  long brand=ok(post("/api/v1/pricing/brands"),Map.of("code",bc,"name","测试品牌","enabled",true)).path("id").asLong();
  long model=ok(post("/api/v1/pricing/models"),Map.of("brandId",brand,"code","M","name","测试车型","enabled",true)).path("id").asLong();
  long part=ok(post("/api/v1/pricing/parts"),Map.of("internalCode",pc,"name","测试,中文配件","enabled",true)).path("id").asLong();
  ok(post("/api/v1/pricing/aliases"),Map.of("partId",part,"name",alias));ok(post("/api/v1/pricing/part-models"),Map.of("partId",part,"modelId",model));
  return new Fixture(brand,model,part,bc,pc,alias);
 }
 private Map<String,Object> price(Fixture f,String from,String to,String amount,String scope,String type){
  var r=new LinkedHashMap<String,Object>();r.put("partId",f.part());r.put("modelId",f.model());r.put("priceType",type);r.put("scope",scope);
  if(scope.equals("REGION"))r.put("regionId",region);if(scope.equals("SHOP"))r.put("shopId",shop);
  r.put("amount",amount);r.put("sourceId",source);r.put("effectiveFrom",from);if(to!=null)r.put("effectiveTo",to);return r;
 }
 private JsonNode first(Fixture f,String from,String to)throws Exception{return ok(post("/api/v1/pricing/prices"),price(f,from,to,"123.45","NATIONAL","REPAIR_SHOP_RAW_REFERENCE"));}
 private Map<String,Object> version(String from,String to,String amount){var r=new HashMap<String,Object>();r.put("amount",amount);r.put("sourceId",source);r.put("effectiveFrom",from);if(to!=null)r.put("effectiveTo",to);return r;}
 private Map<String,String> row(Fixture f,String from,String to,String amount){
  var r=new LinkedHashMap<String,String>();for(String field:ImportData.FIELDS)r.put(field,"");
  r.put("brandCode",f.brandCode());r.put("modelCode","M");r.put("internalCode",f.partCode());r.put("partName","测试,中文配件");r.put("alias",f.alias());
  r.put("priceType","REPAIR_SHOP_RAW_REFERENCE");r.put("scope","NATIONAL");r.put("amount",amount);r.put("sourceCode","MANUAL");r.put("effectiveFrom",from);r.put("effectiveTo",to==null?"":to);return r;
 }
 private byte[] csv(List<Map<String,String>> rows)throws Exception{
  var writer=new StringWriter();try(var printer=new CSVPrinter(writer,CSVFormat.RFC4180)){
   printer.printRecord(ImportData.FIELDS);for(var row:rows)printer.printRecord(ImportData.FIELDS.stream().map(row::get).toList());
  }return writer.toString().getBytes(StandardCharsets.UTF_8);
 }
 private JsonNode preview(String name,byte[] bytes)throws Exception{
  return json.readTree(mvc.perform(multipart("/api/v1/pricing/imports/previews")
   .file(new MockMultipartFile("file",name,name.endsWith(".csv")?"text/csv":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes))
   .header("Authorization","Bearer "+admin)).andExpect(result->assertThat(result.getResolvedException()).isNull()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
 }
 private JsonNode confirm(JsonNode p)throws Exception{return ok(post("/api/v1/pricing/imports/previews/"+p.path("id").asText()+"/confirm"),Map.of("confirmed",true));}
 private long count(){return db.queryForObject("select count(*) from price_version",Long.class);}
 @Test void newVersionClosesUnboundedPredecessorAndProtectsHistoricalFields()throws Exception{
  var f=fixture();var old=first(f,"2026-01-01",null);long rid=old.path("recordId").asLong(),id=old.path("id").asLong();
  var next=ok(post("/api/v1/pricing/records/"+rid+"/versions"),version("2026-02-01",null,"234.56"));
  assertThat(next.path("previousVersionId").asLong()).isEqualTo(id);assertThat(next.path("versionNo").asInt()).isEqualTo(2);
  var before=ok(get("/api/v1/pricing/prices/"+id),null);assertThat(before.path("amount").asText()).isEqualTo("123.45");
  assertThat(before.path("effectiveTo").asText()).isEqualTo("2026-01-31");assertThat(before.path("closedByVersionId").asLong()).isEqualTo(next.path("id").asLong());
  assertThat(before.path("sourceId")).isEqualTo(old.path("sourceId"));assertThat(before.path("createdAt")).isEqualTo(old.path("createdAt"));assertThat(before.path("createdBy")).isEqualTo(old.path("createdBy"));
  ok(get("/api/v1/pricing/records/"+rid+"/versions"),null);
  request(put("/api/v1/pricing/prices/"+id),Map.of("amount","9.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HISTORICAL_PRICE_IMMUTABLE"));
  request(delete("/api/v1/pricing/prices/"+id),null).andExpect(status().isConflict());
  assertThatThrownBy(()->db.update("update price_version set amount=1 where id=?",id)).hasMessageContaining("immutable");
  assertThatThrownBy(()->db.update("delete from price_version where id=?",id)).hasMessageContaining("cannot be deleted");
  assertThatThrownBy(()->db.update("update price_version set effective_to=DATE '2026-01-20' where id=?",id)).hasMessageContaining("immutable");
  String audit=json.writeValueAsString(db.queryForList("select * from audit_log where action like 'PRICE_%'"));
  assertThat(audit).contains("PRICE_VERSION_CLOSE","PRICE_VERSION_CREATE").doesNotContain(PASSWORD,SECRET,admin);
 }
 @Test void positivePrecisionDatesScopesAndFiniteOverlapAreRejectedConsistently()throws Exception{
  var f=fixture();
  for(String amount:List.of("0","-1","1.001","10000000000000000.00"))
   request(post("/api/v1/pricing/prices"),price(f,"2026-01-01",null,amount,"NATIONAL","REPAIR_SHOP_RAW_REFERENCE")).andExpect(status().isBadRequest());
  request(post("/api/v1/pricing/prices"),price(f,"2026-02-30",null,"1","NATIONAL","REPAIR_SHOP_RAW_REFERENCE")).andExpect(status().isBadRequest());
  request(post("/api/v1/pricing/prices"),price(f,"2026-02-01","2026-01-01","1","NATIONAL","REPAIR_SHOP_RAW_REFERENCE")).andExpect(status().isBadRequest());
  var wrong=price(f,"2026-01-01",null,"1","NATIONAL","REPAIR_SHOP_RAW_REFERENCE");wrong.put("shopId",shop);request(post("/api/v1/pricing/prices"),wrong).andExpect(status().isBadRequest());
  var first=first(f,"2026-01-01","2026-01-31");long rid=first.path("recordId").asLong();
  for(String from:List.of("2026-01-01","2025-12-31","2026-01-31"))request(post("/api/v1/pricing/records/"+rid+"/versions"),version(from,null,"2.00")).andExpect(status().isConflict());
  var second=ok(post("/api/v1/pricing/records/"+rid+"/versions"),version("2026-02-01","2026-02-28","0.01"));assertThat(second.path("amount").asText()).isEqualTo("0.01");
  request(post("/api/v1/pricing/prices"),price(f,"2026-03-01",null,"1","NATIONAL","REPAIR_SHOP_RAW_REFERENCE")).andExpect(status().isConflict());
  assertThatThrownBy(()->db.update("update price_record set scope='SHOP',shop_id=? where id=?",shop,rid)).hasMessageContaining("immutable");
 }
 @Test void databaseConstraintsRejectNonpositiveInvalidAndOverlappingIntervals()throws Exception{
  var f=fixture();var old=first(f,"2026-01-01","2026-01-31");long actor=old.path("createdBy").asLong();
  String insert="INSERT INTO price_version(record_id,version_no,amount,currency,source_id,source_code,source_name,source_kind,effective_from,effective_to,previous_version_id,created_by) "+
   "SELECT ?,2,?::numeric,'CNY',id,code,name,kind,?::date,?::date,?,? FROM price_source WHERE id=?";
  for(String amount:List.of("0","-0.01"))
   assertThatThrownBy(()->db.update(insert,old.path("recordId").asLong(),amount,"2026-02-01",null,old.path("id").asLong(),actor,source))
    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("check constraint");
  assertThatThrownBy(()->db.update(insert,old.path("recordId").asLong(),"1","2026-02-01","2026-01-31",old.path("id").asLong(),actor,source))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("check constraint");
  assertThatThrownBy(()->db.update(insert,old.path("recordId").asLong(),"1","2026-01-31",null,old.path("id").asLong(),actor,source))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("exclusion constraint");
  assertThatThrownBy(()->db.update(insert,old.path("recordId").asLong(),"1","2026-02-30",null,old.path("id").asLong(),actor,source))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(db.queryForObject("select count(*) from price_version where record_id=?",Long.class,old.path("recordId").asLong())).isEqualTo(1);
  var maximum=ok(post("/api/v1/pricing/prices"),price(f,"2026-01-01",null,"9999999999999999.99","NATIONAL","PLATFORM_EXTERNAL_REFERENCE"));
  assertThat(maximum.path("amount").isTextual()).isTrue();assertThat(maximum.path("amount").asText()).isEqualTo("9999999999999999.99");
 }
 @Test void candidatesShowEveryScopeTypeAndInclusiveDateWithoutPriority()throws Exception{
  var f=fixture();
  for(String type:List.of("REPAIR_SHOP_RAW_REFERENCE","PLATFORM_EXTERNAL_REFERENCE","INSURER_HISTORICAL_ASSESSED","REPAIR_SHOP_SETTLEMENT_REFERENCE"))
   for(String scope:List.of("NATIONAL","REGION","SHOP"))ok(post("/api/v1/pricing/prices"),price(f,"2026-01-01","2026-01-31","10.10",scope,type));
  for(String date:List.of("2026-01-01","2026-01-31")){
   var page=ok(get("/api/v1/pricing/prices").param("internalCode",f.partCode()).param("brandId",Long.toString(f.brand())).param("modelId",Long.toString(f.model())).param("regionId",Long.toString(region)).param("shopId",Long.toString(shop)).param("queryDate",date).param("size","5"),null);
   assertThat(page.path("total").asInt()).isEqualTo(12);assertThat(page.path("records").size()).isEqualTo(5);
   long last=page.path("records").get(4).path("id").asLong();
   var second=ok(get("/api/v1/pricing/prices").param("alias",f.alias()).param("queryDate",date).param("page","2").param("size","5"),null);
   assertThat(second.path("total").asInt()).isEqualTo(12);assertThat(second.path("records").get(0).path("id").asLong()).isLessThan(last);
  }
  var empty=ok(get("/api/v1/pricing/prices").param("internalCode",f.partCode()).param("queryDate","2026-02-01"),null);assertThat(empty.path("total").asInt()).isZero();
  var filtered=ok(get("/api/v1/pricing/prices").param("alias",f.alias()).param("scope","SHOP").param("priceType","PLATFORM_EXTERNAL_REFERENCE"),null);
  assertThat(filtered.path("total").asInt()).isEqualTo(1);assertThat(filtered.path("records").get(0).path("scope").asText()).isEqualTo("SHOP");
  request(get("/api/v1/pricing/prices?size=101"),null).andExpect(status().isBadRequest());request(get("/api/v1/pricing/prices?queryDate=invalid"),null).andExpect(status().isBadRequest());
 }
 @Test void rolesAndPreviewOwnershipEnforceReadWriteBoundaries()throws Exception{
  var f=fixture();var v=first(f,"2026-01-01",null);var p=preview("test.csv",csv(List.of(row(f,"2026-02-01",null,"2.00"))));
  String cs=login("dev_customer_service","ADMIN");
  for(String path:List.of("/prices","/prices/"+v.path("id").asLong(),"/records/"+v.path("recordId").asLong()+"/versions")){
   mvc.perform(get("/api/v1/pricing"+path).header("Authorization","Bearer "+cs)).andExpect(status().isOk());
   for(String role:List.of("repair_shop","owner"))mvc.perform(get("/api/v1/pricing"+path).header("Authorization","Bearer "+login("dev_"+role,"H5"))).andExpect(status().isForbidden());
  }
  for(String path:List.of("/imports/template","/imports/batches"))mvc.perform(get("/api/v1/pricing"+path).header("Authorization","Bearer "+cs)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/pricing/imports/previews/"+p.path("id").asText()).header("Authorization","Bearer "+cs)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/pricing/imports/previews/"+p.path("id").asText()+"/confirm").header("Authorization","Bearer "+cs)
   .contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}")).andExpect(status().isForbidden());
  String account="it_admin_"+UUID.randomUUID().toString().substring(0,8);
  ok(post("/api/v1/admin/users"),Map.of("username",account,"displayName","测试管理员","password",PASSWORD,"roles",List.of("ADMIN")));
  String other=login(account,"ADMIN");
  mvc.perform(get("/api/v1/pricing/imports/previews/"+p.path("id").asText()).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/pricing/imports/previews/"+p.path("id").asText()+"/confirm").header("Authorization","Bearer "+other).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}")).andExpect(status().isForbidden());
  request(post("/api/v1/pricing/imports/previews/"+p.path("id").asText()+"/confirm"),Map.of("confirmed",false)).andExpect(status().isBadRequest());
 }
 @Test void customerServiceCreatesVersionsAndImportsCsvAndExcelWithoutHistoryMutation()throws Exception{
  admin=login("dev_customer_service","ADMIN");var f=fixture();var old=first(f,"2026-01-01",null);
  long rid=old.path("recordId").asLong(),id=old.path("id").asLong();
  ok(post("/api/v1/pricing/records/"+rid+"/versions"),version("2026-02-01",null,"222.22"));
  for(var method:List.of(put("/api/v1/pricing/prices/"+id),patch("/api/v1/pricing/prices/"+id),delete("/api/v1/pricing/prices/"+id)))
   request(method,Map.of("amount","9.00","sourceId",source,"createdBy",1)).andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("HISTORICAL_PRICE_IMMUTABLE"));
  var p=preview("cs.csv",csv(List.of(row(f,"2026-03-01",null,"333.33"))));
  ok(get("/api/v1/pricing/imports/previews/"+p.path("id").asText()+"/summary"),null);
  ok(get("/api/v1/pricing/imports/previews/"+p.path("id").asText()),null);
  var success=confirm(p);assertThat(success.path("status").asText()).isEqualTo("SUCCESS");
  var excel=preview("cs.xlsx",xlsx(row(f,"2026-04-01",null,"444.44")));assertThat(confirm(excel).path("status").asText()).isEqualTo("SUCCESS");
  long before=count();var invalid=preview("cs-invalid.csv",csv(List.of(row(f,"2026-05-01",null,"0"))));
  var failed=confirm(invalid);assertThat(failed.path("status").asText()).isEqualTo("FAILED");assertThat(count()).isEqualTo(before);
  ok(get("/api/v1/pricing/imports/batches"),null);ok(get("/api/v1/pricing/imports/batches/"+success.path("id").asLong()),null);
  assertThat(ok(get("/api/v1/pricing/imports/batches/"+failed.path("id").asLong()+"/errors"),null).path("total").asInt()).isPositive();
  var unchanged=ok(get("/api/v1/pricing/prices/"+id),null);
  for(String field:List.of("amount","sourceId","sourceCode","sourceName","sourceKind","createdAt","createdBy"))assertThat(unchanged.path(field)).isEqualTo(old.path(field));
  assertThat(unchanged.path("effectiveTo").asText()).isEqualTo("2026-01-31");
  long actor=db.queryForObject("select id from app_user where username='dev_customer_service'",Long.class);
  var logs=db.queryForList("select * from audit_log where actor_id=? and action like 'PRICE_%'",actor);
  assertThat(logs).allSatisfy(entry->{assertThat(entry.get("actor_roles")).isEqualTo("CUSTOMER_SERVICE");assertThat(entry.get("object_id")).isNotNull();assertThat(entry.get("trace_id")).isNotNull();});
  assertThat(logs.stream().map(entry->entry.get("action"))).contains("PRICE_CATALOGUE_CREATE","PRICE_APPLICABILITY_CHANGE","PRICE_CREATE","PRICE_VERSION_CREATE","PRICE_VERSION_CLOSE","PRICE_IMPORT_PREVIEW","PRICE_IMPORT_SUCCESS","PRICE_IMPORT_FAILURE");
  assertThat(json.writeValueAsString(logs)).doesNotContain(PASSWORD,SECRET,admin,"$2a$");
  for(String role:List.of("repair_shop","owner")){
   String token=login("dev_"+role,"H5");
   for(String path:List.of("/prices","/records/"+rid+"/versions","/imports/previews/"+p.path("id").asText()+"/confirm"))
    mvc.perform(post("/api/v1/pricing"+path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
   for(String path:List.of("/imports/template","/imports/batches","/imports/batches/"+failed.path("id").asLong()+"/errors"))
    mvc.perform(get("/api/v1/pricing"+path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
   mvc.perform(multipart("/api/v1/pricing/imports/previews").file(new MockMultipartFile("file","test.csv","text/csv",csv(List.of(row(f,"2026-06-01",null,"5"))))).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  }
 }
 private byte[] xlsx(Map<String,String> values)throws Exception{
  try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
   var sheet=book.createSheet("prices");var header=sheet.createRow(0);var row=sheet.createRow(1);
   for(int i=0;i<ImportData.FIELDS.size();i++){header.createCell(i).setCellValue(ImportData.FIELDS.get(i));row.createCell(i).setCellValue(values.get(ImportData.FIELDS.get(i)));}
   book.write(out);return out.toByteArray();
  }
 }
 @Test void csvPreviewIsReadOnlyAndImportAppendsClosesAndIsIdempotent()throws Exception{
  var f=fixture();var old=first(f,"2026-01-01",null);long count=count();
  var p=preview("unicode.csv",csv(List.of(row(f,"2026-03-01",null,"333.33"),row(f,"2026-02-01",null,"222.22"))));
  assertThat(p.path("validRows").asInt()).isEqualTo(2);assertThat(count()).isEqualTo(count);
  var rows=ok(get("/api/v1/pricing/imports/previews/"+p.path("id").asText()),null);assertThat(rows.path("records").size()).isEqualTo(2);
  var batch=confirm(p);assertThat(batch.path("status").asText()).isEqualTo("SUCCESS");assertThat(batch.path("successCount").asInt()).isEqualTo(2);
  assertThat(count()).isEqualTo(count+2);assertThat(confirm(p).path("id")).isEqualTo(batch.path("id"));assertThat(count()).isEqualTo(count+2);
  var history=ok(get("/api/v1/pricing/records/"+old.path("recordId").asLong()+"/versions"),null);
  assertThat(history.path("records").get(1).path("effectiveTo").asText()).isEqualTo("2026-02-28");
  assertThat(history.path("records").get(2).path("effectiveTo").asText()).isEqualTo("2026-01-31");
  assertThat(history.path("records").get(0).path("batchId").asLong()).isEqualTo(batch.path("id").asLong());
  assertThat(db.queryForObject("select count(*) from audit_log where action='PRICE_VERSION_CLOSE' and object_id in(select id::text from price_version where record_id=?)",Long.class,old.path("recordId").asLong())).isEqualTo(2);
 }
 @Test void duplicateAndInvalidRowsFailWholeBatchWithPaginatedErrorReports()throws Exception{
  var f=fixture();long count=count(),records=db.queryForObject("select count(*) from price_record",Long.class);
  var duplicate=preview("duplicate.csv",csv(List.of(row(f,"2026-01-01",null,"1.00"),row(f,"2026-01-01",null,"1.00"))));
  assertThat(duplicate.path("duplicateRows").asInt()).isEqualTo(2);assertThat(confirm(duplicate).path("status").asText()).isEqualTo("FAILED");
  var good=row(f,"2026-01-01",null,"1.00");var bad=row(f,"2026-02-30","2025-01-01","-1");bad.put("alias","不存在别名");
  var p=preview("bad.csv",csv(List.of(good,bad,row(f,"2026-03-01",null,"0"),row(f,"2026-04-01",null,"1.001"))));
  assertThat(p.path("errorRows").asInt()).isEqualTo(3);var batch=confirm(p);
  assertThat(batch.path("failureCount").asInt()).isEqualTo(4);assertThat(batch.path("successCount").asInt()).isZero();
  var report=ok(get("/api/v1/pricing/imports/batches/"+batch.path("id").asLong()+"/errors?size=1"),null);
  assertThat(report.path("total").asInt()).isGreaterThan(3);assertThat(report.path("records").size()).isEqualTo(1);
  assertThat(report.path("records").get(0).path("rowNumber").asInt()).isEqualTo(2);
  assertThat(count()).isEqualTo(count);assertThat(db.queryForObject("select count(*) from price_record",Long.class)).isEqualTo(records);
  ok(get("/api/v1/pricing/imports/batches/"+batch.path("id").asLong()),null);ok(get("/api/v1/pricing/imports/batches?size=1"),null);
 }
 @Test void finiteAndConcurrentImportConflictsPreservePreviousPrice()throws Exception{
  var f=fixture();var old=first(f,"2026-01-01","2026-01-31");
  var overlap=preview("overlap.csv",csv(List.of(row(f,"2026-01-31",null,"2"))));assertThat(overlap.path("errorRows").asInt()).isEqualTo(1);
  assertThat(confirm(overlap).path("status").asText()).isEqualTo("FAILED");
  var p=preview("stale.csv",csv(List.of(row(f,"2026-03-01",null,"3"))));assertThat(p.path("errorRows").asInt()).isZero();
  ok(post("/api/v1/pricing/records/"+old.path("recordId").asLong()+"/versions"),version("2026-04-01",null,"4"));long before=count();
  assertThat(confirm(p).path("status").asText()).isEqualTo("FAILED");assertThat(count()).isEqualTo(before);
  assertThat(ok(get("/api/v1/pricing/prices/"+old.path("id").asLong()),null).path("effectiveTo").asText()).isEqualTo("2026-01-31");
 }
 @Test void databaseFailureRollsBackInsertedPricesAndAutomaticExpiryAudit()throws Exception{
  var a=fixture();var b=fixture();var old=first(a,"2026-01-01",null);
  var p=preview("rollback.csv",csv(List.of(row(a,"2026-02-01",null,"2"),row(b,"2026-01-01",null,"3"))));assertThat(p.path("errorRows").asInt()).isZero();
  db.execute("CREATE FUNCTION test_reject_price() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM price_record WHERE id=NEW.record_id AND part_id="+b.part()+") THEN RAISE EXCEPTION 'Test conflict' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$");
  db.execute("CREATE TRIGGER test_reject_price BEFORE INSERT ON price_version FOR EACH ROW EXECUTE FUNCTION test_reject_price()");
  long before=count(),audits=db.queryForObject("select count(*) from audit_log where action='PRICE_VERSION_CLOSE'",Long.class);
  try{
   var batch=confirm(p);assertThat(batch.path("status").asText()).isEqualTo("FAILED");assertThat(count()).isEqualTo(before);
   assertThat(ok(get("/api/v1/pricing/prices/"+old.path("id").asLong()),null).has("effectiveTo")).isFalse();
   assertThat(db.queryForObject("select count(*) from audit_log where action='PRICE_VERSION_CLOSE'",Long.class)).isEqualTo(audits);
   assertThat(ok(get("/api/v1/pricing/imports/batches/"+batch.path("id").asLong()+"/errors"),null).path("total").asInt()).isEqualTo(2);
  }finally{db.execute("DROP TRIGGER test_reject_price ON price_version");db.execute("DROP FUNCTION test_reject_price()");}
 }
 @Test void xlsxUtf8CsvParsingAndFormatErrorsAreReal()throws Exception{
  var f=fixture();var values=row(f,"2026-01-01",null,"12.34");byte[] bytes;
  try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
   var sheet=book.createSheet("prices");var header=sheet.createRow(0);var row=sheet.createRow(1);
   for(int i=0;i<ImportData.FIELDS.size();i++){header.createCell(i).setCellValue(ImportData.FIELDS.get(i));row.createCell(i).setCellValue(values.get(ImportData.FIELDS.get(i)));}
   book.write(out);bytes=out.toByteArray();
  }
  var p=preview("test.xlsx",bytes);assertThat(p.path("validRows").asInt()).isEqualTo(1);assertThat(confirm(p).path("status").asText()).isEqualTo("SUCCESS");
  var zero=row(f,"2026-02-01",null,"0");
  assertThat(preview("bom.csv",("\uFEFF"+new String(csv(List.of(zero)),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)).path("errorRows").asInt()).isEqualTo(1);
  mvc.perform(multipart("/api/v1/pricing/imports/previews").file(new MockMultipartFile("file","invalid.csv","text/csv",new byte[]{(byte)0xc3,(byte)0x28})).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/v1/pricing/imports/previews").file(new MockMultipartFile("file","unsupported.xls","application/octet-stream",bytes)).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
  request(get("/api/v1/pricing/imports/template"),null).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("effectiveFrom")));
  flyway.validate();
 }
 @Test void concurrentVersionCreationCannotDuplicateDatesOrLoseHistory()throws Exception{
  var f=fixture();var v=first(f,"2026-01-01",null);String path="/api/v1/pricing/records/"+v.path("recordId").asLong()+"/versions";
  String body=json.writeValueAsString(version("2026-02-01",null,"2"));
  try(var executor=Executors.newFixedThreadPool(2)){
   Callable<Integer> call=()->mvc.perform(post(path).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
   var one=executor.submit(call);var two=executor.submit(call);assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
  }
  assertThat(db.queryForObject("select count(*) from price_version where record_id=?",Long.class,v.path("recordId").asLong())).isEqualTo(2);
 }
 @Test void hundredThousandPricesSupportCommonPagedFiltersAndRealQueryPlans()throws Exception{
  var f=fixture();String prefix="DEV-PERF-"+UUID.randomUUID().toString().substring(0,8)+"-";
  long actor=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
  PriceDataFixture.generate(db,prefix,f.model(),actor,source,region,shop);
  assertThat(db.queryForObject("select count(*) from price_record where model_id=?",Long.class,f.model())).isEqualTo(100000);
  var first=ok(get("/api/v1/pricing/prices").param("modelId",Long.toString(f.model())).param("queryDate","2026-06-01").param("size","20"),null);
  assertThat(first.path("total").asInt()).isEqualTo(100000);assertThat(first.path("records").size()).isEqualTo(20);
  var second=ok(get("/api/v1/pricing/prices").param("modelId",Long.toString(f.model())).param("page","2"),null);
  assertThat(second.path("records").get(0).path("id").asLong()).isLessThan(first.path("records").get(19).path("id").asLong());
  for(var filter:List.of(Map.of("internalCode",prefix+"000500"),Map.of("alias","ALIAS-"+prefix+"000500"),Map.of("brandId",Long.toString(f.brand()),"priceType","PLATFORM_EXTERNAL_REFERENCE"),Map.of("regionId",Long.toString(region),"scope","REGION"),Map.of("shopId",Long.toString(shop),"scope","SHOP"))){
   var request=get("/api/v1/pricing/prices").param("modelId",Long.toString(f.model())).param("queryDate","2026-06-01").param("size","10");
   filter.forEach(request::param);var page=ok(request,null);assertThat(page.path("total").asInt()).isPositive();assertThat(page.path("records").size()).isBetween(1,10);
  }
  var plans=new ArrayList<String>();
  plans.addAll(db.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT v.id FROM price_part p JOIN price_record r ON r.part_id=p.id JOIN price_version v ON v.record_id=r.id WHERE p.internal_code=? AND daterange(v.effective_from,v.effective_to,'[]') @> DATE '2026-06-01' ORDER BY v.id LIMIT 20",String.class,prefix+"000500"));
  plans.addAll(db.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT r.id FROM price_alias a JOIN price_record r ON r.part_id=a.part_id WHERE a.name=? AND r.model_id=? ORDER BY r.id LIMIT 20",String.class,"ALIAS-"+prefix+"000500",f.model()));
  assertThat(String.join("\n",plans)).contains("Index","price_part","price_alias");
  Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/price-query-plans.txt"),String.join("\n",plans),StandardCharsets.UTF_8);
  assertThat(db.queryForList("select indexname from pg_indexes where schemaname=? and tablename='price_record'",String.class,SCHEMA)).contains("idx_price_record_model_type","idx_price_record_part_type","idx_price_record_region","idx_price_record_shop","idx_price_record_type");
 }
}
