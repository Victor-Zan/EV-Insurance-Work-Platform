package com.evinsurance.platform.ocr;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.evinsurance.platform.ocr.application.*;
import com.evinsurance.platform.ocr.infrastructure.*;
import com.evinsurance.platform.integration.ocr.*;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

/** Real PostgreSQL/private storage; provider response is synthetic and cannot access Baidu. */
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("dev")
class ShopQuoteOcrPostgresIT {
    static final String PASSWORD=UUID.randomUUID().toString(),SCHEMA="shop_ocr_it_"+UUID.randomUUID().toString().replace("-","");
    static final String SECRET=Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    // Isolate the formal OCR migrations from unrelated local simulation destinations.
    static String testLocations(){
        try{
            var dir=java.nio.file.Files.createTempDirectory("shop-ocr-fixture-");dir.toFile().deleteOnExit();
            var seed=dir.resolve("V2.1__development_foundation.sql");
            try(var input=ShopQuoteOcrPostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")){
                java.nio.file.Files.copy(Objects.requireNonNull(input),seed);
            }
            seed.toFile().deleteOnExit();return "classpath:db/migration,filesystem:"+dir;
        }catch(IOException e){throw new java.io.UncheckedIOException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        String locations=testLocations();r.add("spring.flyway.locations",()->locations);
        String url=Objects.requireNonNull(System.getenv("TEST_DB_URL"));r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        r.add("OCR_WORKER_ENABLED",()->"false");r.add("JWT_SECRET_BASE64",()->SECRET);r.add("JWT_TTL_SECONDS",()->"1800");for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))r.add(name,()->Objects.requireNonNull(System.getenv("TEST_"+name)));
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;@Autowired OcrMapper jobs;@Autowired OcrSources sources;@Autowired OcrTaskStore tasks;@Autowired ObjectStorageService storage;
    @MockitoBean OcrProvider provider;
    String admin,cs,shop,owner;long shopId,region;byte[] image,raw;
    @BeforeEach void setup() throws Exception {
        db.update("DELETE FROM account_rate_limit");
        db.update("UPDATE app_user SET enabled=true WHERE username='dev_repair_shop'");
        when(provider.id()).thenReturn("BAIDU_HANDWRITING_V1");when(provider.api()).thenReturn("ocr/v1/handwriting");when(provider.version()).thenReturn("offline-shop-test");
        raw="{\"log_id\":18446744073709551615,\"words_result\":[{\"words\":\"配件名称：合成轮胎 数量：2 单位：条 单价：12.50 金额：25.00\",\"location\":{\"left\":5,\"top\":5,\"width\":200,\"height\":20},\"probability\":{\"average\":0.8}},{\"words\":\"人工费：30.00\"},{\"words\":\"合计：55.00\"}]}".getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation->new OcrProvider.Result(new HandwritingCandidates().extract(json.readTree(raw),"SHOP_QUOTE"),raw,"18446744073709551615")).when(provider).recognize(anyString(),any(byte[].class),anyBoolean());
        image=image(3);admin=login("dev_admin","ADMIN");cs=login("dev_customer_service","ADMIN");shop=login("dev_repair_shop","H5");owner=login("dev_owner","H5");shopId=db.queryForObject("SELECT id FROM repair_shop WHERE code='DEV-SHOP'",Long.class);region=db.queryForObject("SELECT id FROM region WHERE code='DEV-DISTRICT'",Long.class);
    }
    byte[] image(int pixel) throws Exception {var bitmap=new java.awt.image.BufferedImage(30,30,java.awt.image.BufferedImage.TYPE_INT_RGB);bitmap.setRGB(pixel,pixel,0xffffff);var out=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",out);return out.toByteArray();}
    String login(String name,String portal) throws Exception {return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD,"portal",portal)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asString();}
    JsonNode post(String token,String path,Object body,int statusCode) throws Exception{return post(token,path,body,UUID.randomUUID().toString(),statusCode);}
    JsonNode post(String token,String path,Object body,String key,int code) throws Exception{return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode get(String token,String path,int code) throws Exception{return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/"+path).header("Authorization","Bearer "+token)).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()).path("data");}
    String arrived() throws Exception {
        var body=new HashMap<String,Object>();body.put("insuranceCompany","合成测试保险");body.put("claimNo","SYNTHETIC-"+UUID.randomUUID());body.put("surveyorName","合成查勘");body.put("surveyorPhone","13900000000");body.put("ownerName","合成客户");body.put("ownerPhone","13800000000");body.put("vehicleBrand","合成品牌");body.put("vehicleModel","合成车型");body.put("vehicleVin","SYNTHETIC-"+UUID.randomUUID());body.put("accidentAt","2026-10-15T01:00:00Z");body.put("accidentRegionId",region);body.put("accidentDescription","合成测试事故");
        String id=post(cs,"work-orders/drafts",body,200).path("id").asString();
        mvc.perform(multipart("/api/v1/materials/cases/"+id).file(new MockMultipartFile("file","synthetic-scene.png","image/png",image)).param("category","SCENE_PHOTO").header("Authorization","Bearer "+cs).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());
        var submitted=post(cs,"work-orders/"+id+"/submit",Map.of("confirmPossibleDuplicate",true,"duplicateReason","合成测试"),200);
        mvc.perform(put("/api/v1/work-orders/"+id+"/dispatch-location").header("Authorization","Bearer "+cs).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",submitted.path("version").asLong(),"regionId",region,"address","合成接车地址","confirmed",true)))).andExpect(status().isOk());post(cs,"work-orders/"+id+"/dispatch",Map.of("shopId",shopId,"confirmPossibleDuplicate",true,"duplicateReason","合成测试"),200);post(shop,"work-orders/"+id+"/accept",Map.of("assignmentVersion",1),200);post(shop,"work-orders/"+id+"/arrive",Map.of("assignmentVersion",1),200);
        mvc.perform(multipart("/api/v1/materials/cases/"+id).file(new MockMultipartFile("file","synthetic-arrival.png","image/png",image)).param("category","ARRIVAL_PHOTO").param("assignmentVersion","1").header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());return id;
    }
    String base(String c){return "quotations/cases/"+c+"/ocr";}
    JsonNode upload(String token,String c,int version,String category,byte[] bytes,String key,int code) throws Exception {return json.readTree(mvc.perform(multipart("/api/v1/"+base(c)).file(new MockMultipartFile("file","synthetic.png","image/png",bytes)).param("assignmentVersion",String.valueOf(version)).param("category",category).param("pageNo","1").header("Authorization","Bearer "+token).header("Idempotency-Key",key)).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()).path("data");}
    String upload(String c) throws Exception {return upload(shop,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),200).path("id").asString();}
    void tick(){new OcrWorker(tasks,sources,storage,provider,json,3,180).tick();}
    Map<String,Object> item(String name,String quantity,String price){return Map.of("index",0,"description",name,"quantity",quantity,"unitPrice",price,"unit","条","lineAmount","25.00");}
    JsonNode apply(String c,String j,int revision,int code) throws Exception{return post(shop,base(c)+"/"+j+"/apply",Map.of("expectedVersion",revision,"items",List.of(item("人工核对合成轮胎","2","12.50"))),code);}
    Map<String,Object> rawRequest(int version,String job,int revision){return Map.of("expectedVersion",version,"assignmentVersion",1,"lines",List.of(Map.of("description","人工核对合成轮胎","quantity",2,"unitPrice","12.50","ocr",Map.of("jobId",job,"revision",revision,"itemIndex",0))));}
    @Test void fullShopFlowHumanConfirmationOriginalSubmissionAndImmutableVersionLineage() throws Exception {
        String c=arrived(),j=upload(c);tick();var result=get(shop,base(c)+"/"+j,200);assertThat(result.at("/candidate/items/0/unit").asString()).isEqualTo("条");assertThat(result.at("/candidate/items/1/quantity").asString()).isEmpty();assertThat(result.at("/candidate/items/1/unitPrice").asString()).isEmpty();
        post(shop,"quotations/cases/"+c+"/raw-quotes",rawRequest(0,j,0),409);var confirmed=apply(c,j,0,200);assertThat(confirmed.path("confirmed").asBoolean()).isTrue();assertThat(db.queryForObject("SELECT count(*) FROM quote_raw WHERE work_order_id=?::uuid",Long.class,c)).isZero();
        String key=UUID.randomUUID().toString();var submitted=post(shop,"quotations/cases/"+c+"/raw-quotes",rawRequest(0,j,1),key,200);String quote=submitted.at("/rawQuote/id").asString();assertThat(submitted.at("/rawQuote/total").asString()).isEqualTo("25.00");assertThat(submitted.at("/rawQuote/lines/0/priceSnapshot/source").asString()).isEqualTo("SHOP_OCR_REVIEWED");assertThat(submitted.has("formalQuote")).isFalse();assertThat(submitted.has("assessment")).isFalse();
        assertThat(post(shop,"quotations/cases/"+c+"/raw-quotes",rawRequest(0,j,1),key,200).at("/rawQuote/id").asString()).isEqualTo(quote);
        post(shop,"quotations/cases/"+c+"/raw-quotes",Map.of("expectedVersion",1,"assignmentVersion",1,"lines",List.of(Map.of("description","人工修订新版本","quantity",1,"unitPrice","30.00"))),200);
        assertThat(db.queryForObject("SELECT count(*) FROM quote_raw WHERE work_order_id=?::uuid",Long.class,c)).isEqualTo(2);assertThat(db.queryForObject("SELECT count(*) FROM quote_raw_ocr_line WHERE raw_quote_id=?::uuid",Long.class,quote)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE quote_raw_item SET unit_price=1 WHERE raw_quote_id=?::uuid",quote)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(get(shop,base(c)+"/"+j+"/raw",200).path("text").asString()).isEqualTo(new String(raw,StandardCharsets.UTF_8));assertThat(get(shop,base(c)+"/"+j+"/program",200).at("/items/0/description").asString()).isEqualTo("合成轮胎");
        var audit=json.writeValueAsString(db.queryForList("SELECT summary FROM audit_log WHERE object_id=?",j));assertThat(audit).doesNotContain("人工核对合成轮胎","access_token","13800000000","words_result");flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("32");
    }
    @Test void providerSwitchReusesOriginalButCreatesNewJobAndKeepsHistoricalEvidence() throws Exception {
        String c=arrived(),key=UUID.randomUUID().toString();when(provider.id()).thenReturn("MOCK_V1");
        String old=upload(shop,c,1,"SHOP_QUOTE",image,key,200).path("id").asString();tick();
        var original=jobs.find(UUID.fromString(old));var historical=get(shop,base(c)+"/"+old+"/program",200);
        when(provider.id()).thenReturn("BAIDU_HANDWRITING_V1");
        String current=upload(shop,c,1,"SHOP_QUOTE",image,key,200).path("id").asString();
        assertThat(current).isNotEqualTo(old);assertThat(jobs.find(UUID.fromString(current)).fileId()).isEqualTo(original.fileId());
        assertThat(jobs.find(UUID.fromString(current)).provider()).isEqualTo("BAIDU_HANDWRITING_V1");
        assertThat(upload(shop,c,1,"SHOP_QUOTE",image,key,200).path("id").asString()).isEqualTo(current);
        assertThat(upload(shop,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),200).path("id").asString()).isEqualTo(current);
        assertThat(get(shop,base(c)+"/"+current,200).path("pageNo").asInt()).isEqualTo(1);
        tick();assertThat(get(shop,base(c)+"/"+old+"/program",200)).isEqualTo(historical);
        assertThat(db.queryForObject("SELECT count(*) FROM shop_quote_ocr_scope WHERE work_order_id=?::uuid",Long.class,c)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM case_file WHERE work_order_id=?::uuid AND category='SHOP_QUOTE'",Long.class,c)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM shop_quote_ocr_command WHERE command_key=?",Long.class,key)).isEqualTo(2);
        when(provider.id()).thenReturn("MOCK_V1");assertThat(upload(shop,c,1,"SHOP_QUOTE",image,key,200).path("id").asString()).isEqualTo(old);
        verify(provider,times(2)).recognize(anyString(),any(byte[].class),anyBoolean());
    }
    @Test void concurrentFirstUploadsShareTaskAndCrossCaseCommandReuseIsFriendlyConflict() throws Exception {
        String first=arrived(),second=arrived(),key=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<String> upload=()->upload(shop,first,1,"SHOP_QUOTE",image,key,200).path("id").asString();
            var a=pool.submit(upload);var b=pool.submit(upload);assertThat(a.get()).isEqualTo(b.get());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM shop_quote_ocr_scope WHERE work_order_id=?::uuid",Long.class,first)).isEqualTo(1);
        upload(shop,second,1,"SHOP_QUOTE",image,key,409);
        assertThat(db.queryForObject("SELECT count(*) FROM shop_quote_ocr_scope WHERE work_order_id=?::uuid",Long.class,second)).isZero();
        String racedKey=UUID.randomUUID().toString();
        java.util.function.Function<String,Callable<Integer>> attempt=id->()->mvc.perform(multipart("/api/v1/"+base(id)).file(new MockMultipartFile("file","synthetic.png","image/png",image)).param("assignmentVersion","1").param("category","SHOP_QUOTE").param("pageNo","1").header("Authorization","Bearer "+shop).header("Idempotency-Key",racedKey)).andReturn().getResponse().getStatus();
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(attempt.apply(first));var b=pool.submit(attempt.apply(second));assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);}
        assertThat(db.queryForObject("SELECT count(*) FROM shop_quote_ocr_command WHERE command_key=?",Long.class,racedKey)).isEqualTo(1);
        verify(provider,never()).recognize(anyString(),any(byte[].class),anyBoolean());
    }
    @Test void migrationBackfillsProviderWithoutChangingOldTaskReviewsOrAppendOnlyProtection(){
        String schema="ocr_provider_migration_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("TEST_DB_URL"),username=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).target("30").load().migrate();
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,username,password);
        var old=new JdbcTemplate(source);UUID c=UUID.randomUUID(),file=UUID.randomUUID(),job=UUID.randomUUID();String sha="a".repeat(64);
        old.update("INSERT INTO app_user(id,username,display_name,password_hash) VALUES(1,'synthetic','合成迁移测试','not-a-login-hash')");
        old.update("INSERT INTO repair_shop(id,code,name) VALUES(1,'SYNTHETIC','合成测试网点')");
        old.update("INSERT INTO work_order(id,current_responsible_id,created_by,updated_by) VALUES(?,1,1,1)",c);
        old.update("INSERT INTO case_file(id,work_order_id,group_id,category,version_no,state,object_key,original_name,content_type,byte_size,sha256,shop_id,assignment_version,uploaded_by) VALUES(?,?,?,'SHOP_QUOTE',1,'ACTIVE','synthetic-migration','synthetic.png','image/png',1,?,1,1,1)",file,c,file,sha);
        old.update("INSERT INTO ocr_job(id,file_id,provider,state,candidate_json,created_by) VALUES(?,?,'MOCK_V1','SUCCEEDED','{}',1)",job,file);
        old.update("INSERT INTO shop_quote_ocr_scope(job_id,work_order_id,shop_id,assignment_version,file_sha256,category,page_no) VALUES(?,?,1,1,?,'SHOP_QUOTE',1)",job,c,sha);
        old.update("INSERT INTO shop_quote_ocr_command(actor_id,command_key,request_hash,job_id) VALUES(1,'original-key',?,?)",sha,job);
        old.update("INSERT INTO ocr_review(job_id,revision,candidate_json,actor_id,command_key) VALUES(?,0,'{}',1,'original-review')",job);
        old.update("INSERT INTO ocr_confirmation(job_id,revision,actor_id) VALUES(?,0,1)",job);
        Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(old.queryForObject("SELECT provider FROM shop_quote_ocr_scope WHERE job_id=?",String.class,job)).isEqualTo("MOCK_V1");
        assertThat(old.queryForObject("SELECT provider FROM shop_quote_ocr_command WHERE job_id=?",String.class,job)).isEqualTo("MOCK_V1");
        assertThat(old.queryForObject("SELECT candidate_json FROM ocr_review WHERE job_id=?",String.class,job)).isEqualTo("{}");
        assertThat(old.queryForObject("SELECT count(*) FROM ocr_confirmation WHERE job_id=?",Long.class,job)).isEqualTo(1);
        assertThatThrownBy(()->old.update("UPDATE shop_quote_ocr_scope SET page_no=2 WHERE job_id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->old.update("DELETE FROM shop_quote_ocr_command WHERE job_id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID real=UUID.randomUUID();old.update("INSERT INTO ocr_job(id,file_id,provider,state,created_by) VALUES(?,?,'BAIDU_HANDWRITING_V1','QUEUED',1)",real,file);
        old.update("INSERT INTO shop_quote_ocr_scope(job_id,work_order_id,shop_id,assignment_version,file_sha256,category,page_no,provider) VALUES(?,?,1,1,?,'SHOP_QUOTE',1,'BAIDU_HANDWRITING_V1')",real,c,sha);
        old.update("INSERT INTO shop_quote_ocr_command(actor_id,command_key,request_hash,job_id,provider) VALUES(1,'original-key',?,?,'BAIDU_HANDWRITING_V1')",sha,real);
        assertThatThrownBy(()->old.update("INSERT INTO ocr_job(id,file_id,provider,state,created_by) VALUES(?,?,'BAIDU_HANDWRITING_V1','QUEUED',1)",UUID.randomUUID(),file)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(old.queryForObject("SELECT count(*) FROM shop_quote_ocr_scope",Long.class)).isEqualTo(2);
    }
    @Test void roleCaseAssignmentAndFileCategoryBoundaries() throws Exception {
        String c=arrived(),j=upload(c);tick();for(String token:List.of(admin,cs)){get(token,base(c)+"/"+j,200);upload(token,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),403);post(token,base(c)+"/"+j+"/retry",Map.of(),403);post(token,base(c)+"/"+j+"/apply",Map.of("expectedVersion",0,"items",List.of(item("x","2","12.50"))),403);}
        get(owner,base(c)+"/"+j,403);get(shop,"ocr/"+j,403);post(shop,"ocr/intake",Map.of(),403);
        for(String category:List.of("NOTICE","LOSS_ASSESSMENT","ARRIVAL_PHOTO"))upload(shop,c,1,category,image,UUID.randomUUID().toString(),400);
        upload(shop,c,2,"SHOP_QUOTE",image,UUID.randomUUID().toString(),403);String other=arrived();get(shop,base(other)+"/"+j,403);
        long second=db.queryForObject("INSERT INTO repair_shop(code,name,enabled) VALUES(?, '合成其他网点',true) RETURNING id",Long.class,"SYN-"+UUID.randomUUID().toString().substring(0,16));String username="synthetic_shop_"+UUID.randomUUID().toString().replace("-","");post(admin,"admin/users",Map.of("username",username,"displayName","合成其他网点账号","roles",List.of("REPAIR_SHOP"),"shopId",second,"password",PASSWORD),200);String otherShop=login(username,"H5");get(otherShop,base(c)+"/"+j,403);upload(otherShop,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),403);
    }
    @Test void duplicatesAndConcurrentClicksDoNotCreateMoreTasksOrConfirmations() throws Exception {
        String c=arrived(),key=UUID.randomUUID().toString();String j=upload(shop,c,1,"SHOP_QUOTE",image,key,200).path("id").asString();assertThat(upload(shop,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),200).path("id").asString()).isEqualTo(j);
        upload(shop,c,1,"SHOP_QUOTE",image(4),key,409);upload(shop,c,1,"SHOP_QUOTE",new byte[]{1,2,3},UUID.randomUUID().toString(),400);
        try(var pool=Executors.newFixedThreadPool(2)){Callable<String> same=()->upload(shop,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),200).path("id").asString();var a=pool.submit(same);var b=pool.submit(same);assertThat(a.get()).isEqualTo(j);assertThat(b.get()).isEqualTo(j);}
        assertThat(db.queryForObject("SELECT count(*) FROM shop_quote_ocr_scope WHERE work_order_id=?::uuid",Long.class,c)).isEqualTo(1);tick();String command=UUID.randomUUID().toString();var body=Map.of("expectedVersion",0,"items",List.of(item("人工核对合成轮胎","2","12.50")));post(shop,base(c)+"/"+j+"/apply",body,command,200);post(shop,base(c)+"/"+j+"/apply",body,command,200);assertThat(db.queryForObject("SELECT count(*) FROM ocr_confirmation WHERE job_id=?::uuid",Long.class,j)).isEqualTo(1);verify(provider,times(1)).recognize(anyString(),any(byte[].class),anyBoolean());
    }
    @Test void sourceVoidedFrozenAndOldAssignmentCannotUseResults() throws Exception {
        for(String changed:List.of("COMPLETED","CANCELLED","REPAIRING")){String c=arrived(),j=upload(c);tick();db.update("UPDATE work_order SET status=? WHERE id=?::uuid",changed,c);get(shop,base(c)+"/"+j,409);apply(c,j,0,409);get(cs,base(c)+"/"+j,200);}
        String c=arrived(),j=upload(c);tick();var f=jobs.find(UUID.fromString(j));post(cs,"materials/"+f.fileId()+"/void",Map.of(),200);get(shop,base(c)+"/"+j,403);apply(c,j,0,403);
        String reassigned=arrived(),old=upload(reassigned);tick();db.update("UPDATE work_order_assignment SET status='CANCELLED' WHERE work_order_id=?::uuid",reassigned);get(shop,base(reassigned)+"/"+old,403);
    }
    @Test void failureStillAllowsManualQuoteAndUnconfirmedOrTamperedSourcesReject() throws Exception {
        String c=arrived(),j=upload(c);doThrow(new OcrFailure("BAIDU_QUOTA")).when(provider).recognize(anyString(),any(byte[].class),anyBoolean());tick();assertThat(get(shop,base(c)+"/"+j,200).path("errorCode").asString()).isEqualTo("BAIDU_QUOTA");post(shop,base(c)+"/"+j+"/retry",Map.of("expectedVersion",0),200);tick();assertThat(get(shop,base(c)+"/"+j,200).path("rerunsUsed").asInt()).isEqualTo(1);
        post(shop,"quotations/cases/"+c+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",1,"lines",List.of(Map.of("description","纯手填项目","quantity",1,"unitPrice","8.00"))),200);
        String c2=arrived(),j2=upload(c2);doAnswer(invocation->new OcrProvider.Result(new HandwritingCandidates().extract(json.readTree(raw),"SHOP_QUOTE"),raw,"18446744073709551615")).when(provider).recognize(anyString(),any(byte[].class),anyBoolean());tick();apply(c2,j2,0,200);
        var body=rawRequest(0,j2,1);var line=new HashMap<>((Map<String,Object>)((List<?>)body.get("lines")).getFirst());line.put("unitPrice","99.00");post(shop,"quotations/cases/"+c2+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",1,"lines",List.of(line)),409);
        post(shop,"quotations/cases/"+c2+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",1,"lines",List.of(body.get("lines") instanceof List<?> list?list.getFirst():Map.of(),body.get("lines") instanceof List<?> list?list.getFirst():Map.of())),409);
        assertThat(db.queryForObject("SELECT count(*) FROM quote_raw WHERE work_order_id=?::uuid",Long.class,c2)).isZero();
    }
    @Test void workerRechecksSourceDuringCallAndLeaseExpiryNeverCallsAgain() throws Exception {
        String c=arrived(),j=upload(c);doAnswer(invocation->{db.update("UPDATE case_file SET state='VOID' WHERE id=(SELECT file_id FROM ocr_job WHERE id=?::uuid)",j);return new OcrProvider.Result(Map.of("items",List.of()),raw,"1");}).when(provider).recognize(anyString(),any(byte[].class),anyBoolean());tick();assertThat(get(cs,base(c)+"/"+j,200).path("errorCode").asString()).isEqualTo("SOURCE_ACCESS_CHANGED");assertThat(db.queryForObject("SELECT count(*) FROM ocr_raw_result WHERE job_id=?::uuid",Long.class,j)).isZero();
        String c2=arrived(),j2=upload(c2);var claim=tasks.claim(3,180);assertThat(tasks.claim(3,180)).isNull();db.update("UPDATE ocr_job SET lease_until=now()-interval '1 second' WHERE id=?::uuid",j2);assertThat(tasks.claim(3,180)).isNull();tasks.finish(claim,"{}",null);assertThat(get(shop,base(c2)+"/"+j2,200).path("errorCode").asString()).isEqualTo("BAIDU_LEASE_POSSIBLY_CHARGED");verify(provider,times(1)).recognize(anyString(),any(byte[].class),anyBoolean());
    }
    @Test void simultaneousHumanSnapshotsOnlyOneVersionWinsAndDisabledCreatorCannotInvokeProvider() throws Exception {
        String c=arrived(),j=upload(c);tick();
        try(var pool=Executors.newFixedThreadPool(2)){Callable<Integer> apply=()->mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/"+base(c)+"/"+j+"/apply").header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",0,"items",List.of(item("人工核对合成轮胎","2","12.50")))))).andReturn().getResponse().getStatus();var a=pool.submit(apply);var b=pool.submit(apply);assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);}
        String c2=arrived(),j2=upload(c2);db.update("UPDATE app_user SET enabled=false WHERE username='dev_repair_shop'");tick();db.update("UPDATE app_user SET enabled=true WHERE username='dev_repair_shop'");assertThat(get(cs,base(c2)+"/"+j2,200).path("errorCode").asString()).isEqualTo("SOURCE_ACCESS_CHANGED");verify(provider,times(1)).recognize(anyString(),any(byte[].class),anyBoolean());
    }
    @Test void explicitAssessmentAndConfirmedRevisionMustStayInCurrentAssignment() throws Exception {
        String c=arrived(),j=upload(shop,c,1,"ASSESSMENT",image,UUID.randomUUID().toString(),200).path("id").asString();tick();apply(c,j,0,200);assertThat(get(cs,base(c)+"/"+j,200).path("shopQuote").asBoolean()).isTrue();
        db.update("UPDATE work_order_assignment SET status='CANCELLED' WHERE work_order_id=?::uuid",c);
        db.update("INSERT INTO work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by,assigned_at) VALUES(?::uuid,2,?,'ACCEPTED',(SELECT id FROM app_user WHERE username='dev_customer_service'),now())",c,shopId);
        get(shop,base(c)+"/"+j,403);post(shop,"quotations/cases/"+c+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",2,"lines",((List<?>)rawRequest(0,j,1).get("lines"))),403);
        String document=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(document).contains("/api/v1/quotations/cases/{caseId}/ocr","/api/v1/quotations/cases/{caseId}/ocr/{job}/apply");
    }
    @Test void threeManualRerunsAreIdempotentAndPreserveOriginalQuoteHistory() throws Exception {
        String c=arrived(),j=upload(c);
        var worker=new OcrWorker(tasks,sources,storage,provider,json,1,180);worker.tick();
        apply(c,j,0,200);
        var quote=post(shop,"quotations/cases/"+c+"/raw-quotes",rawRequest(0,j,1),200);
        String snapshot=db.queryForObject("SELECT candidate_json FROM ocr_review WHERE job_id=?::uuid AND revision=1",String.class,j);
        for(int n=1;n<=3;n++){
            int expected=n;String key=UUID.randomUUID().toString();var body=Map.of("expectedVersion",expected);
            if(n==1){
                try(var pool=Executors.newFixedThreadPool(2)){
                    Callable<JsonNode> same=()->post(shop,base(c)+"/"+j+"/retry",body,key,200);
                    var a=pool.submit(same);var b=pool.submit(same);
                    assertThat(a.get().path("rerunsUsed").asInt()).isEqualTo(1);
                    assertThat(b.get().path("rerunsUsed").asInt()).isEqualTo(1);
                }
            }else post(shop,base(c)+"/"+j+"/retry",body,key,200);
            post(shop,base(c)+"/"+j+"/retry",body,key,200);
            post(shop,base(c)+"/"+j+"/retry",Map.of("expectedVersion",expected+1),409);
            assertThat(upload(shop,c,1,"SHOP_QUOTE",image,UUID.randomUUID().toString(),200).path("rerunsUsed").asInt()).isEqualTo(n);
            worker.tick();worker.tick(); // No automatic extra external call, even with queued command history.
            var result=get(shop,base(c)+"/"+j,200);
            assertThat(result.path("state").asString()).isEqualTo("SUCCEEDED");
            assertThat(result.path("reviewVersion").asInt()).isEqualTo(n+1);
            assertThat(result.path("attempts").asInt()).isEqualTo(n+1);
            assertThat(result.path("rerunsUsed").asInt()).isEqualTo(n);
            var restored=get(shop,base(c)+"?assignmentVersion=1&page=1&size=20",200);
            assertThat(restored.path("records").size()).isEqualTo(1);
            assertThat(restored.at("/records/0/rerunsUsed").asInt()).isEqualTo(n);
            get(owner,base(c)+"?assignmentVersion=1",403);
            get(shop,base(c)+"?assignmentVersion=2",403);
        }
        post(shop,base(c)+"/"+j+"/retry",Map.of("expectedVersion",4),409);
        post(shop,base(c)+"/"+j+"/retry",Map.of("expectedVersion",0),409);
        post(cs,base(c)+"/"+j+"/retry",Map.of("expectedVersion",4),403);
        String other=arrived();post(shop,base(other)+"/"+j+"/retry",Map.of("expectedVersion",4),403);
        assertThat(db.queryForObject("SELECT candidate_json FROM ocr_review WHERE job_id=?::uuid AND revision=1",String.class,j)).isEqualTo(snapshot);
        assertThat(db.queryForObject("SELECT revision FROM quote_raw_ocr_line WHERE raw_quote_id=?::uuid",Integer.class,quote.at("/rawQuote/id").asString())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM ocr_raw_result WHERE job_id=?::uuid",Integer.class,j)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM ocr_confirmation WHERE job_id=?::uuid AND revision=1",Integer.class,j)).isEqualTo(1);
        assertThatThrownBy(()->db.update("DELETE FROM shop_quote_ocr_rerun WHERE job_id=?::uuid",j)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        when(provider.id()).thenReturn("MOCK_V1");
        String switched=upload(c);worker=new OcrWorker(tasks,sources,storage,provider,json,1,180);worker.tick();
        assertThat(get(shop,base(c)+"/"+switched,200).path("rerunsUsed").asInt()).isEqualTo(3);
        post(shop,base(c)+"/"+switched+"/retry",Map.of("expectedVersion",0),409);
        verify(provider,times(5)).recognize(anyString(),any(byte[].class),anyBoolean());flyway.validate();
    }
    @Test void separateImagesKeepServerPageAndSourceIdentityWithoutMergingSameNamedRows() throws Exception {
        String c=arrived(),j1=upload(c);String j2=upload(shop,c,1,"SHOP_QUOTE",image(4),UUID.randomUUID().toString(),200).path("id").asString();assertThat(j2).isNotEqualTo(j1);tick();tick();
        assertThat(get(shop,base(c)+"/"+j1,200).path("pageNo").asInt()).isEqualTo(1);assertThat(get(shop,base(c)+"/"+j2,200).path("pageNo").asInt()).isEqualTo(2);
        assertThat(upload(shop,c,1,"SHOP_QUOTE",image(4),UUID.randomUUID().toString(),200).path("pageNo").asInt()).isEqualTo(2);
        apply(c,j1,0,200);apply(c,j2,0,200);var first=((List<?>)rawRequest(0,j1,1).get("lines")).getFirst();var second=((List<?>)rawRequest(0,j2,1).get("lines")).getFirst();
        var result=post(shop,"quotations/cases/"+c+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",1,"lines",List.of(first,second)),200);assertThat(result.at("/rawQuote/lines").size()).isEqualTo(2);assertThat(result.at("/rawQuote/total").asString()).isEqualTo("50.00");verify(provider,times(2)).recognize(anyString(),any(byte[].class),anyBoolean());
    }
}
