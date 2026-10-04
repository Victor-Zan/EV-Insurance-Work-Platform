package com.evinsurance.platform.pricing;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.flywaydb.core.Flyway;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class CataloguePostgresIT {
 private static final String PASSWORD=UUID.randomUUID().toString();
 private static final String SCHEMA="price_catalogue_it_"+UUID.randomUUID().toString().replace("-","");
 private static final String SECRET=secret();
 private static String secret(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return Base64.getEncoder().encodeToString(key);}
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
  String url=System.getenv("TEST_DB_URL");
  if(url==null||url.isBlank())throw new IllegalStateException("postgres-it requires a dedicated TEST_DB_URL");
  registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
  registry.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));registry.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
  registry.add("spring.flyway.schemas",()->SCHEMA);registry.add("spring.flyway.default-schema",()->SCHEMA);
  registry.add("JWT_SECRET_BASE64",()->SECRET);registry.add("JWT_TTL_SECONDS",()->"1800");
  for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))registry.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
 }
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;
 private String admin;
 @BeforeEach void authenticate()throws Exception{admin=login("admin");}
 private String login(String role)throws Exception{
  return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
   .content(json.writeValueAsString(Map.of("username","dev_"+role,"password",PASSWORD,"portal",List.of("admin","customer_service").contains(role)?"ADMIN":"H5"))))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();
 }
 private ResultActions request(MockHttpServletRequestBuilder r,Object body)throws Exception{
  r.header("Authorization","Bearer "+admin);if(body!=null)r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  return mvc.perform(r);
 }
 private JsonNode create(String path,Object body)throws Exception{return json.readTree(request(post("/api/v1/pricing/"+path),body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
 private Map<String,Object> named(String code,String name){return Map.of("code",code,"name",name,"enabled",true);}
 @Test void permissionsReuseIdentityWithoutGrantingOrganizationManagement()throws Exception{
  for(String family:List.of("brands","models","parts","aliases","sources","part-models","regions","shops")){
   mvc.perform(get("/api/v1/pricing/"+family)).andExpect(status().isUnauthorized());
   request(get("/api/v1/pricing/"+family),null).andExpect(status().isOk());
   String cs=login("customer_service");
   mvc.perform(get("/api/v1/pricing/"+family).header("Authorization","Bearer "+cs)).andExpect(status().isOk());
   for(var method:List.of(post("/api/v1/pricing/"+family),put("/api/v1/pricing/"+family+"/1"),delete("/api/v1/pricing/"+family+"/1")))
    mvc.perform(method.header("Authorization","Bearer "+cs).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
   for(String role:List.of("repair_shop","owner")){
    String token=login(role);mvc.perform(get("/api/v1/pricing/"+family).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/pricing/"+family).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
   }
  }
  mvc.perform(get("/api/v1/admin/regions").header("Authorization","Bearer "+login("customer_service"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/pricing/brands").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
 }
 @Test void catalogueCrudRelationsUniqueCodesAndAtomicAudit()throws Exception{
  String suffix=UUID.randomUUID().toString().substring(0,8);
  long brand=create("brands",named("TEST-B-"+suffix,"测试品牌")).path("id").asLong();
  long model=create("models",Map.of("brandId",brand,"code","TEST-M","name","测试车型","enabled",true)).path("id").asLong();
  long part=create("parts",Map.of("internalCode","TEST-P-"+suffix,"name","测试配件","enabled",true)).path("id").asLong();
  long alias=create("aliases",Map.of("partId",part,"name","测试别名")).path("id").asLong();
  long source=create("sources",Map.of("code","TEST-S-"+suffix,"name","测试来源","kind","MANUAL","enabled",true)).path("id").asLong();
  create("part-models",Map.of("partId",part,"modelId",model));
  request(get("/api/v1/pricing/part-models?partId="+part),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].modelId").value(model));
  request(get("/api/v1/pricing/models?brandId="+brand),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
  request(get("/api/v1/pricing/aliases?partId="+part+"&name=测试"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
  Long audits=db.queryForObject("select count(*) from audit_log",Long.class);
  request(post("/api/v1/pricing/parts"),Map.of("internalCode","TEST-P-"+suffix,"name","冲突","enabled",true)).andExpect(status().isConflict());
  request(delete("/api/v1/pricing/parts/"+part),null).andExpect(status().isConflict());
  request(post("/api/v1/pricing/models"),Map.of("brandId",99999999,"code","BAD","name","不存在品牌","enabled",true)).andExpect(status().isNotFound());
  assertThat(db.queryForObject("select count(*) from audit_log",Long.class)).isEqualTo(audits);
  request(put("/api/v1/pricing/brands/"+brand),named("TEST-B-"+suffix,"更新品牌")).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("更新品牌"));
  request(put("/api/v1/pricing/models/"+model),Map.of("brandId",brand,"code","TEST-M","name","更新车型","enabled",false)).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
  request(put("/api/v1/pricing/parts/"+part),Map.of("internalCode","TEST-P-"+suffix,"name","更新配件","enabled",true)).andExpect(status().isOk());
  request(put("/api/v1/pricing/aliases/"+alias),Map.of("partId",part,"name","新别名")).andExpect(status().isOk());
  request(put("/api/v1/pricing/sources/"+source),Map.of("code","TEST-S-"+suffix,"name","新来源","kind","MANUAL","enabled",false)).andExpect(status().isOk());
  request(delete("/api/v1/pricing/part-models/"+part+"/"+model),null).andExpect(status().isOk());
  request(delete("/api/v1/pricing/aliases/"+alias),null).andExpect(status().isOk());
  request(delete("/api/v1/pricing/parts/"+part),null).andExpect(status().isOk());
  request(delete("/api/v1/pricing/models/"+model),null).andExpect(status().isOk());
  request(delete("/api/v1/pricing/brands/"+brand),null).andExpect(status().isOk());
  request(delete("/api/v1/pricing/sources/"+source),null).andExpect(status().isOk());
  request(delete("/api/v1/pricing/sources/"+source),null).andExpect(status().isNotFound());
  String logs=json.writeValueAsString(db.queryForList("select * from audit_log"));
  assertThat(logs).contains("PRICE_CATALOGUE_CREATE","PRICE_CATALOGUE_UPDATE","PRICE_CATALOGUE_DELETE","PRICE_APPLICABILITY_CHANGE").doesNotContain(PASSWORD,SECRET,admin,"$2a$");
 }
 @Test void boundedStablePagesLiteralAliasSearchAndExistingOrganizationLookups()throws Exception{
  String suffix=UUID.randomUUID().toString().substring(0,8);
  for(int i=0;i<3;i++)create("parts",Map.of("internalCode","PAGE-"+suffix+"-"+i,"name","分页"+suffix+"-"+i,"enabled",true));
  var first=json.readTree(request(get("/api/v1/pricing/parts?size=2&name=分页"+suffix),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andReturn().getResponse().getContentAsString()).path("data");
  var second=json.readTree(request(get("/api/v1/pricing/parts?page=2&size=2&name=分页"+suffix),null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(first.path("records").size()).isEqualTo(2);assertThat(second.path("records").size()).isEqualTo(1);
  assertThat(second.path("records").get(0).path("id").asLong()).isGreaterThan(first.path("records").get(1).path("id").asLong());
  long part=first.path("records").get(0).path("id").asLong();
  create("aliases",Map.of("partId",part,"name","%literal_"+suffix));
  create("aliases",Map.of("partId",part,"name","other"+suffix));
  request(get("/api/v1/pricing/aliases").param("name","%literal_"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
  request(get("/api/v1/pricing/parts?size=101"),null).andExpect(status().isBadRequest());
  request(get("/api/v1/pricing/parts?page=0"),null).andExpect(status().isBadRequest());
  long shop=db.queryForObject("select id from repair_shop where code='DEV-SHOP'",Long.class);
  request(get("/api/v1/pricing/shops/"+shop+"/service-regions?size=1"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records[0].code").isNotEmpty());
  request(get("/api/v1/pricing/shops"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].contactPhone").doesNotExist());
  flyway.validate();
  mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/pricing/parts'].get.responses['403']").exists());
 }
}
