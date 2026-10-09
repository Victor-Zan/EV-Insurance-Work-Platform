package com.evinsurance.platform.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class MaterialsPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SECRET=secret();
    private static final String SCHEMA="materials_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String OWNER_PHONE="13800001234";

    private static String secret() {
        byte[] key=new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url=System.getenv("TEST_DB_URL");
        String username=System.getenv("TEST_DB_USERNAME");
        String password=System.getenv("TEST_DB_PASSWORD");
        if(url==null||url.isBlank()||username==null||username.isBlank()||password==null) {
            throw new IllegalStateException("postgres-it requires a dedicated TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD");
        }
        registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
        registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password);
        registry.add("spring.flyway.schemas",()->SCHEMA);
        registry.add("spring.flyway.default-schema",()->SCHEMA);
        registry.add("OCR_WORKER_ENABLED",()->"false");
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET")) registry.add(name,()->{String value=System.getenv("TEST_"+name);if(value==null||value.isBlank())throw new IllegalStateException("Real MinIO test configuration required");return value;});
        registry.add("JWT_SECRET_BASE64",()->SECRET);
        registry.add("JWT_TTL_SECONDS",()->"1800");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")) {
            registry.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;

    private String admin;
    private String customerService;
    private String shop;
    private String owner;
    private long regionId;
    private long shopId;

    @BeforeEach
    void authenticate() throws Exception {
        admin=login("dev_admin","ADMIN");
        customerService=login("dev_customer_service","ADMIN");
        shop=login("dev_repair_shop","H5");
        owner=login("dev_owner","H5");
        regionId=db.queryForObject("select id from region where code='DEV-DISTRICT'",Long.class);
        shopId=db.queryForObject("select id from repair_shop where code='DEV-SHOP'",Long.class);
        db.update("update work_order_config set possible_duplicate_days=7 where id=1");
        db.update("update owner_profile set contact_phone=? where user_id=(select id from app_user where username='dev_owner')",OWNER_PHONE);
    }

    private String login(String username,String portal) throws Exception {
        String body=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password",PASSWORD,"portal",portal))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("accessToken").asText();
    }

    private JsonNode createUser(String role,Long assignedShop) throws Exception {
        String username="it_"+UUID.randomUUID().toString().replace("-","");
        Map<String,Object> request=new HashMap<>();
        request.put("username",username);
        request.put("displayName","集成测试账号");
        request.put("password",PASSWORD);
        request.put("roles",List.of(role));
        if(assignedShop!=null) request.put("shopId",assignedShop);
        String body=mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+admin)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private long createEligibleShop() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String body=mvc.perform(post("/api/v1/admin/shops").header("Authorization","Bearer "+admin)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","第二测试网点","code","IT-"+suffix,"enabled",true))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(body).path("data").path("id").asLong();
        mvc.perform(put("/api/v1/admin/shops/{id}/service-regions",id).header("Authorization","Bearer "+admin)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("regionIds",List.of(regionId)))))
            .andExpect(status().isOk());
        return id;
    }

    private Map<String,Object> requiredDraft(String claimNo,String phone,String vin,Instant accidentAt) {
        Map<String,Object> request=new HashMap<>();
        request.put("insuranceCompany","平安保险");
        request.put("claimNo",claimNo);
        request.put("ownerName","王小明");
        request.put("ownerPhone",phone);
        request.put("policyNo","POL-"+claimNo);
        request.put("vehicleBrand","测试品牌");
        request.put("vehicleModel","测试车型");
        request.put("vehicleVin",vin);
        request.put("accidentAt",accidentAt.toString());
        request.put("accidentRegionId",regionId);
        request.put("accidentDescription","低速碰撞测试");
        return request;
    }

    private JsonNode createDraft(String token,Map<String,Object> request) throws Exception {
        String body=mvc.perform(post("/api/v1/work-orders/drafts").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private JsonNode submit(String token,String id,boolean confirm,String reason,String key,int expectedStatus) throws Exception {
        Map<String,Object> request=new HashMap<>();
        request.put("confirmPossibleDuplicate",confirm);
        if(reason!=null) request.put("duplicateReason",reason);
        String body=mvc.perform(post("/api/v1/work-orders/{id}/submit",id).header("Authorization","Bearer "+token)
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().is(expectedStatus))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode dispatch(String token,String id,long targetShop,boolean confirm,String reason,String key,int expectedStatus) throws Exception {
        Map<String,Object> request=new HashMap<>();
        request.put("shopId",targetShop);
        request.put("confirmPossibleDuplicate",confirm);
        if(reason!=null) request.put("duplicateReason",reason);
        String body=mvc.perform(post("/api/v1/work-orders/{id}/dispatch",id).header("Authorization","Bearer "+token)
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().is(expectedStatus))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode formal(String token,String claim,String phone,String vin,Instant accidentAt) throws Exception {
        String id=createDraft(token,requiredDraft(claim,phone,vin,accidentAt)).path("id").asText();
        return submit(token,id,false,null,"submit-"+UUID.randomUUID(),200).path("data");
    }

    private int assignmentVersion(JsonNode order) {
        return order.path("currentAssignment").path("assignmentVersion").asInt();
    }

    @Autowired com.evinsurance.platform.ocr.application.OcrTaskStore tasks;
    @Autowired com.evinsurance.platform.document.infrastructure.FileMapper files;
    @Autowired com.evinsurance.platform.integration.storage.ObjectStorageService storage;
    @Autowired com.evinsurance.platform.integration.ocr.OcrProvider provider;
    private String caseId() throws Exception{return formal(customerService,"FILE-"+UUID.randomUUID(),OWNER_PHONE,"VIN-"+UUID.randomUUID(),Instant.now()).path("id").asText();}
    private JsonNode upload(String token,String id,String category,String key,String replace,Integer version,int expected) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id)
            .file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf","%PDF-1.7\nsynthetic desensitized material\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .header("Authorization","Bearer "+token).header("Idempotency-Key",key).param("category",category);
        if(replace!=null)request.param("replace",replace);if(version!=null)request.param("assignmentVersion",version.toString());
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode command(String token,String path,Object body,int expected) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void realObjectsPermissionsHistoryAndReadonly() throws Exception {
        String id=caseId();var notice=upload(customerService,id,"NOTICE","notice-"+id,null,null,200);String file=notice.path("id").asText();
        assertThat(upload(customerService,id,"NOTICE","notice-"+id,null,null,200)).isEqualTo(notice);
        var stored=files.find(UUID.fromString(file));try(var stream=storage.read(stored.objectKey())){assertThat(com.evinsurance.platform.document.application.FileService.hash(stream.readAllBytes())).isEqualTo(stored.sha256());}
        mvc.perform(get("/api/v1/materials/{id}/download",file).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/materials/{id}/download",file).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
        upload(admin,id,"NOTICE",UUID.randomUUID().toString(),null,null,403);
        var assigned=dispatch(customerService,id,shopId,true,"合成文件测试",UUID.randomUUID().toString(),200).path("data");int version=assignmentVersion(assigned);
        var photo=upload(shop,id,"ARRIVAL_PHOTO",UUID.randomUUID().toString(),null,version,200);
        mvc.perform(get("/api/v1/materials/{id}/download",photo.path("id").asText()).header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        upload(shop,id,"NOTICE",UUID.randomUUID().toString(),null,version,403);upload(shop,id,"ARRIVAL_PHOTO",UUID.randomUUID().toString(),null,version+1,403);
        long secondShop=createEligibleShop();var secondUser=createUser("REPAIR_SHOP",secondShop);String other=login(secondUser.path("username").asText(),"H5");
        mvc.perform(get("/api/v1/materials/cases/{id}",id).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        var replacement=upload(customerService,id,"ARRIVAL_PHOTO",UUID.randomUUID().toString(),photo.path("id").asText(),null,200);assertThat(replacement.path("version").asInt()).isEqualTo(2);
        mvc.perform(get("/api/v1/materials/{id}/download",photo.path("id").asText()).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/materials/{id}/download",photo.path("id").asText()).header("Authorization","Bearer "+customerService)).andExpect(status().isOk());
        command(customerService,"materials/"+replacement.path("id").asText()+"/void",Map.of(),200);
        assertThat(db.queryForObject("select count(*) from case_file where work_order_id=?::uuid",Long.class,id)).isEqualTo(3);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("delete from case_file where id=?::uuid",file));
        for(String state:List.of("COMPLETED","CANCELLED")){db.update("update work_order set status=? where id=?::uuid",state,id);upload(customerService,id,"NOTICE",UUID.randomUUID().toString(),null,null,409);command(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成缺失"),409);mvc.perform(put("/api/v1/work-orders/{id}/critical-fields",id).header("Authorization","Bearer "+customerService).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("insuranceCompany","测试","claimNo","changed","vehicleVin","VIN","reason","只读测试")))).andExpect(status().isConflict());}
        assertThat(db.queryForObject("select count(*) from audit_log where action='FILE_UPLOAD' and object_id=?",Long.class,file)).isEqualTo(1);
        mvc.perform(put("/api/v1/work-orders/{id}/critical-fields",id).header("Authorization","Bearer "+customerService).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("insuranceCompany","测试","claimNo","changed","vehicleVin","VIN","reason","只读测试")))).andExpect(status().isConflict());
        flyway.validate();
    }
    @Test void limitsTypesAndIdempotentConcurrency() throws Exception {
        String id=caseId();mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf","%PDF-1.7".getBytes())).header("Authorization","Bearer "+customerService).param("category","NOTICE")).andExpect(status().isBadRequest());String first=upload(customerService,id,"NOTICE","first-"+id,null,null,200).path("id").asText();
        upload(customerService,id,"SHOP_QUOTE","first-"+id,null,null,409);
        for(int i=1;i<20;i++)upload(customerService,id,"NOTICE",UUID.randomUUID().toString(),null,null,200);
        upload(customerService,id,"NOTICE",UUID.randomUUID().toString(),null,null,409);upload(customerService,id,"NOTICE",UUID.randomUUID().toString(),first,null,200);
        assertThat(db.queryForObject("select count(*) from case_file where work_order_id=?::uuid and state='ACTIVE'",Long.class,id)).isEqualTo(20);
        var invalid=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","fake.png","image/png","not an image".getBytes())).header("Authorization","Bearer "+customerService).header("Idempotency-Key","invalid-"+id).param("category","NOTICE");mvc.perform(invalid).andExpect(status().isBadRequest());
        var large=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","large.pdf","application/pdf",new byte[10485761])).header("Authorization","Bearer "+customerService).header("Idempotency-Key","large-"+id).param("category","NOTICE");mvc.perform(large).andExpect(status().isBadRequest());
        String concurrent=caseId(),key=UUID.randomUUID().toString();try(var pool=Executors.newFixedThreadPool(2)){var futures=pool.invokeAll(List.<Callable<String>>of(()->upload(customerService,concurrent,"NOTICE",key,null,null,200).path("id").asText(),()->upload(customerService,concurrent,"NOTICE",key,null,null,200).path("id").asText()));assertThat(futures.get(0).get()).isEqualTo(futures.get(1).get());}
    }
    @Test void ocrFailureRetryHumanSnapshotsAndLeases() throws Exception {
        String id=caseId();String file=upload(customerService,id,"NOTICE",UUID.randomUUID().toString(),null,null,200).path("id").asText();
        var job=command(customerService,"ocr",Map.of("fileId",file,"simulateFailure",true),200);String jobId=job.path("id").asText();assertThat(command(customerService,"ocr",Map.of("fileId",file),200).path("id").asText()).isEqualTo(jobId);
        var claimed=tasks.claim(3,60);assertThat(claimed.id().toString()).isEqualTo(jobId);assertThat(tasks.claim(3,60)).isNull();tasks.finish(claimed,null,"PROVIDER_OR_STORAGE_FAILURE");
        command(customerService,"ocr/"+jobId+"/retry",Map.of(),200);var next=tasks.claim(3,60);var f=files.find(UUID.fromString(file));byte[] data;try(var stream=storage.read(f.objectKey())){data=stream.readAllBytes();}tasks.finish(next,json.writeValueAsString(provider.extract(f.category(),data,false)),null);
        mvc.perform(get("/api/v1/ocr/{id}",jobId).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        command(admin,"ocr/"+jobId+"/confirm",Map.of("expectedVersion",0),403);
        var revised=command(customerService,"ocr/"+jobId+"/review",Map.of("expectedVersion",0,"candidate",Map.of("claimNo","人工修正候选")),200);assertThat(revised.path("confirmed").asBoolean()).isFalse();
        command(customerService,"ocr/"+jobId+"/confirm",Map.of("expectedVersion",0),409);command(customerService,"ocr/"+jobId+"/confirm",Map.of("expectedVersion",1),200);
        assertThat(db.queryForObject("select count(*) from ocr_review where job_id=?::uuid",Long.class,jobId)).isEqualTo(2);assertThat(db.queryForObject("select claim_no from work_order where id=?::uuid",String.class,id)).doesNotContain("人工修正");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update ocr_review set candidate_json='{}' where job_id=?::uuid",jobId));
        String second=upload(customerService,id,"ASSESSMENT",UUID.randomUUID().toString(),null,null,200).path("id").asText();command(customerService,"ocr",Map.of("fileId",second),200);var expired=tasks.claim(3,60);db.update("update ocr_job set lease_until=now()-interval '1 second' where id=?",expired.id());var reclaimed=tasks.claim(3,60);tasks.finish(expired,"{}",null);assertThat(db.queryForObject("select state from ocr_job where id=?",String.class,reclaimed.id())).isEqualTo("RUNNING");tasks.finish(reclaimed,"{}",null);
        String frozenFile=upload(customerService,id,"LOSS_ASSESSMENT",UUID.randomUUID().toString(),null,null,200).path("id").asText();var frozenJob=command(customerService,"ocr",Map.of("fileId",frozenFile),200);var inFlight=tasks.claim(3,60);db.update("update work_order set status='COMPLETED' where id=?::uuid",id);tasks.finish(inFlight,"{}",null);assertThat(db.queryForObject("select state from ocr_job where id=?::uuid",String.class,frozenJob.path("id").asText())).isEqualTo("RUNNING");assertThat(tasks.claim(3,60)).isNull();command(customerService,"ocr/"+jobId+"/review",Map.of("expectedVersion",1,"candidate",Map.of()),409);
    }
    @Test void mapMockScopeAndValidation() throws Exception {
        mvc.perform(get("/api/v1/maps/search?query=合成地址").header("Authorization","Bearer "+customerService)).andExpect(status().isOk());
        for(String token:List.of(admin,shop,owner))mvc.perform(get("/api/v1/maps/geocode?address=test").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/maps/reverse?latitude=91&longitude=114").header("Authorization","Bearer "+customerService)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/maps/distance?fromLat=22&fromLon=114&toLat=22&toLon=114").header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.metres").value(0)).andExpect(jsonPath("$.data.mock").value(true));
    }
    @Test void bindingAssignmentChangesMissingReasonsAndDeletedDraftAreEnforced() throws Exception {
        String id=caseId();var assigned=dispatch(customerService,id,shopId,true,"权限追溯测试",UUID.randomUUID().toString(),200).path("data");int version=assignmentVersion(assigned);
        var photo=upload(shop,id,"PROGRESS_PHOTO",UUID.randomUUID().toString(),null,version,200);String file=photo.path("id").asText();
        var unrelated=createUser("OWNER",null);String unrelatedToken=login(unrelated.path("username").asText(),"H5");mvc.perform(get("/api/v1/materials/{id}/download",file).header("Authorization","Bearer "+unrelatedToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/materials/cases/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].name").value(org.hamcrest.Matchers.startsWith("progress_photo-"))).andExpect(jsonPath("$.data.records[0].objectKey").doesNotExist()).andExpect(jsonPath("$.data.records[0].sha256").doesNotExist());
        command(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成缺失原因"),200);upload(customerService,id,"NOTICE",UUID.randomUUID().toString(),null,null,200);
        mvc.perform(get("/api/v1/materials/cases/{id}/notice-status",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.noticePresent").value(true)).andExpect(jsonPath("$.data.missingReason").value("合成缺失原因"));
        long other=createEligibleShop();command(customerService,"work-orders/"+id+"/reassign",Map.of("shopId",other,"assignmentVersion",version,"reason","改派验证","vehicleNotArrivedConfirmed",true,"confirmPossibleDuplicate",true,"duplicateReason","合成验证"),200);
        mvc.perform(get("/api/v1/materials/{id}/download",file).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        String draft=createDraft(customerService,requiredDraft("DRAFT-"+UUID.randomUUID(),OWNER_PHONE,"VIN-"+UUID.randomUUID(),Instant.now())).path("id").asText();String retained=upload(customerService,draft,"NOTICE",UUID.randomUUID().toString(),null,null,200).path("id").asText();
        mvc.perform(delete("/api/v1/work-orders/{id}/draft",draft).header("Authorization","Bearer "+customerService)).andExpect(status().isOk());mvc.perform(get("/api/v1/materials/{id}/download",retained).header("Authorization","Bearer "+customerService)).andExpect(status().isNotFound());assertThat(files.find(UUID.fromString(retained))).isNotNull();
    }}
