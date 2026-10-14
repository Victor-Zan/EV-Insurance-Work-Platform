package com.evinsurance.platform.ocr;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.evinsurance.platform.ocr.application.*;
import com.evinsurance.platform.ocr.infrastructure.*;
import com.evinsurance.platform.integration.ocr.*;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

/** Real PostgreSQL + private MinIO. The vendor alone is stubbed; no live OCR quota is consumed. */
@SpringBootTest @ActiveProfiles("dev")
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class OcrIntakePostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString(),SCHEMA="ocr_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String SECRET=Base64.getEncoder().encodeToString((UUID.randomUUID().toString()+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        String url=System.getenv("TEST_DB_URL");if(url==null)throw new IllegalStateException("Dedicated PostgreSQL test environment required");
        r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        r.add("OCR_WORKER_ENABLED",()->"false");r.add("JWT_SECRET_BASE64",()->SECRET);r.add("JWT_TTL_SECONDS",()->"1800");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))r.add(name,()->{String v=System.getenv("TEST_"+name);if(v==null)throw new IllegalStateException("Real private MinIO required");return v;});
    }
    @MockitoBean(name="selectedOcrProvider") OcrProvider provider;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;@Autowired OcrTaskStore tasks;@Autowired OcrMapper jobs;@Autowired OcrSources sources;@Autowired ObjectStorageService storage;
    private String cs,admin,shop,owner;private byte[] image;
    @Test void mainV15UpgradesToOcrV16WithoutChangingItsChecksum(){
        String schema="ocr_upgrade_it_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("TEST_DB_URL"),user=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        try {
            Flyway mainOnly=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("15").load();
            mainOnly.migrate();mainOnly.validate();
            assertThat(mainOnly.info().current().getVersion().toString()).isEqualTo("15");
            Integer checksum=db.queryForObject("SELECT checksum FROM "+schema+".flyway_schema_history WHERE version='15'",Integer.class);
            assertThat(db.queryForObject("SELECT count(*) FROM "+schema+".owner_contact_event",Long.class)).isZero();
            assertThat(db.queryForObject("SELECT to_regclass(?) IS NULL",Boolean.class,schema+".ocr_intake_file")).isTrue();
            Flyway integrated=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
            integrated.migrate();integrated.validate();
            assertThat(integrated.info().current().getVersion().toString()).isEqualTo("16");
            assertThat(db.queryForObject("SELECT checksum FROM "+schema+".flyway_schema_history WHERE version='15'",Integer.class)).isEqualTo(checksum);
            assertThat(db.queryForObject("SELECT count(*) FROM "+schema+".ocr_intake_file",Long.class)).isZero();
        } finally {db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
    private OcrWorker worker(){return new OcrWorker(tasks,sources,storage,provider,json,3,180);}
    @BeforeEach void setup() throws Exception {
        db.update("UPDATE app_user SET enabled=true WHERE username='dev_customer_service'");
        when(provider.id()).thenReturn("BAIDU_HANDWRITING_V1");when(provider.api()).thenReturn("ocr/v1/handwriting");when(provider.version()).thenReturn("adapter-test");
        byte[] raw="{\"log_id\":18446744073709551615,\"words_result\":[{\"words\":\"客户姓名：合成客户甲\",\"location\":{\"left\":10,\"top\":10,\"width\":80,\"height\":15},\"probability\":{\"average\":0.8}},{\"words\":\"维修单号：SYNTHETIC-DOC\"}]}".getBytes(StandardCharsets.UTF_8);
        when(provider.recognize(anyString(),any(byte[].class),eq(false))).thenReturn(new OcrProvider.Result(new HandwritingCandidates().extract(json.readTree(raw),"SHOP_QUOTE"),raw,"18446744073709551615"));
        var bitmap=new java.awt.image.BufferedImage(30,30,java.awt.image.BufferedImage.TYPE_INT_RGB);bitmap.setRGB(15,15,0xffffff);var out=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",out);image=out.toByteArray();
        cs=login("dev_customer_service","ADMIN");admin=login("dev_admin","ADMIN");shop=login("dev_repair_shop","H5");owner=login("dev_owner","H5");
    }
    private String login(String name,String portal) throws Exception {return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD,"portal",portal)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asString();}
    private JsonNode command(String token,String path,Object body,int statusCode) throws Exception {return json.readTree(mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode upload(String token,String key,int statusCode) throws Exception {return json.readTree(mvc.perform(multipart("/api/v1/ocr/intake").file(new MockMultipartFile("file","synthetic.png","image/png",image)).header("Authorization","Bearer "+token).header("Idempotency-Key",key)).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode getJob(String token,String id,int statusCode) throws Exception {return json.readTree(mvc.perform(get("/api/v1/ocr/"+id).header("Authorization","Bearer "+token)).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString()).path("data");}
    @Test void noCaseUploadPrefillCorrectionConfirmedDraftAndOriginalFormalSubmit() throws Exception {
        long before=db.queryForObject("SELECT count(*) FROM work_order",Long.class);String key=UUID.randomUUID().toString();var queued=upload(cs,key,200);String id=queued.path("id").asString();
        assertThat(upload(cs,key,200).path("id").asString()).isEqualTo(id);assertThat(db.queryForObject("SELECT count(*) FROM work_order",Long.class)).isEqualTo(before);
        worker().tick();var ready=getJob(cs,id,200);assertThat(ready.path("provider").asString()).isEqualTo("BAIDU_HANDWRITING_V1");assertThat(ready.path("candidate").path("fields").path("ownerName").asString()).isEqualTo("合成客户甲");assertThat(ready.path("candidate").path("fields").has("claimNo")).isFalse();
        var draft=new LinkedHashMap<String,Object>();draft.put("insuranceCompany","合成测试保险");draft.put("claimNo","SYNTHETIC-"+UUID.randomUUID());draft.put("ownerName","人工修正合成客户");draft.put("ownerPhone","13800000000");draft.put("vehicleBrand","合成品牌");draft.put("vehicleModel","合成车型");draft.put("vehicleVin","SYNTHETIC-"+UUID.randomUUID());draft.put("accidentAt","2026-10-13T01:00:00Z");draft.put("accidentRegionId",db.queryForObject("SELECT id FROM region WHERE code='DEV-DISTRICT'",Long.class));draft.put("accidentDescription","合成测试事故");
        var candidate=(tools.jackson.databind.node.ObjectNode)ready.path("candidate").deepCopy();candidate.set("draft",json.valueToTree(draft));
        command(cs,"ocr/"+id+"/draft",Map.of("expectedVersion",0,"draft",draft),409);
        command(cs,"ocr/"+id+"/review",Map.of("expectedVersion",0,"candidate",candidate),200);command(cs,"ocr/"+id+"/confirm",Map.of("expectedVersion",1),200);
        assertThat(db.queryForObject("SELECT count(*) FROM work_order",Long.class)).isEqualTo(before);
        var created=command(cs,"ocr/"+id+"/draft",Map.of("expectedVersion",1,"draft",draft),200);assertThat(created.path("status").asString()).isEqualTo("DRAFT");assertThat(created.path("ownerName").asString()).isEqualTo("人工修正合成客户");
        assertThat(command(cs,"ocr/"+id+"/draft",Map.of("expectedVersion",1,"draft",draft),200).path("id")).isEqualTo(created.path("id"));
        var formal=command(cs,"work-orders/"+created.path("id").asString()+"/submit",Map.of("confirmPossibleDuplicate",true,"duplicateReason","合成验收"),200);assertThat(formal.path("status").asString()).isEqualTo("PENDING_DISPATCH");assertThat(formal.path("businessNo").asString()).startsWith("EVR-");
        var raw=json.readTree(mvc.perform(get("/api/v1/ocr/"+id+"/raw").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(raw.path("logId").asString()).isEqualTo("18446744073709551615");assertThat(raw.path("text").asString()).contains("合成客户甲").doesNotContain("人工修正");
        assertThat(db.queryForObject("SELECT count(*) FROM ocr_draft_link WHERE job_id=?::uuid",Long.class,id)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM ocr_review WHERE job_id=?::uuid",Long.class,id)).isEqualTo(2);flyway.validate();
        assertThatThrownBy(()->db.update("UPDATE ocr_raw_result SET log_id='changed' WHERE job_id=?::uuid",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForList("SELECT summary FROM audit_log WHERE object_id=?",String.class,id).toString()).doesNotContain("合成客户甲","13800000000","access_token","words_result");
    }
    @Test void fourRolesAndAnotherCustomerServiceCannotCrossIntakeScope() throws Exception {
        String id=upload(cs,UUID.randomUUID().toString(),200).path("id").asString();worker().tick();getJob(admin,id,200);
        for(String token:List.of(admin,shop,owner))upload(token,UUID.randomUUID().toString(),403);
        for(String token:List.of(shop,owner)){getJob(token,id,403);mvc.perform(get("/api/v1/ocr/"+id+"/raw").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());}
        command(admin,"ocr/"+id+"/review",Map.of("expectedVersion",0,"candidate",Map.of()),403);command(admin,"ocr/"+id+"/confirm",Map.of("expectedVersion",0),403);
        String username="synthetic_cs_"+UUID.randomUUID().toString().replace("-","");command(admin,"admin/users",Map.of("username",username,"displayName","合成其他客服","password",PASSWORD,"roles",List.of("CUSTOMER_SERVICE")),200);String other=login(username,"ADMIN");getJob(other,id,403);mvc.perform(get("/api/v1/ocr/"+id+"/image").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
    }
    @Test void realProviderLeaseExpiryNeverAutomaticallyReissuesAndStaleFinishCannotPublish() throws Exception {
        String id=upload(cs,UUID.randomUUID().toString(),200).path("id").asString();var first=tasks.claim(3,180);assertThat(tasks.claim(3,180)).isNull();db.update("UPDATE ocr_job SET lease_until=now()-interval '1 second' WHERE id=?::uuid",id);
        assertThat(tasks.claim(3,180)).isNull();assertThat(getJob(cs,id,200).path("errorCode").asString()).isEqualTo("BAIDU_LEASE_POSSIBLY_CHARGED");tasks.finish(first,"{}",null);assertThat(getJob(cs,id,200).path("state").asString()).isEqualTo("FAILED");
        command(cs,"ocr/"+id+"/retry",Map.of(),200);var retry=tasks.claim(3,180);tasks.finish(retry,null,"BAIDU_QUOTA");command(cs,"ocr/"+id+"/retry",Map.of(),409);verify(provider,never()).recognize(anyString(),any(byte[].class),anyBoolean());
    }
    @Test void disabledCreatorHashMismatchAndFrozenCasePreventResultPublication() throws Exception {
        String id=upload(cs,UUID.randomUUID().toString(),200).path("id").asString();db.update("UPDATE app_user SET enabled=false WHERE username='dev_customer_service'");worker().tick();db.update("UPDATE app_user SET enabled=true WHERE username='dev_customer_service'");assertThat(getJob(cs,id,200).path("state").asString()).isEqualTo("FAILED");verify(provider,never()).recognize(anyString(),any(byte[].class),anyBoolean());
        String corrupt=upload(cs,UUID.randomUUID().toString(),200).path("id").asString();var row=jobs.find(UUID.fromString(corrupt));var source=sources.source(row,false);storage.put(source.objectKey(),new ByteArrayInputStream(new byte[]{1}),1,"image/png");worker().tick();assertThat(getJob(cs,corrupt,200).path("errorCode").asString()).isEqualTo("SOURCE_HASH_MISMATCH");
        var draft=command(cs,"work-orders/drafts",Map.of("insuranceCompany","合成保险","claimNo","SYNTHETIC-"+UUID.randomUUID(),"ownerName","合成客户","ownerPhone","13800000000","vehicleBrand","合成品牌","vehicleModel","合成车型","vehicleVin","SYNTHETIC-"+UUID.randomUUID(),"accidentAt","2026-10-13T01:00:00Z","accidentRegionId",db.queryForObject("SELECT id FROM region WHERE code='DEV-DISTRICT'",Long.class),"accidentDescription","合成事故"),200);String caseId=draft.path("id").asString();command(cs,"work-orders/"+caseId+"/submit",Map.of("confirmPossibleDuplicate",true,"duplicateReason","合成验收"),200);var file=json.readTree(mvc.perform(multipart("/api/v1/materials/cases/"+caseId).file(new MockMultipartFile("file","synthetic.png","image/png",image)).param("category","NOTICE").header("Authorization","Bearer "+cs).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var job=command(cs,"ocr",Map.of("fileId",file.path("id").asString()),200);var claimed=tasks.claim(3,180);db.update("UPDATE work_order SET status='COMPLETED' WHERE id=?::uuid",caseId);tasks.finish(claimed,"{}",null);assertThat(getJob(cs,job.path("id").asString(),200).path("errorCode").asString()).isEqualTo("SOURCE_ACCESS_CHANGED");
    }
    @Test void concurrentHumanReviewsOneWinsAndConfirmIsIdempotent() throws Exception {
        String id=upload(cs,UUID.randomUUID().toString(),200).path("id").asString();worker().tick();
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Integer> review=()->mvc.perform(post("/api/v1/ocr/"+id+"/review").header("Authorization","Bearer "+cs).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"candidate\":{\"fields\":{\"ownerName\":\"合成修正\"}}}")).andReturn().getResponse().getStatus();var a=pool.submit(review);var b=pool.submit(review);assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
            Callable<Integer> confirm=()->mvc.perform(post("/api/v1/ocr/"+id+"/confirm").header("Authorization","Bearer "+cs).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andReturn().getResponse().getStatus();var c=pool.submit(confirm);var d=pool.submit(confirm);assertThat(c.get()).isEqualTo(200);assertThat(d.get()).isEqualTo(200);
        }assertThat(db.queryForObject("SELECT count(*) FROM ocr_confirmation WHERE job_id=?::uuid",Long.class,id)).isEqualTo(1);
    }
    @Test void localReextractionAppendsProgramHistoryWithoutCallingVendorAgain() throws Exception {
        String id=upload(cs,UUID.randomUUID().toString(),200).path("id").asString();worker().tick();long before=db.queryForObject("SELECT count(*) FROM work_order",Long.class);
        var updated=command(cs,"ocr/"+id+"/reextract",Map.of("expectedVersion",0),200);assertThat(updated.path("reviewVersion").asInt()).isEqualTo(1);assertThat(updated.path("candidate").path("rawAttempt").asInt()).isEqualTo(1);assertThat(updated.path("confirmed").asBoolean()).isFalse();verify(provider,times(1)).recognize(anyString(),any(byte[].class),eq(false));
        command(cs,"ocr/"+id+"/review",Map.of("expectedVersion",1,"candidate",Map.of("origin","PROGRAM_LABELLED_TEXT_V2","fields",Map.of("ownerName","合成人工修改"))),200);
        assertThat(getJob(cs,id,200).path("candidate").path("origin").asString()).isEqualTo("HUMAN_REVISION");
        var program=json.readTree(mvc.perform(get("/api/v1/ocr/"+id+"/program").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(program.path("fields").path("ownerName").asString()).isEqualTo("合成客户甲");
        assertThat(db.queryForObject("SELECT count(*) FROM ocr_review WHERE job_id=?::uuid",Long.class,id)).isEqualTo(3);assertThat(db.queryForObject("SELECT count(*) FROM ocr_raw_result WHERE job_id=?::uuid",Long.class,id)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM work_order",Long.class)).isEqualTo(before);command(admin,"ocr/"+id+"/reextract",Map.of("expectedVersion",2),403);
    }
}
