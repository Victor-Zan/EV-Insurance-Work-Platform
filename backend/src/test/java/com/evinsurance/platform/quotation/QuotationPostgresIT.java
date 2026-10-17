package com.evinsurance.platform.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class QuotationPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SECRET=secret();
    private static final String SCHEMA="quotation_it_"+UUID.randomUUID().toString().replace("-","");
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
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))registry.add(name,()->{String value=System.getenv("TEST_"+name);if(value==null||value.isBlank())throw new IllegalStateException("Real MinIO test settings required");return value;});
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

    @org.springframework.beans.factory.annotation.Autowired private com.evinsurance.platform.notification.application.NotificationProjector notificationProjector;
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
        request.put("surveyorName","合成查勘");request.put("surveyorPhone","13926018473");request.put("vehicleColor","蓝色");
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
        var draft=json.readTree(body).path("data");
        if(request.containsKey("surveyorName"))scenePhoto(token,draft.path("id").asText());
        return draft;
    }

    private String scenePhoto(String token,String id) throws Exception {
        byte[] image=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jFWkAAAAASUVORK5CYII=");
        var body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic-scene.png","image/png",image)).param("category","SCENE_PHOTO").header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("id").asText();
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
        if(expectedStatus!=403){var row=json.readTree(mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");if(row.path("status").asText().equals("PENDING_DISPATCH")&&!row.path("dispatchAddressConfirmed").asBoolean())mvc.perform(put("/api/v1/work-orders/{id}/dispatch-location",id).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",row.path("version").asLong(),"regionId",regionId,"address","合成接车地址","confirmed",true)))).andExpect(status().isOk());}

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

    private JsonNode request(String token,String path,Object body,String key,int expected) throws Exception {
        String text=mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return json.readTree(text).path("data");
    }
    private JsonNode request(String token,String path,Object body,int expected) throws Exception{return request(token,path,body,UUID.randomUUID().toString(),expected);}
    private String arrived() throws Exception {
        String id=formal(customerService,"QUOTE-"+UUID.randomUUID(),OWNER_PHONE,"VIN-"+UUID.randomUUID(),Instant.now()).path("id").asText();
        var dispatched=dispatch(customerService,id,shopId,true,"合成报价测试",UUID.randomUUID().toString(),200).path("data");int version=assignmentVersion(dispatched);
        request(shop,"work-orders/"+id+"/accept",Map.of("assignmentVersion",version),200);request(shop,"work-orders/"+id+"/arrive",Map.of("assignmentVersion",version),200);return id;
    }
    private JsonNode context(String token,String id) throws Exception{return json.readTree(mvc.perform(get("/api/v1/quotations/cases/{id}",id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    private String path(String id,String suffix){return "quotations/cases/"+id+"/"+suffix;}
    private Map<String,Object> rawBody(String id,List<Map<String,Object>> lines) throws Exception {var c=context(shop,id);return Map.of("expectedVersion",c.path("version").asInt(),"assignmentVersion",c.path("assignmentVersion").asInt(),"lines",lines);}
    private JsonNode raw(String id,String price) throws Exception{return request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","原始配件","quantity",3,"unitPrice",price))),200);}
    private JsonNode review(String id,String mode,String value,int expected) throws Exception {var c=context(customerService,id);return request(customerService,path(id,"formal-quotes"),Map.of("expectedVersion",c.path("version").asInt(),"rawQuoteId",c.path("rawQuote").path("id").asText(),"mode",mode,mode.equals("FIXED_AMOUNT")?"fixedAmount":"percentage",value),expected);}
    private JsonNode assess(String id,String amount,int expected) throws Exception {
        var current=context(customerService,id);
        var available=db.queryForList("select id from case_file where work_order_id=?::uuid and category='LOSS_ASSESSMENT' and state='ACTIVE' order by created_at desc,id desc limit 1",id);
        if(expected!=200||!current.hasNonNull("formalQuote")||available.isEmpty())return request(customerService,path(id,"assessments"),Map.of("expectedVersion",current.path("version").asInt(),"amount",amount),expected);
        var job=request(customerService,"ocr",Map.of("fileId",available.getFirst().get("id").toString()),200);
        if("QUEUED".equals(job.path("state").asText())){var claimed=ocrTasks.claim(3,60);assertThat(claimed.id().toString()).isEqualTo(job.path("id").asText());ocrTasks.finish(claimed,json.writeValueAsString(Map.of("mock",true,"amountCandidate",amount)),null);}
        String jobId=job.path("id").asText();int revision=job.path("reviewVersion").asInt();
        var reviewed=request(customerService,"ocr/"+jobId+"/review",Map.of("expectedVersion",revision,"candidate",Map.of("originalReviewed",true,"adopted",amount)),200);
        int confirmedRevision=reviewed.path("reviewVersion").asInt();request(customerService,"ocr/"+jobId+"/confirm",Map.of("expectedVersion",confirmedRevision),200);
        return request(customerService,path(id,"recognized-assessment"),Map.of("jobId",jobId,"revision",confirmedRevision,"assessment",Map.of("expectedVersion",current.path("version").asInt(),"amount",amount)),expected);
    }
    private JsonNode confirm(String id,String kind) throws Exception {
        var c=context(customerService,id);var body=new HashMap<String,Object>();body.put("expectedVersion",c.path("version").asInt());
        if("CUSTOMER_SERVICE".equals(kind)){body.put("assessmentId",c.path("assessment").path("id").asText());body.put("lossFileId",c.path("assessment").path("lossFileId").asText());body.put("amount",c.path("assessment").path("amount").asText());body.put("originalReviewed",true);}
        return request(customerService,path(id,"confirmations/"+kind),body,200);
    }
    private JsonNode start(String id,int expected) throws Exception {var c=context(shop,id);return request(shop,path(id,"start-repair"),Map.of("expectedVersion",c.path("version").asInt(),"assignmentVersion",c.path("assignmentVersion").asInt()),expected);}
    private String upload(String id,String category,String replace,int expected) throws Exception {
        var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf","%PDF-1.7\nSYNTHETIC\n%%EOF".getBytes())).param("category",category).header("Authorization","Bearer "+customerService).header("Idempotency-Key",UUID.randomUUID().toString());if(replace!=null)req.param("replace",replace);
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
    }
    @Test void customerReviewKeepsRawPricesAndOriginalsImmutableAndUsesCorrectedTotals() throws Exception {
        String id=arrived();int assignment=context(shop,id).path("assignmentVersion").asInt();
        quoteOriginal(id,assignment,"first.png");quoteOriginal(id,assignment,"second.png");
        upload(id,"SHOP_QUOTE",null,200); // Staff material must not be attributed to this shop submission.
        var raw=raw(id,"10.00");String rawId=raw.path("rawQuote").path("id").asText();
        var c=context(customerService,id);
        assertThat(c.path("rawQuote").path("originals").size()).isEqualTo(2);
        quoteOriginal(id,assignment,"later.png");
        assertThat(context(customerService,id).path("rawQuote").path("originals").size()).isEqualTo(2);
        var body=new HashMap<String,Object>(Map.of("expectedVersion",c.path("version").asInt(),"rawQuoteId",rawId,"mode","PERCENTAGE","percentage","7.50","reviewedLines",List.of(Map.of("description","原件核对配件","quantity",2,"unitPrice","15.25"))));
        for(String token:List.of(shop,admin,owner))request(token,path(id,"formal-quotes"),body,403);
        var invalid=new HashMap<>(body);invalid.put("reviewedLines",List.of(Map.of("description","核对配件","quantity",0,"unitPrice","15.25")));request(customerService,path(id,"formal-quotes"),invalid,400);
        invalid.put("reviewedLines",List.of(Map.of("description","核对配件","quantity",1,"unitPrice","1.001")));request(customerService,path(id,"formal-quotes"),invalid,400);
        assertThat(db.queryForObject("select count(*) from quote_formal_review",Long.class)).isZero();
        String key=UUID.randomUUID().toString();var result=request(customerService,path(id,"formal-quotes"),body,key,200);
        assertThat(result.path("formalQuote").path("originalTotal").asText()).isEqualTo("30.50");
        assertThat(result.path("formalQuote").path("markupAmount").asText()).isEqualTo("2.29");
        assertThat(result.path("formalQuote").path("total").asText()).isEqualTo("32.79");
        assertThat(result.path("formalQuote").path("reviewedLines").get(0).path("description").asText()).isEqualTo("原件核对配件");
        assertThat(result.path("rawQuote").path("total").asText()).isEqualTo("30.00");
        assertThat(db.queryForObject("select unit_price from quote_raw_item where raw_quote_id=?::uuid",java.math.BigDecimal.class,rawId)).isEqualByComparingTo("10.00");
        assertThat(request(customerService,path(id,"formal-quotes"),body,key,200)).isEqualTo(result);
        assertThat(db.queryForObject("select count(*) from quote_formal_review",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from audit_log where action='QUOTE_REVIEW'",Long.class)).isEqualTo(1);
        assertThat(context(shop,id).has("formalQuote")).isFalse();
        request(customerService,path(id,"formal-quotes"),body,409);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update quote_formal_review set adopted_json='[]'"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("delete from quote_raw_file where raw_quote_id=?::uuid",rawId));
        flyway.validate();
    }
    private void quoteOriginal(String id,int assignment,String name) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id)
            .file(new org.springframework.mock.web.MockMultipartFile("file",name,"image/png",Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5S8AAAAASUVORK5CYII=")))
            .param("category","SHOP_QUOTE").param("assignmentVersion",String.valueOf(assignment))
            .header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());
    }
    @Test void assessmentAndConfirmationsCannotSkipEarlierWorkflowSteps() throws Exception {
        String id=arrived();
        assess(id,"1.00",409);
        request(customerService,path(id,"confirmations/INSURER"),Map.of("expectedVersion",context(customerService,id).path("version").asInt()),409);
        assertThat(db.queryForObject("select count(*) from quote_assessment where work_order_id=?::uuid",Long.class,id)).isZero();
        assertThat(db.queryForObject("select count(*) from quotation_confirmation where work_order_id=?::uuid",Long.class,id)).isZero();
    }
    @Test void futureStageMaterialsAreRejectedWithoutStoringFiles() throws Exception {
        String id=arrived();
        upload(id,"LOSS_ASSESSMENT",null,409);
        upload(id,"PROGRESS_PHOTO",null,409);
        upload(id,"NOTICE",null,200);
        assertThat(db.queryForObject("select count(*) from case_file where work_order_id=?::uuid",Long.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from case_file where work_order_id=?::uuid AND category IN ('LOSS_ASSESSMENT','PROGRESS_PHOTO')",Long.class,id)).isZero();
    }
    @Test void workflowViewIsStaffScopedAndDoesNotExposeMoney() throws Exception {
        String id=arrived();
        String body=mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("SHOP_QUOTE")).andExpect(jsonPath("$.data.nodes.length()").value(9)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("markupValue","unitPrice","ownerPhone");
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/work-orders/{id}/workflow'].get").exists());
    }
    @Test void cancelledAndUnknownLegacyStatesDoNotInventCompletedNodes() throws Exception {
        String id=arrived();
        request(admin,"work-orders/"+id+"/cancel",Map.of("reason","合成取消","description","流程快照验收","notifyOwner",false,"notifyShop",false),200);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentNode").value("SHOP_QUOTE")).andExpect(jsonPath("$.data.nodes[2].state").value("STOPPED"))
            .andExpect(jsonPath("$.data.nodes[8].state").value("TERMINATED")).andExpect(jsonPath("$.data.canAct").value(false));
        db.update("update work_order set status='CLOSED' where id=?::uuid",id);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentNode").value("UNKNOWN")).andExpect(jsonPath("$.data.legacyNeedsReview").value(true))
            .andExpect(jsonPath("$.data.nodes[0].state").value("UNRECORDED")).andExpect(jsonPath("$.data.canContinue").value(false));
        upload(id,"NOTICE",null,409);
    }
    private void prepareFormal(String id) throws Exception {
        var current=context(customerService,id);
        if(!current.path("formalQuote").isNull())return;
        if(current.path("rawQuote").isNull())raw(id,"0.00");
        request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成流程测试缺失说明"),200);
        review(id,"FIXED_AMOUNT","0.00",200);
    }
    private void authorized(String id) throws Exception {prepareFormal(id);upload(id,"LOSS_ASSESSMENT",null,200);assess(id,"0.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");assertThat(context(shop,id).path("authorized").asBoolean()).isTrue();}
    @Test void finalAuthorizationChecksPriceAndLatestConfirmedLossDocumentInsteadOfFormalQuoteTotal() throws Exception {
        String id=arrived();raw(id,"10.00");review(id,"FIXED_AMOUNT","0.00",200);
        upload(id,"LOSS_ASSESSMENT",null,200);assess(id,"18.00",200);confirm(id,"INSURER");
        var c=context(customerService,id);var a=c.path("assessment");
        assertThat(a.path("sourceCurrent").asBoolean()).isTrue();
        assertThat(c.path("formalQuote").path("total").asText()).isEqualTo("30.00");
        var body=new HashMap<String,Object>(Map.of("expectedVersion",c.path("version").asInt(),"assessmentId",a.path("id").asText(),"lossFileId",a.path("lossFileId").asText(),"amount","30.00","originalReviewed",true));
        request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,409);
        body.put("amount","18.00");body.put("originalReviewed",false);request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,400);
        body.put("originalReviewed",true);body.put("lossFileId",UUID.randomUUID().toString());request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,409);
        body.put("lossFileId",a.path("lossFileId").asText());body.put("assessmentId",UUID.randomUUID().toString());request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,409);
        body.put("assessmentId",a.path("id").asText());
        for(String token:List.of(shop,owner,admin))request(token,path(id,"confirmations/CUSTOMER_SERVICE"),body,403);
        assertThat(context(customerService,id).path("authorized").asBoolean()).isFalse();
        String key=UUID.randomUUID().toString();var accepted=request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,key,200);
        assertThat(accepted.path("authorized").asBoolean()).isTrue();
        assertThat(request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,key,200)).isEqualTo(accepted);
        var stored=json.readTree(db.queryForObject("select file_snapshot from quotation_confirmation where work_order_id=?::uuid and kind='CUSTOMER_SERVICE' order by created_at desc limit 1",String.class,id));
        assertThat(stored.path("amount").asText()).isEqualTo("18.00");assertThat(stored.path("lossFileId").asText()).isEqualTo(a.path("lossFileId").asText());
        upload(id,"LOSS_ASSESSMENT",null,200);
        c=context(customerService,id);assertThat(c.path("assessment").path("sourceCurrent").asBoolean()).isFalse();start(id,409);
        confirm(id,"INSURER");body.put("expectedVersion",context(customerService,id).path("version").asInt());request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),body,409);
        assess(id,"19.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");start(id,200);
    }
    @Test void unlinkedAssessmentCannotAuthorizeEvenWithAnActiveFileAndMatchingEnteredPrice() throws Exception {
        String id=arrived();prepareFormal(id);upload(id,"LOSS_ASSESSMENT",null,200);
        var c=context(customerService,id);var recorded=request(customerService,path(id,"assessments"),Map.of("expectedVersion",c.path("version").asInt(),"amount","12.00"),200);
        assertThat(recorded.path("assessment").path("sourceCurrent").asBoolean()).isFalse();confirm(id,"INSURER");c=context(customerService,id);
        String file=db.queryForObject("select id::text from case_file where work_order_id=?::uuid and category='LOSS_ASSESSMENT' and state='ACTIVE' order by created_at desc,id desc limit 1",String.class,id);
        request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),Map.of("expectedVersion",c.path("version").asInt(),"assessmentId",c.path("assessment").path("id").asText(),"lossFileId",file,"amount","12.00","originalReviewed",true),409);
        assertThat(context(customerService,id).path("authorized").asBoolean()).isFalse();
    }
    @Test void documentPreviewsRenderTheAuthorizedPdfAndKeepRoleAndCaseScope() throws Exception {
        String id=arrived();prepareFormal(id);String quote=context(customerService,id).path("formalQuote").path("id").asText();
        String url="/api/v1/quotations/cases/"+id+"/formal-quotes/"+quote;
        byte[] pdf=mvc.perform(get(url+"/pdf").header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        byte[] png=mvc.perform(get(url+"/preview").header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Page-Count","1")).andReturn().getResponse().getContentAsByteArray();
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png))).isNotNull();
        mvc.perform(get(url+"/preview?page=0").header("Authorization","Bearer "+customerService)).andExpect(status().isBadRequest());
        mvc.perform(get(url+"/preview?page=999").header("Authorization","Bearer "+customerService)).andExpect(status().isBadRequest());
        for(String token:List.of(shop,owner))mvc.perform(get(url+"/preview").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        String other=arrived();mvc.perform(get("/api/v1/quotations/cases/"+other+"/formal-quotes/"+quote+"/preview").header("Authorization","Bearer "+customerService)).andExpect(status().isNotFound());
        String file=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic-loss.pdf","application/pdf",pdf)).param("category","LOSS_ASSESSMENT").header("Authorization","Bearer "+customerService).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(get("/api/v1/materials/{id}/preview",file).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Page-Count","1"));
        for(String token:List.of(shop,owner))mvc.perform(get("/api/v1/materials/{id}/preview",file).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void confirmationRequiresAmountFileAndInsurerInOrder() throws Exception {
        String id=arrived();prepareFormal(id);
        request(customerService,path(id,"confirmations/INSURER"),Map.of("expectedVersion",context(customerService,id).path("version").asInt()),409);
        assess(id,"0.00",200);
        request(customerService,path(id,"confirmations/INSURER"),Map.of("expectedVersion",context(customerService,id).path("version").asInt()),409);
        upload(id,"LOSS_ASSESSMENT",null,200);
        request(customerService,path(id,"confirmations/CUSTOMER_SERVICE"),Map.of("expectedVersion",context(customerService,id).path("version").asInt()),409);
        assertThat(db.queryForObject("select count(*) from quotation_confirmation where work_order_id=?::uuid",Long.class,id)).isZero();
        assess(id,"0.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("REPAIR")).andExpect(jsonPath("$.data.canAct").value(false)).andExpect(jsonPath("$.data.action").value("START_REPAIR"));
        raw(id,"1.00");
        assertThat(context(customerService,id).hasNonNull("assessment")).isFalse();
        assertThat(db.queryForObject("select assessment_id from quotation_context where work_order_id=?::uuid",UUID.class,id)).isNull();
        // Original unlinked amount and the subsequent document-reviewed revision are both retained.
        assertThat(db.queryForObject("select count(*) from quote_assessment where work_order_id=?::uuid",Long.class,id)).isEqualTo(2);
        assertThat(context(customerService,id).path("authorized").asBoolean()).isFalse();
        request(customerService,path(id,"confirmations/INSURER"),Map.of("expectedVersion",context(customerService,id).path("version").asInt()),409);
    }
    @Test void workflowRequiresActualShopCompletionEvidenceAndProtectsOtherDrafts() throws Exception {
        var other=createUser("CUSTOMER_SERVICE",null);String otherToken=login(other.path("username").asText(),"ADMIN");
        String draft=createDraft(otherToken,requiredDraft("PRIVATE-"+UUID.randomUUID(),OWNER_PHONE,"VIN-"+UUID.randomUUID(),Instant.now())).path("id").asText();
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",draft).header("Authorization","Bearer "+customerService)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",draft).header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        String id=arrived();authorized(id);start(id,200);
        repairPhoto(customerService,id,"COMPLETION_PHOTO");
        String body=mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canAct").value(true)).andExpect(jsonPath("$.data.canContinue").value(false)).andReturn().getResponse().getContentAsString();
        var rows=json.readTree(body).path("data").path("materials");
        for(var row:rows)if(row.path("category").asText().equals("COMPLETION_PHOTO")){assertThat(row.path("present").asBoolean()).isTrue();assertThat(row.path("satisfied").asBoolean()).isFalse();}
        repairPhoto(shop,id,"COMPLETION_PHOTO");
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canContinue").value(true));
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canAct").value(false));
    }
    @Test void immutablePricesFixedPercentageZeroAndExternalExportHaveExactTotals() throws Exception {
        String id=arrived();var body=rawBody(id,List.of(Map.of("description","=SUM(1,2)\n合成配件","quantity",2,"unitPrice","30.00"),Map.of("description","工时","quantity",1,"unitPrice","40.00"),Map.of("description","赠送","quantity",5,"unitPrice","0.00")));
        request(shop,path(id,"raw-quotes"),body,200);
        String scene=db.queryForObject("SELECT id::text FROM case_file WHERE work_order_id=?::uuid AND category='SCENE_PHOTO' AND state='ACTIVE'",String.class,id);request(customerService,"materials/"+scene+"/void",Map.of(),200);review(id,"FIXED_AMOUNT","10.00",409);scenePhoto(customerService,id);
        request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成验收缺失"),200);
        var fixed=review(id,"FIXED_AMOUNT","10.00",200).path("formalQuote");String fixedId=fixed.path("id").asText();
        assertThat(fixed.path("total").asText()).isEqualTo("110.00");assertThat(fixed.path("lines").get(0).path("externalAmount").asText()).isEqualTo("66.00");assertThat(fixed.path("lines").get(2).path("externalAmount").asText()).isEqualTo("0.00");
        var rate=review(id,"PERCENTAGE","1.23",200).path("formalQuote");assertThat(rate.path("total").asText()).isEqualTo("101.23");assertThat(rate.path("version").asInt()).isEqualTo(2);
        assertThat(db.queryForObject("select total from quote_formal where id=?::uuid",java.math.BigDecimal.class,fixedId)).isEqualByComparingTo("110.00");
        assertThat(db.queryForObject("select unit_price from quote_raw_item where raw_quote_id=?::uuid and line_no=1",java.math.BigDecimal.class,context(shop,id).path("rawQuote").path("id").asText())).isEqualByComparingTo("30.00");
        String csv=mvc.perform(get("/api/v1/quotations/cases/{id}/formal-quotes/{quote}/export",id,fixedId).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv).contains("66.00","44.00","110.00","\"'=SUM(1,2)\\n合成配件\"".replace("\\n","\n")).doesNotContain("markup","original","30.00");
        byte[] pdf=mvc.perform(get("/api/v1/quotations/cases/{id}/formal-quotes/{quote}/pdf",id,fixedId).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf)){String rendered=new org.apache.pdfbox.text.PDFTextStripper().getText(document);assertThat(rendered).contains("平台正式报价单","110.00").doesNotContain("原始合计","加价","平台利润");}
        for(String token:List.of(shop,owner))mvc.perform(get("/api/v1/quotations/cases/{id}/formal-quotes/{quote}/export",id,fixedId).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        for(String token:List.of(shop,owner))mvc.perform(get("/api/v1/quotations/cases/{id}/formal-quotes/{quote}/pdf",id,fixedId).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotations/cases/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        var shopView=context(shop,id);assertThat(shopView.has("formalQuote")).isFalse();assertThat(shopView.has("assessment")).isFalse();assertThat(shopView.toString()).doesNotContain("markup","originalUnitPrice","externalUnitPrice");
        mvc.perform(get("/api/v1/quotations/cases/{id}/history?type=FORMAL",id).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update quote_formal set total=total+1 where id=?::uuid",fixedId));
        flyway.validate();
    }
    @Test void currentRepairShopMayProvideAssessmentDocumentButOwnerMayNot() throws Exception {
        String id=arrived();prepareFormal(id);int assignment=context(shop,id).path("assignmentVersion").asInt();
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id)
            .file(new org.springframework.mock.web.MockMultipartFile("file","assessment.pdf","application/pdf","%PDF-1.7\nSYNTHETIC\n%%EOF".getBytes()))
            .param("category","ASSESSMENT").param("assignmentVersion",String.valueOf(assignment))
            .header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString());
        String body=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fileId=json.readTree(body).path("data").path("id").asText();
        mvc.perform(get("/api/v1/materials/cases/{id}",id).header("Authorization","Bearer "+shop)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].category").value("ASSESSMENT"));
        mvc.perform(get("/api/v1/materials/{id}/download",fileId).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
    }
    @Test void reconciliationExportIsPagedScopedAndStaffOnly() throws Exception {
        String id=arrived();String businessNo=db.queryForObject("select business_no from work_order where id=?::uuid",String.class,id);
        byte[] file=mvc.perform(get("/api/v1/funds/reconciliation/export").header("Authorization","Bearer "+customerService)
            .param("query",businessNo).param("page","1").param("size","1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            var row=book.getSheet("工单资金对账").getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(businessNo);
            assertThat(row.getCell(1).getStringCellValue()).isNotBlank();
            assertThat((Object)book.getSheetAt(0).getRow(2)).isNull();
        }
        for(String token:List.of(shop,owner))mvc.perform(get("/api/v1/funds/reconciliation/export")
            .header("Authorization","Bearer "+token).param("query",businessNo)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/funds/reconciliation/export").header("Authorization","Bearer "+admin)
            .param("from","2026-10-12").param("to","2026-10-11")).andExpect(status().isBadRequest());
    }
    @Test void historicalWorkbookArchivesReadOnlyRowsWithoutCreatingCurrentWorkOrders() throws Exception {
        byte[] source;
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new java.io.ByteArrayOutputStream()) {
            var sheet=book.createSheet("历史订单");book.createSheet("字段说明");
            var header=sheet.createRow(0);for(int column=0;column<com.evinsurance.platform.historical.application.HistoricalCaseFileParser.FIELDS.size();column++)
                header.createCell(column).setCellValue(com.evinsurance.platform.historical.application.HistoricalCaseFileParser.FIELDS.get(column));
            String[][] values={
                {"","张三","13800000001","粤B12345","雅迪","冠能","深圳市宝安区","无法充电","22.571000","113.883000","350.00","2026-07-01","老网点","completed","battery","旧系统","第一条"},
                {"HIST-100","李四","13800000002","","雅迪","冠能","深圳市南山区","碰撞","22.530000","113.930000","100.00","","","closed","accident","旧系统",""},
                {"HIST-100","李四","13800000002","","雅迪","冠能","深圳市南山区","碰撞","22.530000","113.930000","120.00","","","closed","accident","旧系统",""},
                {"HIST-100","李四","13800000002","","雅迪","冠能","深圳市南山区","碰撞","22.530000","113.930000","120.00","","","closed","accident","旧系统",""},
                {"HIST-101","王五","123","","雅迪","冠能","深圳市南山区","碰撞","22.530000","113.930000","120.00","","","closed","accident","旧系统",""},
                {"HIST-102","赵六","13800000003","","雅迪","冠能","深圳市南山区","碰撞","22.530000","113.930000","120.00","","","closed","accident","旧系统",""}
            };
            for(int index=0;index<values.length;index++){var row=sheet.createRow(index+1);for(int column=0;column<17;column++)row.createCell(column).setCellValue(values[index][column]);}
            sheet.getRow(6).getCell(16).setCellFormula("1+1");
            book.write(output);source=output.toByteArray();
        }
        long before=db.queryForObject("select count(*) from work_order",Long.class);
        var upload=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/historical-cases/imports")
            .file(new org.springframework.mock.web.MockMultipartFile("file","history.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",source))
            .header("Authorization","Bearer "+customerService);
        var batch=json.readTree(mvc.perform(upload).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String batchId=batch.path("id").asText();assertThat(batch.path("ready_count").asInt()).isEqualTo(3);assertThat(batch.path("duplicate_count").asInt()).isEqualTo(1);assertThat(batch.path("invalid_count").asInt()).isEqualTo(2);
        var preview=json.readTree(mvc.perform(get("/api/v1/historical-cases/imports/{id}/rows",batchId).header("Authorization","Bearer "+customerService))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("records");
        assertThat(preview.get(0).path("values").path("报案号").asText()).isEmpty();
        assertThat(preview.get(2).path("warning").asText()).isEqualTo("CLAIM_NO_REUSED");
        var confirmed=json.readTree(mvc.perform(post("/api/v1/historical-cases/imports/{id}/confirm",batchId)
            .header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(confirmed.path("imported_count").asInt()).isEqualTo(3);
        assertThat(db.queryForObject("select count(*) from work_order",Long.class)).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from historical_case where import_id=?::uuid",Long.class,batchId)).isEqualTo(3);
        var repeated=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/historical-cases/imports")
            .file(new org.springframework.mock.web.MockMultipartFile("file","history.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",source))
            .header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(repeated.path("duplicate_count").asInt()).isEqualTo(4);assertThat(repeated.path("invalid_count").asInt()).isEqualTo(2);
        for(String token:List.of(shop,owner))mvc.perform(get("/api/v1/historical-cases").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/historical-cases").header("Authorization","Bearer "+admin).param("query","HIST-100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    }
    @Test void allFourConditionsAndCurrentDocumentAmountBindingAreRevalidated() throws Exception {
        for(int omitted=0;omitted<4;omitted++) {
            String id=arrived();authorized(id);
            // Simulate a single missing condition in legacy evidence; authorized=true must never bypass revalidation.
            switch(omitted) {
                case 0 -> db.update("update case_file set state='VOID' where work_order_id=?::uuid and category='LOSS_ASSESSMENT'",id);
                case 1 -> db.update("update quotation_context set assessment_id=null where work_order_id=?::uuid",id);
                case 2 -> db.update("update quotation_context set insurer_confirmed=false where work_order_id=?::uuid",id);
                case 3 -> db.update("update quotation_context set service_confirmed=false where work_order_id=?::uuid",id);
                default -> throw new AssertionError();
            }
            var view=context(shop,id);assertThat(view.path("gate").path("allowed").asBoolean()).isFalse();assertThat(view.path("gate").path("missing").size()).isEqualTo(omitted==0?2:1);start(id,409);
        }
        String valid=arrived();authorized(valid);String key=UUID.randomUUID().toString();var c=context(shop,valid);var body=Map.of("expectedVersion",c.path("version").asInt(),"assignmentVersion",c.path("assignmentVersion").asInt());
        var first=request(shop,path(valid,"start-repair"),body,key,200);var retry=request(shop,path(valid,"start-repair"),body,key,200);assertThat(retry).isEqualTo(first);assertThat(first.path("status").asText()).isEqualTo("REPAIRING");
        assertThat(db.queryForObject("select count(*) from audit_log where object_id=? and action='REPAIR_START'",Long.class,valid)).isEqualTo(1);
    }
    @Test void changesInvalidateConfirmationsAndPostStartModificationsAreFrozen() throws Exception {
        String id=arrived();raw(id,"0.01");request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成缺失"),200);var quote=review(id,"FIXED_AMOUNT","0.01",200).path("formalQuote");assertThat(quote.path("lines").get(0).path("externalUnitPrice").asText()).isEqualTo("0.013333");assertThat(quote.path("total").asText()).isEqualTo("0.04");
        String file=upload(id,"LOSS_ASSESSMENT",null,200);assess(id,"0.04",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");
        var changed=assess(id,"0.05",200);assertThat(changed.path("authorized").asBoolean()).isFalse();assertThat(changed.path("gate").path("insurerConfirmationRecorded").asBoolean()).isFalse();assertThat(changed.path("gate").path("customerServiceConfirmationRecorded").asBoolean()).isFalse();start(id,409);
        confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");String next=upload(id,"LOSS_ASSESSMENT",file,200);assertThat(context(shop,id).path("authorized").asBoolean()).isFalse();assertThat(context(customerService,id).path("assessment").path("amount").asText()).isEqualTo("0.05");
        assertThat(context(customerService,id).path("assessment").path("sourceCurrent").asBoolean()).isFalse();assess(id,"0.05",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");raw(id,"0.02");var afterRaw=context(customerService,id);assertThat(afterRaw.path("formalQuote").isNull()).isTrue();assertThat(afterRaw.path("authorized").asBoolean()).isFalse();
        review(id,"PERCENTAGE","0.00",200);assess(id,"0.05",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");start(id,200);
        assess(id,"1.00",409);request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","开修后修订","quantity",1,"unitPrice","9.00"))),409);upload(id,"LOSS_ASSESSMENT",next,409);request(customerService,"materials/"+next+"/void",Map.of(),409);assertThat(db.queryForObject("select count(*) from quotation_confirmation where work_order_id=?::uuid",Long.class,id)).isGreaterThan(2);
    }
    @Test void numericCoercionMutuallyExclusiveMarkupAndOldVersionsAreRejected() throws Exception {
        String id=arrived();request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","测试","quantity",1.9,"unitPrice","1.00"))),400);request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","测试","quantity",1,"unitPrice",1.00))),400);request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","测试","quantity",1,"unitPrice","1.001"))),400);raw(id,"0.00");
        request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","测试"),200);var c=context(customerService,id);
        request(customerService,path(id,"formal-quotes"),Map.of("expectedVersion",c.path("version").asInt(),"rawQuoteId",c.path("rawQuote").path("id").asText(),"mode","FIXED_AMOUNT","fixedAmount","1.00","percentage","10.00"),400);review(id,"FIXED_AMOUNT","1.00",400);review(id,"PERCENTAGE","100.01",400);review(id,"FIXED_AMOUNT","0.00",200);
        request(admin,path(id,"assessments"),Map.of("expectedVersion",context(admin,id).path("version").asInt(),"amount","1.00"),403);request(customerService,path(id,"assessments"),Map.of("expectedVersion",0,"amount","1.00"),409);assess(id,"1.001",400);
        long otherShop=createEligibleShop();var otherUser=createUser("REPAIR_SHOP",otherShop);String other=login(otherUser.path("username").asText(),"H5");mvc.perform(get("/api/v1/quotations/cases/{id}",id).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        var current=context(shop,id);request(shop,path(id,"start-repair"),Map.of("expectedVersion",current.path("version").asInt(),"assignmentVersion",current.path("assignmentVersion").asInt()+1),403);
    }
    @Test void duplicateSubmissionAndRepairVersusMaterialVoidAreSerialized() throws Exception {
        String id=arrived();var rawRequest=rawBody(id,List.of(Map.of("description","并发测试","quantity",1,"unitPrice","1.00")));String key=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.<Callable<JsonNode>>of(()->request(shop,path(id,"raw-quotes"),rawRequest,key,200),()->request(shop,path(id,"raw-quotes"),rawRequest,key,200)));assertThat(results.get(0).get()).isEqualTo(results.get(1).get());}
        assertThat(db.queryForObject("select count(*) from quote_raw where work_order_id=?::uuid",Long.class,id)).isEqualTo(1);
        prepareFormal(id);String file=upload(id,"LOSS_ASSESSMENT",null,200);assess(id,"1.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");var c=context(shop,id);var startBody=Map.of("expectedVersion",c.path("version").asInt(),"assignmentVersion",c.path("assignmentVersion").asInt());
        Callable<Integer> begin=()->mvc.perform(post("/api/v1/"+path(id,"start-repair")).header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(startBody))).andReturn().getResponse().getStatus();
        Callable<Integer> revoke=()->mvc.perform(post("/api/v1/materials/{id}/void",file).header("Authorization","Bearer "+customerService)).andReturn().getResponse().getStatus();
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.of(begin,revoke));int startStatus=results.get(0).get(),voidStatus=results.get(1).get();assertThat(List.of(startStatus,voidStatus)).containsExactlyInAnyOrder(200,409);String state=db.queryForObject("select status from work_order where id=?::uuid",String.class,id);String material=db.queryForObject("select state from case_file where id=?::uuid",String.class,file);if("REPAIRING".equals(state))assertThat(material).isEqualTo("ACTIVE");else assertThat(material).isEqualTo("VOID");}
    }
    private String repairPhoto(String token,String id,String category) throws Exception {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id)
            .file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.png","image/png",png)).param("category",category)
            .param("assignmentVersion",String.valueOf(context(shop,id).path("assignmentVersion").asInt()))
            .header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString());
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
    }
    @Test void repairCompletionFreezesActualShopEvidenceAndPreservesHistory() throws Exception {
        String id=arrived();authorized(id);start(id,200);int assignment=context(shop,id).path("assignmentVersion").asInt();
        String since=db.queryForObject("select status_started_at::text from work_order where id=?::uuid",String.class,id);
        var progress=Map.of("expectedVersion",0,"assignmentVersion",assignment,"note","合成维修进度","photoIds",List.of());
        String key=UUID.randomUUID().toString();request(shop,"repairs/cases/"+id+"/progress",progress,key,200);request(shop,"repairs/cases/"+id+"/progress",progress,key,200);
        assertThat(db.queryForObject("select count(*) from repair_progress where work_order_id=?::uuid",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("select status_started_at::text from work_order where id=?::uuid",String.class,id)).isEqualTo(since);
        String staffPhoto=repairPhoto(customerService,id,"COMPLETION_PHOTO");
        request(shop,"repairs/cases/"+id+"/completion",Map.of("expectedVersion",1,"assignmentVersion",assignment,"photoIds",List.of(staffPhoto)),409);
        request(shop,"repairs/cases/"+id+"/completion",Map.of("expectedVersion",1,"assignmentVersion",assignment,"photoIds",List.of()),400);
        String photo=repairPhoto(shop,id,"COMPLETION_PHOTO");
        var completion=Map.of("expectedVersion",1,"assignmentVersion",assignment,"photoIds",List.of(photo));key=UUID.randomUUID().toString();
        var result=request(shop,"repairs/cases/"+id+"/completion",completion,key,200);assertThat(result.path("status").asText()).isEqualTo("WAITING_OWNER_CONFIRMATION");
        request(shop,"repairs/cases/"+id+"/completion",completion,key,200);
        request(shop,"repairs/cases/"+id+"/progress",Map.of("expectedVersion",2,"assignmentVersion",assignment,"note","不能在完工后追加","photoIds",List.of()),409);
        mvc.perform(post("/api/v1/materials/{id}/void",photo).header("Authorization","Bearer "+customerService)).andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update case_file set state='VOID' where id=?::uuid",photo));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update repair_completion set photo_snapshot='[]' where work_order_id=?::uuid",id));
        String view=mvc.perform(get("/api/v1/repairs/cases/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(view).contains(photo).doesNotContain("objectKey","originalTotal","markup","amount","sha256");
        mvc.perform(get("/api/v1/materials/{id}/download",photo).header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from audit_log where object_id=? and action='REPAIR_COMPLETE'",Integer.class,id)).isEqualTo(1);
        flyway.validate();
    }
    @Test void repairMutationsRejectUnstartedUnauthorizedAndUnboundActors() throws Exception {
        String id=arrived();int assignment=context(shop,id).path("assignmentVersion").asInt();
        var progress=Map.of("expectedVersion",0,"assignmentVersion",assignment,"note","不能绕过开修","photoIds",List.of());
        request(shop,"repairs/cases/"+id+"/progress",progress,409);
        db.update("update work_order set status='REPAIRING' where id=?::uuid",id);
        request(shop,"repairs/cases/"+id+"/progress",progress,409);
        request(owner,"repairs/cases/"+id+"/progress",progress,403);
        long otherShop=createEligibleShop();var other=createUser("REPAIR_SHOP",otherShop);String otherToken=login(other.path("username").asText(),"H5");
        request(otherToken,"repairs/cases/"+id+"/progress",progress,403);
        var otherOwner=createUser("OWNER",null);String otherOwnerToken=login(otherOwner.path("username").asText(),"H5");
        mvc.perform(get("/api/v1/repairs/cases/{id}/progress",id).header("Authorization","Bearer "+otherOwnerToken)).andExpect(status().isForbidden());
    }
    @Test void completionAndVoidRaceNeverLeaveCommittedInvalidEvidence() throws Exception {
        String id=arrived();authorized(id);start(id,200);String photo=repairPhoto(shop,id,"COMPLETION_PHOTO");
        int assignment=context(shop,id).path("assignmentVersion").asInt();var body=Map.of("expectedVersion",0,"assignmentVersion",assignment,"photoIds",List.of(photo));
        Callable<Integer> complete=()->mvc.perform(post("/api/v1/repairs/cases/{id}/completion",id).header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getStatus();
        Callable<Integer> cancelPhoto=()->mvc.perform(post("/api/v1/materials/{id}/void",photo).header("Authorization","Bearer "+customerService)).andReturn().getResponse().getStatus();
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.of(complete,cancelPhoto));assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);
            String state=db.queryForObject("select status from work_order where id=?::uuid",String.class,id);
            String fileState=db.queryForObject("select state from case_file where id=?::uuid",String.class,photo);
            int count=db.queryForObject("select count(*) from repair_completion where work_order_id=?::uuid",Integer.class,id);
            if("WAITING_OWNER_CONFIRMATION".equals(state)){assertThat(fileState).isEqualTo("ACTIVE");assertThat(count).isEqualTo(1);}
            else {assertThat(state).isEqualTo("REPAIRING");assertThat(fileState).isEqualTo("VOID");assertThat(count).isZero();}
        }
    }
    @Test void actorCommandKeyAcrossCasesIsSerializedAndConflictingPayloadIsRejected() throws Exception {
        String first=arrived(),second=arrived();authorized(first);authorized(second);start(first,200);start(second,200);String key=UUID.randomUUID().toString();
        List<Callable<Integer>> calls=new ArrayList<>();for(String id:List.of(first,second)){
            var body=Map.of("expectedVersion",0,"assignmentVersion",context(shop,id).path("assignmentVersion").asInt(),"note","跨案件同键测试","photoIds",List.of());
            calls.add(()->mvc.perform(post("/api/v1/repairs/cases/{id}/progress",id).header("Authorization","Bearer "+shop).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getStatus());
        }
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(calls);assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("select count(*) from repair_progress where work_order_id IN (?::uuid,?::uuid)",Integer.class,first,second)).isEqualTo(1);
    }
    private String completedRepair() throws Exception {
        String id=arrived();authorized(id);start(id,200);String photo=repairPhoto(shop,id,"COMPLETION_PHOTO");
        request(shop,"repairs/cases/"+id+"/completion",Map.of("expectedVersion",0,"assignmentVersion",context(shop,id).path("assignmentVersion").asInt(),"photoIds",List.of(photo)),200);return id;
    }
    @Test void boundOwnerOrCustomerServiceMayConfirmReceiptWithoutRatingOrPayment() throws Exception {
        for(String token:List.of(owner,customerService)){
            String id=completedRepair(),key=UUID.randomUUID().toString();var body=Map.of("expectedVersion",1);
            var result=request(token,"repairs/cases/"+id+"/receipt",body,key,200);
            assertThat(result.path("status").asText()).isEqualTo("WAITING_INSURER_PAYMENT");assertThat(result.path("version").asInt()).isEqualTo(2);
            assertThat(result.path("receipt").path("confirmedBy").asText()).isEqualTo(token.equals(owner)?"OWNER":"CUSTOMER_SERVICE");
            request(token,"repairs/cases/"+id+"/receipt",body,key,200);request(token,"repairs/cases/"+id+"/receipt",body,409);
            assertThat(db.queryForObject("select count(*) from repair_receipt where work_order_id=?::uuid",Integer.class,id)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from audit_log where object_id=? and action='REPAIR_RECEIPT_CONFIRM'",Integer.class,id)).isEqualTo(1);
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update repair_receipt set actor_role='OWNER' where work_order_id=?::uuid",id));
            request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","不能改已完成案件"),409);
        }
        flyway.validate();
    }
    @Test void receiptEnforcesBindingRoleStateVersionAndSerializedConcurrentConfirmation() throws Exception {
        String early=arrived();request(owner,"repairs/cases/"+early+"/receipt",Map.of("expectedVersion",0),409);
        db.update("update work_order set status='WAITING_OWNER_CONFIRMATION' where id=?::uuid",early);
        request(customerService,"repairs/cases/"+early+"/receipt",Map.of("expectedVersion",0),409);
        String id=completedRepair();var body=Map.of("expectedVersion",1);
        for(String token:List.of(shop,admin))request(token,"repairs/cases/"+id+"/receipt",body,403);
        var unrelated=createUser("OWNER",null);request(login(unrelated.path("username").asText(),"H5"),"repairs/cases/"+id+"/receipt",body,403);
        request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",0),409);
        List<Callable<Integer>> calls=new ArrayList<>();for(String token:List.of(owner,customerService))calls.add(()->mvc.perform(post("/api/v1/repairs/cases/{id}/receipt",id).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getStatus());
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(calls);assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);}
        assertThat(db.queryForObject("select count(*) from repair_receipt where work_order_id=?::uuid",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from work_order_status_history where work_order_id=?::uuid and action='REPAIR_RECEIPT_CONFIRM'",Integer.class,id)).isEqualTo(1);
    }
    private JsonNode repairContext(String token,String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/repairs/cases/{id}",id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void customerServiceDelegationWithdrawalAndReviewCorrectionsPreserveOwnerOriginal() throws Exception {
        String id=arrived();authorized(id);start(id,200);int assignment=context(shop,id).path("assignmentVersion").asInt();
        request(customerService,"repairs/cases/"+id+"/progress",Map.of("expectedVersion",0,"assignmentVersion",assignment,"note","客服代录维修进度","photoIds",List.of()),200);
        String photo=repairPhoto(shop,id,"COMPLETION_PHOTO");request(customerService,"repairs/cases/"+id+"/completion",Map.of("expectedVersion",1,"assignmentVersion",assignment,"photoIds",List.of(photo)),200);
        request(customerService,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",2),200);
        var review=Map.of("expectedVersion",3,"text","车主原始评价","score",5);String key=UUID.randomUUID().toString();
        request(owner,"repairs/cases/"+id+"/review",review,key,200);request(owner,"repairs/cases/"+id+"/review",review,key,200);
        for(String token:List.of(customerService,admin,shop))request(token,"repairs/cases/"+id+"/review",review,403);
        var correction=Map.of("expectedVersion",4,"text","客服纠正说明","score",3,"reason","合成纠正原因");
        request(owner,"repairs/cases/"+id+"/review/corrections",correction,403);var corrected=request(customerService,"repairs/cases/"+id+"/review/corrections",correction,200);
        assertThat(corrected.path("review").path("original").path("score").asInt()).isEqualTo(5);assertThat(corrected.path("review").path("current").path("score").asInt()).isEqualTo(3);
        assertThat(repairContext(owner,id).path("review").path("current").has("reason")).isFalse();
        for(String token:List.of(owner,admin,shop))request(token,"repairs/cases/"+id+"/receipt/withdraw",Map.of("expectedVersion",5,"reason","禁止越权撤回"),403);
        request(customerService,"repairs/cases/"+id+"/receipt/withdraw",Map.of("expectedVersion",5,"reason",""),400);
        var withdrawal=Map.of("expectedVersion",5,"reason","收车录入错误");key=UUID.randomUUID().toString();var withdrawn=request(customerService,"repairs/cases/"+id+"/receipt/withdraw",withdrawal,key,200);
        request(customerService,"repairs/cases/"+id+"/receipt/withdraw",withdrawal,key,200);
        assertThat(withdrawn.path("status").asText()).isEqualTo("WAITING_OWNER_CONFIRMATION");assertThat(withdrawn.path("review").path("eligibleForCurrentRating").asBoolean()).isFalse();
        mvc.perform(post("/api/v1/materials/{id}/void",photo).header("Authorization","Bearer "+customerService)).andExpect(status().isConflict());
        var again=request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",6),200);assertThat(again.path("review").path("eligibleForCurrentRating").asBoolean()).isFalse();
        request(owner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",7,"score",4),409);
        var restored=request(customerService,"repairs/cases/"+id+"/review/corrections",Map.of("expectedVersion",7,"text","再次收车后人工纠正","reason","确认本次收车评价"),200);
        assertThat(restored.path("review").path("eligibleForCurrentRating").asBoolean()).isTrue();assertThat(restored.path("review").path("original").path("text").asText()).isEqualTo("车主原始评价");
        assertThat(db.queryForObject("select count(*) from repair_receipt where work_order_id=?::uuid",Integer.class,id)).isEqualTo(2);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update repair_review set score=1 where work_order_id=?::uuid",id));
        mvc.perform(get("/api/v1/repairs/cases/{id}/review/history?page=1&size=1",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records[0].reason").doesNotExist());
    }
    @Test void reviewOptionalFieldsIntegerScoresAndAuthorizationAreEnforced() throws Exception {
        String id=completedRepair();request(owner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",1,"score",5),409);
        request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",1),200);
        for(Object score:List.of(0,6,1.5,"5"))request(owner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",2,"score",score),400);
        request(owner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",2,"text",""),400);
        var result=request(owner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",2,"text","只有文字，没有评分"),200);
        assertThat(result.path("review").path("original").path("score").isNull()).isTrue();
        request(owner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",3,"score",4),409);
        String second=completedRepair();request(customerService,"repairs/cases/"+second+"/receipt",Map.of("expectedVersion",1),200);
        var scoreOnly=request(owner,"repairs/cases/"+second+"/review",Map.of("expectedVersion",2,"score",4),200);assertThat(scoreOnly.path("review").path("original").path("text").isNull()).isTrue();
    }
    @Test void independentComplaintStateHistoryCorrectionsAndInternalNotesAreRoleFiltered() throws Exception {
        String id=completedRepair();request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",1),200);var repairBefore=repairContext(owner,id);
        String photo=repairBefore.path("completion").path("photos").get(0).path("id").asText();var body=Map.of("description","车主原始投诉","photoIds",List.of(photo));String key=UUID.randomUUID().toString();
        var complaint=request(owner,"complaints/cases/"+id,body,key,200);request(owner,"complaints/cases/"+id,body,key,200);String complaintId=complaint.path("id").asText();
        assertThat(db.queryForObject("select count(*) from complaint where work_order_id=?::uuid",Integer.class,id)).isEqualTo(1);
        for(String token:List.of(customerService,admin,shop))request(token,"complaints/cases/"+id,body,403);
        request(owner,"complaints/"+complaintId+"/corrections",Map.of("expectedVersion",0,"publicNote","车主不能改"),403);
        for(String token:List.of(owner,customerService,shop))request(token,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",0,"status","PROCESSING","publicNote","禁止越权"),403);
        request(admin,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",0,"status","RESOLVED","publicNote","不得跳状态"),409);
        var handled=request(admin,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",0,"status","PROCESSING","publicNote","管理员公开说明","internalNote","SECRET_INTERNAL_NOTE"),200);
        request(customerService,"complaints/"+complaintId+"/corrections",Map.of("expectedVersion",1,"publicNote","客服追加纠正，不改原文"),200);
        request(admin,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",2,"status","RESOLVED","publicNote","处理完成"),200);
        request(admin,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",3,"status","CLOSED","publicNote","已关闭"),200);
        request(admin,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",4,"status","PROCESSING","publicNote","不允许重开"),409);
        for(String token:List.of(owner,shop)){
            String history=mvc.perform(get("/api/v1/complaints/{id}/history",complaintId).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(history).contains("管理员公开说明","客服追加纠正").doesNotContain("SECRET_INTERNAL_NOTE","internalNote");
        }
        String internal=mvc.perform(get("/api/v1/complaints/{id}/history",complaintId).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(internal).contains("SECRET_INTERNAL_NOTE");
        assertThat(repairContext(owner,id)).isEqualTo(repairBefore);assertThat(handled.path("description").asText()).isEqualTo("车主原始投诉");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update complaint set description='changed' where id=?::uuid",complaintId));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update complaint_event set public_note='changed' where complaint_id=?::uuid",complaintId));
        var unrelated=createUser("OWNER",null);String unrelatedToken=login(unrelated.path("username").asText(),"H5");mvc.perform(get("/api/v1/complaints/{id}/history",complaintId).header("Authorization","Bearer "+unrelatedToken)).andExpect(status().isForbidden());
        long otherShop=createEligibleShop();var other=createUser("REPAIR_SHOP",otherShop);mvc.perform(get("/api/v1/complaints/{id}",complaintId).header("Authorization","Bearer "+login(other.path("username").asText(),"H5"))).andExpect(status().isForbidden());
        String early=arrived();request(owner,"complaints/cases/"+early,Map.of("description","未开修不能投诉"),409);
        String cancelled=arrived();authorized(cancelled);start(cancelled,200);db.update("update work_order set status='CANCELLED' where id=?::uuid",cancelled);request(owner,"complaints/cases/"+cancelled,Map.of("description","取消不能新增"),409);
        authorized(early);start(early,200);
        request(owner,"complaints/cases/"+id,Map.of("description","不能跨案引用照片","photoIds",List.of(repairPhoto(shop,early,"PROGRESS_PHOTO"))),400);
    }
    @Test void idempotentReceiptReplayFiltersStaffFieldsAfterLegitimateRoleChanges() throws Exception {
        var actor=createUser("OWNER",null);long actorId=actor.path("id").asLong();String username=actor.path("username").asText(),phone="139"+String.format("%08d",actorId);
        db.update("update owner_profile set contact_phone=? where user_id=?",phone,actorId);
        String id=formal(customerService,"ROLE-REPLAY-"+UUID.randomUUID(),phone,"VIN-"+UUID.randomUUID(),Instant.now()).path("id").asText();
        var assigned=dispatch(customerService,id,shopId,true,"合成角色回放验证",UUID.randomUUID().toString(),200).path("data");int assignment=assignmentVersion(assigned);
        request(shop,"work-orders/"+id+"/accept",Map.of("assignmentVersion",assignment),200);request(shop,"work-orders/"+id+"/arrive",Map.of("assignmentVersion",assignment),200);
        authorized(id);start(id,200);String photo=repairPhoto(shop,id,"COMPLETION_PHOTO");request(shop,"repairs/cases/"+id+"/completion",Map.of("expectedVersion",0,"assignmentVersion",assignment,"photoIds",List.of(photo)),200);
        String actorOwner=login(username,"H5");request(actorOwner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",1),200);request(actorOwner,"repairs/cases/"+id+"/review",Map.of("expectedVersion",2,"score",5),200);
        request(customerService,"repairs/cases/"+id+"/review/corrections",Map.of("expectedVersion",3,"score",4,"reason","STAFF_REASON_MUST_NOT_REPLAY"),200);
        request(customerService,"repairs/cases/"+id+"/receipt/withdraw",Map.of("expectedVersion",4,"reason","合成角色变更复验"),200);
        mvc.perform(put("/api/v1/admin/users/{id}/assignment",actorId).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("roles",List.of("CUSTOMER_SERVICE"))))).andExpect(status().isOk());
        String actorStaff=login(username,"ADMIN"),key=UUID.randomUUID().toString();var body=Map.of("expectedVersion",5);
        var staffResult=request(actorStaff,"repairs/cases/"+id+"/receipt",body,key,200);assertThat(staffResult.toString()).contains("STAFF_REASON_MUST_NOT_REPLAY");
        mvc.perform(put("/api/v1/admin/users/{id}/assignment",actorId).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("roles",List.of("OWNER"))))).andExpect(status().isOk());
        var replay=request(login(username,"H5"),"repairs/cases/"+id+"/receipt",body,key,200);assertThat(replay.toString()).doesNotContain("reason","STAFF_REASON_MUST_NOT_REPLAY");
        assertThat(db.queryForObject("select count(*) from repair_receipt where work_order_id=?::uuid",Integer.class,id)).isEqualTo(2);
    }
    private JsonNode funds(String token,String id) throws Exception {return json.readTree(mvc.perform(get("/api/v1/funds/cases/{id}",id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode fundsTarget(String id,String direction,String amount,int expected) throws Exception {var body=new HashMap<String,Object>();body.put("expectedVersion",funds(customerService,id).path("version").asInt());body.put("direction",direction);body.put("amount",amount);body.put("reason","合成资金目标版本");return request(customerService,"funds/cases/"+id+"/targets",body,expected);}
    private Map<String,Object> fundsEntry(String id,String dir,String number,String amount) throws Exception {return Map.of("expectedVersion",funds(customerService,id).path("version").asInt(),"direction",dir,"transactionNo",number,"amount",amount,"occurredAt","2026-10-10T02:00:00Z","note","SYNTHETIC_INTERNAL_NOTE");}
    private JsonNode fundsConfirm(String id,String dir,int expected) throws Exception {return request(customerService,"funds/cases/"+id+"/confirmations",Map.of("expectedVersion",funds(customerService,id).path("version").asInt(),"direction",dir),expected);}
    private void caseStatus(String id,String state) {assertThat(db.queryForObject("select status from work_order where id=?::uuid",String.class,id)).isEqualTo(state);}
    private String receivedRepair() throws Exception {String id=completedRepair();request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",1),200);return id;}
    @Test void fundsPartialMultipleReceiptsAndPaymentsRemainSeparateFromRepairAndRoleFiltered() throws Exception {
        String id=receivedRepair();fundsTarget(id,"RECEIVE","100.01",200);fundsTarget(id,"PAY","80.00",200);
        String number="RECEIVE-"+UUID.randomUUID();var first=fundsEntry(id,"RECEIVE",number,"20.00");String key=UUID.randomUUID().toString();request(customerService,"funds/cases/"+id+"/entries",first,key,200);request(customerService,"funds/cases/"+id+"/entries",first,key,200);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","PAY-"+UUID.randomUUID(),"1.00"),409);
        var duplicate=request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE",number,"20.00"),200);assertThat(duplicate.path("duplicate").asBoolean()).isTrue();
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE",number,"21.00"),409);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","RECEIVE-"+UUID.randomUUID(),"80.02"),409);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","RECEIVE-"+UUID.randomUUID(),"80.01"),200);
        caseStatus(id,"WAITING_INSURER_PAYMENT");fundsConfirm(id,"RECEIVE",200);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","PAY-"+UUID.randomUUID(),"30.00"),200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","PAY-"+UUID.randomUUID(),"50.00"),200);
        caseStatus(id,"WAITING_SHOP_SETTLEMENT");fundsConfirm(id,"PAY",200);
        assertThat(funds(customerService,id).path("receivable").path("net").asText()).isEqualTo("100.01");assertThat(funds(customerService,id).path("payable").path("status").asText()).isEqualTo("SETTLED");assertThat(funds(shop,id).has("receivable")).isFalse();
        String shopHistory=mvc.perform(get("/api/v1/funds/cases/{id}/entries",id).header("Authorization","Bearer "+shop)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(shopHistory).doesNotContain("RECEIVE","SYNTHETIC_INTERNAL_NOTE","transactionNo","reversalReason");
        mvc.perform(get("/api/v1/funds/cases/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());mvc.perform(get("/api/v1/funds/cases/{id}/export",id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        request(admin,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","NO-"+UUID.randomUUID(),"1.00"),403);
        var other=createUser("REPAIR_SHOP",createEligibleShop());mvc.perform(get("/api/v1/funds/cases/{id}/export",id).header("Authorization","Bearer "+login(other.path("username").asText(),"H5"))).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select status from work_order where id=?::uuid",String.class,id)).isEqualTo("COMPLETED");assertThat(db.queryForObject("select count(*) from funds_entry where work_order_id=?::uuid",Integer.class,id)).isEqualTo(4);
    }
    @Test void fundsZeroUnknownTargetVersionsAndReversalsProtectHistory() throws Exception {
        String id=arrived();fundsTarget(id,"RECEIVE","0.00",200);assertThat(funds(customerService,id).path("receivable").path("status").asText()).isEqualTo("SETTLED");fundsTarget(id,"PAY",null,200);assertThat(funds(shop,id).path("payable").path("target").isNull()).isTrue();
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","ZERO-"+UUID.randomUUID(),"0.00"),400);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","DECIMAL-"+UUID.randomUUID(),"1.001"),400);
        fundsTarget(id,"RECEIVE","50.00",200);String entry=request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","REV-"+UUID.randomUUID(),"30.00"),200).path("entryId").asText();fundsTarget(id,"RECEIVE","20.00",409);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update funds_entry set amount=1 where id=?::uuid",entry));org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update funds_target set amount=1 where work_order_id=?::uuid",id));
        db.update("update work_order set status='CANCELLED' where id=?::uuid",id);fundsTarget(id,"RECEIVE","40.00",409);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","CANCEL-"+UUID.randomUUID(),"1.00"),409);
        var body=Map.of("expectedVersion",funds(customerService,id).path("version").asInt(),"reason","合成冲销纠错");String key=UUID.randomUUID().toString();request(customerService,"funds/cases/"+id+"/entries/"+entry+"/reverse",body,key,200);request(customerService,"funds/cases/"+id+"/entries/"+entry+"/reverse",body,key,200);
        assertThat(funds(customerService,id).path("receivable").path("net").asText()).isEqualTo("0.00");assertThat(db.queryForObject("select count(*) from funds_reversal where entry_id=?::uuid",Integer.class,entry)).isEqualTo(1);assertThat(db.queryForObject("select status from work_order where id=?::uuid",String.class,id)).isEqualTo("CANCELLED");
    }
    @Test void receiptWithdrawalPreservesFundsAndBlocksNewPaymentUntilRereceipt() throws Exception {
        String id=receivedRepair();fundsTarget(id,"RECEIVE","20.00",200);fundsTarget(id,"PAY","20.00",200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","WITHDRAW-R-"+UUID.randomUUID(),"20.00"),200);fundsConfirm(id,"RECEIVE",200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","WITHDRAW-P-"+UUID.randomUUID(),"10.00"),200);
        var before=funds(customerService,id);assertThat(before.path("canRecordPay").asBoolean()).isTrue();request(customerService,"repairs/cases/"+id+"/receipt/withdraw",Map.of("expectedVersion",2,"reason","合成收车撤回"),200);var after=funds(customerService,id);assertThat(after.path("canRecordPay").asBoolean()).isFalse();assertThat(after.path("receiveConfirmed").asBoolean()).isFalse();assertThat(after.path("receivable")).isEqualTo(before.path("receivable"));assertThat(after.path("payable")).isEqualTo(before.path("payable"));caseStatus(id,"WAITING_OWNER_CONFIRMATION");
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","AFTER-WITHDRAW-"+UUID.randomUUID(),"10.00"),409);fundsTarget(id,"PAY",null,409);fundsTarget(id,"PAY","9.00",409);
        request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",3),200);fundsConfirm(id,"RECEIVE",200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","AFTER-RERECEIVE-"+UUID.randomUUID(),"10.00"),200);assertThat(funds(customerService,id).path("payable").path("status").asText()).isEqualTo("SETTLED");
    }

    @Test void fundsManualConfirmationRequiresOrderedSettledLegsAndCurrentVersions() throws Exception {
        String early=arrived();fundsTarget(early,"RECEIVE","0.00",200);fundsConfirm(early,"RECEIVE",409);
        String id=receivedRepair();caseStatus(id,"WAITING_INSURER_PAYMENT");fundsConfirm(id,"RECEIVE",409);fundsConfirm(id,"PAY",409);
        fundsTarget(id,"RECEIVE","20.00",200);fundsTarget(id,"PAY","10.00",200);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","CONFIRM-PART-"+UUID.randomUUID(),"5.00"),200);fundsConfirm(id,"RECEIVE",409);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","CONFIRM-FULL-"+UUID.randomUUID(),"15.00"),200);
        var body=Map.of("expectedVersion",funds(customerService,id).path("version").asInt(),"direction","RECEIVE");
        for(String actor:List.of(admin,shop,owner))request(actor,"funds/cases/"+id+"/confirmations",body,403);
        request(customerService,"funds/cases/"+id+"/confirmations",Map.of("expectedVersion",0,"direction","RECEIVE"),409);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","BEFORE-CONFIRM-"+UUID.randomUUID(),"10.00"),409);
        String key=UUID.randomUUID().toString();var first=request(customerService,"funds/cases/"+id+"/confirmations",body,key,200);
        assertThat(request(customerService,"funds/cases/"+id+"/confirmations",body,key,200)).isEqualTo(first);caseStatus(id,"WAITING_SHOP_SETTLEMENT");fundsConfirm(id,"PAY",409);
        assertThat(findTask(customerService,id,"PAY_SHOP").path("kind").asText()).isEqualTo("PAY_SHOP");
        assertThat(getData(shop,"notifications/todos?caseId="+id+"&size=100").path("total").asInt()).isZero();
        boolean listed=false;for(var row:getData(customerService,"work-orders?status=WAITING_SHOP_SETTLEMENT&size=100").path("records"))if(id.equals(row.path("id").asText())){listed=true;assertThat(row.path("workflowNode").asText()).isEqualTo("FUNDS");assertThat(row.path("workflowActor").asText()).isEqualTo("客服");}assertThat(listed).isTrue();
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","CONFIRM-PAY-"+UUID.randomUUID(),"10.00"),200);caseStatus(id,"WAITING_SHOP_SETTLEMENT");
        fundsConfirm(id,"PAY",200);caseStatus(id,"COMPLETED");
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("COMPLETE")).andExpect(jsonPath("$.data.nodes[7].key").value("FUNDS"));
        assertThat(funds(shop,id).toString()).doesNotContain("receiveConfirmed","canConfirm","receivable","flowStage");
        assertThat(db.queryForObject("select count(*) from funds_confirmation where work_order_id=?::uuid",Integer.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("select cardinality(entry_ids) from funds_confirmation where work_order_id=?::uuid and direction='RECEIVE'",Integer.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from audit_log where object_id=? and action='FUNDS_CONFIRM'",Integer.class,id)).isEqualTo(2);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("delete from funds_confirmation where work_order_id=?::uuid",id));
    }
    @Test void fundsZeroTargetsNeedExplicitConfirmationAndChangedEvidenceReopensCorrectStep() throws Exception {
        String id=receivedRepair();fundsTarget(id,"RECEIVE","0.00",200);fundsTarget(id,"PAY","0.00",200);fundsConfirm(id,"PAY",409);
        fundsConfirm(id,"RECEIVE",200);fundsConfirm(id,"PAY",200);caseStatus(id,"COMPLETED");
        fundsTarget(id,"PAY","0.00",200);caseStatus(id,"WAITING_SHOP_SETTLEMENT");fundsConfirm(id,"PAY",200);
        fundsTarget(id,"RECEIVE","10.00",200);caseStatus(id,"WAITING_INSURER_PAYMENT");
        String entry=request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","REOPEN-"+UUID.randomUUID(),"10.00"),200).path("entryId").asText();
        fundsConfirm(id,"RECEIVE",200);caseStatus(id,"WAITING_SHOP_SETTLEMENT");fundsConfirm(id,"PAY",200);caseStatus(id,"COMPLETED");
        request(customerService,"funds/cases/"+id+"/entries/"+entry+"/reverse",Map.of("expectedVersion",funds(customerService,id).path("version").asInt(),"reason","合成到账冲销"),200);
        caseStatus(id,"WAITING_INSURER_PAYMENT");assertThat(funds(customerService,id).path("receiveConfirmed").asBoolean()).isFalse();assertThat(funds(customerService,id).path("payConfirmed").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("FUNDS"));
        String legacy=receivedRepair();db.update("update funds_context set staged_flow=false where work_order_id=?::uuid",legacy);db.update("update work_order set status='COMPLETED' where id=?::uuid",legacy);
        fundsTarget(legacy,"RECEIVE","0.00",200);caseStatus(legacy,"COMPLETED");assertThat(funds(customerService,legacy).path("stagedFlow").asBoolean()).isFalse();fundsConfirm(legacy,"RECEIVE",409);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",legacy).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("COMPLETE")).andExpect(jsonPath("$.data.nodes[7].state").value("UNRECORDED"));
    }
    private JsonNode importCsv(String token,String csv,int expected) throws Exception {
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/funds/imports").file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.csv","text/csv",csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))).header("Authorization","Bearer "+token)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return json.readTree(result).path("data");
    }
    private JsonNode importRows(String token,String id) throws Exception{return json.readTree(mvc.perform(get("/api/v1/funds/imports/{id}/rows",id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("records");}
    @Test void fundsImportCrossChecksIdentifiersRequiresManualResolutionAndDeduplicatesEachRow() throws Exception {
        String id=arrived(),other=arrived();fundsTarget(id,"RECEIVE","150.00",200);String claim=db.queryForObject("select claim_no from work_order where id=?::uuid",String.class,id),business=db.queryForObject("select business_no from work_order where id=?::uuid",String.class,id),otherBusiness=db.queryForObject("select business_no from work_order where id=?::uuid",String.class,other);String a="IMPORT-A-"+UUID.randomUUID(),b="IMPORT-B-"+UUID.randomUUID();
        String header="transactionNo,direction,claimNo,businessNo,amount,occurredAt,note\n",good=a+",RECEIVE,"+claim+","+business+",50.00,2026-10-10T02:00:00Z,SYNTHETIC\n";String csv=header+good+good+b+",RECEIVE,"+claim+","+otherBusiness+",50.00,2026-10-10T02:00:00Z,SYNTHETIC\nINVALID,RECEIVE,"+claim+","+business+",-1.00,2026-10-10T02:00:00Z,SYNTHETIC\n";
        String batch=importCsv(customerService,csv,200).path("id").asText();assertThat(importRows(customerService,batch).get(2).path("status").asText()).isEqualTo("NEEDS_REVIEW");request(customerService,"funds/imports/"+batch+"/confirm",Map.of(),200);
        assertThat(importRows(customerService,batch).get(0).path("status").asText()).isEqualTo("RECORDED");assertThat(importRows(customerService,batch).get(1).path("status").asText()).isEqualTo("DUPLICATE");assertThat(importRows(customerService,batch).get(3).path("status").asText()).isEqualTo("INVALID");assertThat(funds(customerService,id).path("receivable").path("net").asText()).isEqualTo("50.00");
        request(customerService,"funds/imports/"+batch+"/rows/4/resolve",Map.of("expectedVersion",0,"caseId",id,"reason","核对纸质流水确认原工单号录错"),200);request(customerService,"funds/imports/"+batch+"/confirm",Map.of(),200);request(customerService,"funds/imports/"+batch+"/confirm",Map.of(),200);assertThat(funds(customerService,id).path("receivable").path("net").asText()).isEqualTo("100.00");
        String repeated=importCsv(customerService,csv,200).path("id").asText();request(customerService,"funds/imports/"+repeated+"/rows/4/resolve",Map.of("expectedVersion",0,"caseId",id,"reason","合成重复批次人工核对"),200);request(customerService,"funds/imports/"+repeated+"/confirm",Map.of(),200);assertThat(funds(customerService,id).path("receivable").path("net").asText()).isEqualTo("100.00");
        mvc.perform(get("/api/v1/funds/imports/{id}/source",batch).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for(String token:List.of(admin,shop,owner))mvc.perform(get("/api/v1/funds/imports/{id}/source",batch).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        var otherStaff=createUser("CUSTOMER_SERVICE",null);mvc.perform(get("/api/v1/funds/imports/{id}/rows",batch).header("Authorization","Bearer "+login(otherStaff.path("username").asText(),"ADMIN"))).andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update funds_import_row set raw_json='{}' where import_id=?::uuid",batch));assertThat(db.queryForObject("select count(*) from funds_import_resolution where import_id=?::uuid",Integer.class,batch)).isEqualTo(1);
    }
    @Test void simultaneousFundsEntriesCannotExceedTargetOrLoseHistory() throws Exception {
        String id=arrived();fundsTarget(id,"RECEIVE","50.00",200);var one=fundsEntry(id,"RECEIVE","RACE-A-"+UUID.randomUUID(),"30.00");var two=fundsEntry(id,"RECEIVE","RACE-B-"+UUID.randomUUID(),"30.00");
        Callable<Integer> first=()->mvc.perform(post("/api/v1/funds/cases/{id}/entries",id).header("Authorization","Bearer "+customerService).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(one))).andReturn().getResponse().getStatus();Callable<Integer> second=()->mvc.perform(post("/api/v1/funds/cases/{id}/entries",id).header("Authorization","Bearer "+customerService).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(two))).andReturn().getResponse().getStatus();
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.of(first,second));assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);}
        assertThat(funds(customerService,id).path("receivable").path("net").asText()).isEqualTo("30.00");assertThat(db.queryForObject("select count(*) from funds_entry where work_order_id=?::uuid",Integer.class,id)).isEqualTo(1);
    }
    private JsonNode getData(String token,String path) throws Exception {return json.readTree(mvc.perform(get("/api/v1/"+path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode findTask(String token,String id,String kind) throws Exception {var rows=getData(token,"notifications/todos?caseId="+id+"&size=100").path("records");for(var row:rows)if(kind.equals(row.path("kind").asText()))return row;throw new AssertionError("Missing active task "+kind);}
    @Test void stationNotificationsAreRecipientScopedReadIdempotentAndContainNoFinancialOrInternalFields() throws Exception {
        String id=receivedRepair();fundsTarget(id,"RECEIVE","100.00",200);fundsTarget(id,"PAY","80.00",200);long pending=db.queryForObject("SELECT count(*) FROM notification_event WHERE projected_at IS NULL",Long.class);for(long offset=0;offset<pending;offset+=50)notificationProjector.project();assertThat(db.queryForObject("SELECT count(*) FROM notification_event WHERE projected_at IS NULL",Long.class)).isZero();
        var ownerInbox=getData(owner,"notifications?size=100").path("records");JsonNode ownNotice=null;for(var row:ownerInbox)if(id.equals(row.path("caseId").asText())){ownNotice=row;assertThat(row.toString()).doesNotContain("amount","markup","originalTotal","internalNote","FUNDS_UPDATED","PAY_UPDATED");}
        assertThat(ownNotice).isNotNull();long noticeId=ownNotice.path("id").asLong();request(owner,"notifications/"+noticeId+"/read",Map.of(),200);request(owner,"notifications/"+noticeId+"/read",Map.of(),200);request(shop,"notifications/"+noticeId+"/read",Map.of(),403);assertThat(db.queryForObject("select count(*) from notification_read where notification_id=?",Integer.class,noticeId)).isEqualTo(1);
        var settings=getData(owner,"notifications/settings");assertThat(settings.path("automaticTimeoutEnabled").asBoolean()).isFalse();assertThat(settings.path("smsEnabled").asBoolean()).isFalse();
        var stranger=createUser("OWNER",null);String strangerToken=login(stranger.path("username").asText(),"H5");assertThat(getData(strangerToken,"notifications?size=100").path("total").asInt()).isZero();request(strangerToken,"notifications/"+noticeId+"/read",Map.of(),403);
        db.update("update work_order set owner_user_id=? where id=?::uuid",stranger.path("id").asLong(),id);request(owner,"notifications/"+noticeId+"/read",Map.of(),403);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update in_app_notification set recipient_role='ADMIN' where id=?",noticeId));
    }
    @Test void deadlinesAreOptionalVersionedChinaTimeAndExpireOnlyAsLabelsWhileCompletedTasksRejectChanges() throws Exception {
        String id=arrived();var task=findTask(customerService,id,"QUOTE_SHOP");String key=task.path("taskKey").asText();var body=Map.of("taskKey",key,"expectedVersion",0,"deadline","2026-01-01T12:00:00+08:00","reason","合成客服约定期限");
        for(String token:List.of(admin,owner,shop))request(token,"notifications/deadlines",body,403);var dated=request(customerService,"notifications/deadlines",body,200);assertThat(dated.path("overdue").asBoolean()).isTrue();request(customerService,"notifications/deadlines",body,409);
        var clear=new HashMap<String,Object>();clear.put("taskKey",key);clear.put("expectedVersion",1);clear.put("deadline",null);clear.put("reason","取消期限但保留历史");var cleared=request(customerService,"notifications/deadlines",clear,200);assertThat(cleared.path("deadline").isNull()).isTrue();assertThat(cleared.path("overdue").asBoolean()).isFalse();
        request(customerService,"notifications/deadlines",Map.of("taskKey",key,"expectedVersion",2,"deadline","2026-10-10T12:00:00Z","reason","错误时区"),400);
        request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","合成项目","quantity",1,"unitPrice","1.00"))),200);clear.put("expectedVersion",2);request(customerService,"notifications/deadlines",clear,409);
        var history=json.readTree(mvc.perform(get("/api/v1/notifications/deadlines/history").param("taskKey",key).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(history.path("total").asInt()).isEqualTo(2);org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update todo_deadline_event set reason='overwrite' where task_key=?",key));
        assertThat(db.queryForObject("select status from work_order where id=?::uuid",String.class,id)).isEqualTo("QUOTE_REVIEWING");
    }
    @Test void reassignmentInvalidatesOldShopNotificationsAndProjectorsDeduplicateConcurrently() throws Exception {
        String id=formal(customerService,"NOTIFY-"+UUID.randomUUID(),OWNER_PHONE,"VIN-"+UUID.randomUUID(),Instant.now()).path("id").asText();var assigned=dispatch(customerService,id,shopId,true,"合成通知派单",UUID.randomUUID().toString(),200).path("data");int assignment=assignmentVersion(assigned);notificationProjector.project();
        JsonNode notice=null;for(var row:getData(shop,"notifications?size=100").path("records"))if(id.equals(row.path("caseId").asText()))notice=row;assertThat(notice).isNotNull();long oldNotice=notice.path("id").asLong();
        long nextShop=createEligibleShop();request(customerService,"work-orders/"+id+"/reassign",Map.of("shopId",nextShop,"assignmentVersion",assignment,"reason","合成改派隔离","vehicleNotArrivedConfirmed",true,"confirmPossibleDuplicate",true),200);request(shop,"notifications/"+oldNotice+"/read",Map.of(),403);
        try(var pool=Executors.newFixedThreadPool(2)){var results=pool.invokeAll(List.<Callable<Void>>of(()->{notificationProjector.project();return null;},()->{notificationProjector.project();return null;}));for(var result:results)result.get();}
        assertThat(db.queryForObject("select count(*) from (select event_id,recipient_id,recipient_role from in_app_notification group by event_id,recipient_id,recipient_role having count(*)>1) x",Integer.class)).isZero();
        mvc.perform(get("/api/v1/notifications/todos").param("caseId",id).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
    }
    @Autowired com.evinsurance.platform.ocr.application.OcrTaskStore ocrTasks;
    @Autowired com.evinsurance.platform.ocr.infrastructure.OcrMapper ocrJobs;
    private JsonNode documentJob(String id,String token,String category,boolean failed) throws Exception {
        int version=context(shop,id).path("assignmentVersion").asInt();
        var upload=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","workflow-synthetic.pdf","application/pdf","%PDF-1.7\nSYNTHETIC\n%%EOF".getBytes())).param("category",category).param("assignmentVersion",String.valueOf(version)).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString());
        String file=json.readTree(mvc.perform(upload).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        var job=request(token,"ocr",Map.of("fileId",file),200);var claimed=ocrTasks.claim(3,60);assertThat(claimed.id().toString()).isEqualTo(job.path("id").asText());
        ocrTasks.finish(claimed,failed?null:json.writeValueAsString(Map.of("mock",true,"fields",Map.of("claimNo","SYNTHETIC"))),failed?"PROVIDER_OR_STORAGE_FAILURE":null);return job;
    }
    @Test void shopUsesDedicatedOcrAndManualFailureFallbackWithoutSkippingGates() throws Exception {
        String id=arrived();
        mvc.perform(get("/api/v1/ocr/configuration").header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotations/cases/{id}/ocr/configuration",id).header("Authorization","Bearer "+shop)).andExpect(status().isOk()).andExpect(jsonPath("$.data.processRequestCounts").doesNotExist());
        mvc.perform(get("/api/v1/funds/cases/{id}",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canRecordPay").value(false)).andExpect(jsonPath("$.data.canRecordReceive").value(false));
        mvc.perform(get("/api/v1/funds/cases/{id}",id).header("Authorization","Bearer "+shop)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canRecordPay").doesNotExist()).andExpect(jsonPath("$.data.canRecordReceive").doesNotExist());
        var bitmap=new java.awt.image.BufferedImage(30,30,java.awt.image.BufferedImage.TYPE_INT_RGB);bitmap.setRGB(1,1,0xffffff);var image=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(bitmap,"png",image);
        var uploaded=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/quotations/cases/{id}/ocr",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.png","image/png",image.toByteArray())).param("assignmentVersion","1").header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jobId=json.readTree(uploaded).path("data").path("id").asText();var claimed=ocrTasks.claim(3,60);assertThat(claimed.id().toString()).isEqualTo(jobId);ocrTasks.finish(claimed,null,"PROVIDER_OR_STORAGE_FAILURE");
        mvc.perform(get("/api/v1/ocr/{id}",jobId).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        request(shop,"ocr/"+jobId+"/review",Map.of("expectedVersion",0,"candidate",Map.of()),403);
        request(shop,"ocr/"+jobId+"/confirm",Map.of("expectedVersion",0),403);
        var quote=rawBody(id,List.of(Map.of("description","合成手填项目","quantity",2,"unitPrice","10.00")));String key=UUID.randomUUID().toString();
        var result=request(shop,path(id,"raw-quotes"),quote,key,200);String business=result.path("rawQuote").path("id").asText();assertThat(result.path("rawQuote").path("total").asText()).isEqualTo("20.00");
        assertThat(result.has("formalQuote")).isFalse();assertThat(result.has("assessment")).isFalse();assertThat(result.path("gate").path("allowed").asBoolean()).isFalse();
        assertThat(request(shop,path(id,"raw-quotes"),quote,key,200)).isEqualTo(result);
        request(shop,path(id,"raw-quotes"),rawBody(id,List.of(Map.of("description","变更","quantity",1,"unitPrice","1.00"))),key,409);
        assertThat(db.queryForObject("select state from ocr_job where id=?::uuid",String.class,jobId)).isEqualTo("FAILED");
        assertThat(db.queryForObject("select count(*) from quote_raw where work_order_id=?::uuid",Integer.class,id)).isEqualTo(1);
        mvc.perform(get("/api/v1/quotations/cases/{id}",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rawQuote.originals[0].id").exists());
        var outsider=createUser("REPAIR_SHOP",createEligibleShop());String other=login(outsider.path("username").asText(),"H5");
        mvc.perform(get("/api/v1/quotations/cases/{id}/ocr/{job}",id,jobId).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotations/cases/{id}/ocr/{job}",id,jobId).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        assertThatThrownBy(()->db.update("update quote_raw set total=0 where id=?::uuid",business)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成通知单例外"),200);review(id,"FIXED_AMOUNT","0.00",200);
        var loss=documentJob(id,customerService,"LOSS_ASSESSMENT",false);String lossId=loss.path("id").asText();
        request(shop,"ocr",Map.of("fileId",loss.path("fileId").asText()),403);
        request(customerService,"ocr/"+lossId+"/review",Map.of("expectedVersion",0,"candidate",Map.of("originalReviewed",true,"adopted","18.00","fields",Map.of("claimNo","原件不同报案号"))),200);
        request(customerService,"ocr/"+lossId+"/confirm",Map.of("expectedVersion",1),200);
        var assessed=request(customerService,path(id,"recognized-assessment"),Map.of("jobId",lossId,"revision",1,"assessment",Map.of("expectedVersion",context(customerService,id).path("version").asInt(),"amount","18.00")),200);
        assertThat(assessed.path("assessment").path("amount").asText()).isEqualTo("18.00");
        assertThat(assessed.path("gate").path("allowed").asBoolean()).isFalse();
        confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");start(id,200);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("REPAIR"));
        assertThat(db.queryForObject("select claim_no from work_order where id=?::uuid",String.class,id)).doesNotContain("原件不同");
    }

    @Test void textDraftPreservesOriginalCandidateAndAdoptionWithNoOcrCall() throws Exception {
        long count=db.queryForObject("select count(*) from ocr_job",Long.class);String text="车主姓名：文字甲\n车主姓名：文字乙\n保险公司：合成保险\n报案号：TEXT-SYNTHETIC";
        var candidate=request(customerService,"work-orders/text/extract",Map.of("text",text),200);assertThat(candidate.path("fields").has("ownerName")).isFalse();assertThat(candidate.path("fields").path("claimNo").asText()).isEqualTo("TEXT-SYNTHETIC");
        var draft=requiredDraft("TEXT-"+UUID.randomUUID(),OWNER_PHONE,"VIN-"+UUID.randomUUID(),Instant.now());String key=UUID.randomUUID().toString();
        request(customerService,"work-orders/text/draft",Map.of("text",text,"confirmed",false,"draft",draft),409);
        var body=Map.of("text",text,"confirmed",true,"draft",draft);var result=request(customerService,"work-orders/text/draft",body,key,200);assertThat(result.path("status").asText()).isEqualTo("DRAFT");
        assertThat(request(customerService,"work-orders/text/draft",body,key,200).path("id")).isEqualTo(result.path("id"));
        request(customerService,"work-orders/text/draft",Map.of("text",text+"变更","confirmed",true,"draft",draft),key,409);
        request(admin,"work-orders/text/extract",Map.of("text",text),200);request(shop,"work-orders/text/extract",Map.of("text",text),403);request(owner,"work-orders/text/extract",Map.of("text",text),403);
        assertThat(db.queryForObject("select original_text from text_draft_source where work_order_id=?::uuid",String.class,result.path("id").asText())).isEqualTo(text);
        mvc.perform(get("/api/v1/work-orders/{id}/text-source",result.path("id").asText()).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.text").value(text));
        mvc.perform(get("/api/v1/work-orders/{id}/text-source",result.path("id").asText()).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select count(*) from ocr_job",Long.class)).isEqualTo(count);
    }

    @Test void legacyPendingAssessmentWithoutFormalQuoteOpensEarliestReviewWithoutRewritingHistory() throws Exception {
        String id=arrived();raw(id,"1.00");UUID assessment=UUID.randomUUID();
        long actor=db.queryForObject("select id from app_user where username='dev_customer_service'",Long.class);
        db.update("insert into quote_assessment(id,work_order_id,version_no,amount,file_snapshot,created_by) values(?,?::uuid,1,1.00,'[]',?)",assessment,id,actor);
        db.update("update quotation_context set assessment_id=?,insurer_confirmed=true,service_confirmed=true where work_order_id=?::uuid",assessment,id);
        db.update("update work_order set status='WAITING_REPAIR_AUTHORIZATION' where id=?::uuid",id);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("QUOTE_REVIEW")).andExpect(jsonPath("$.data.status").value("WAITING_REPAIR_AUTHORIZATION")).andExpect(jsonPath("$.data.canAct").value(true)).andExpect(jsonPath("$.data.legacyNeedsReview").value(true));
        assertThat(db.queryForObject("select status from work_order where id=?::uuid",String.class,id)).isEqualTo("WAITING_REPAIR_AUTHORIZATION");
        prepareFormal(id);assertThat(context(customerService,id).hasNonNull("assessment")).isFalse();
        assertThat(db.queryForObject("select count(*) from quote_assessment where work_order_id=?::uuid",Long.class,id)).isEqualTo(1);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("INSURER_ASSESSMENT"));
        db.update("update quotation_context set formal_quote_id=null where work_order_id=?::uuid",id);db.update("update work_order set status='REPAIRING' where id=?::uuid",id);
        mvc.perform(get("/api/v1/work-orders/{id}/workflow",id).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentNode").value("REPAIR")).andExpect(jsonPath("$.data.nodes[3].state").value("UNRECORDED")).andExpect(jsonPath("$.data.nodes[4].state").value("UNRECORDED"));
    }

}
