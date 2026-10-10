package com.evinsurance.platform.quotation;

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
    private JsonNode assess(String id,String amount,int expected) throws Exception{return request(customerService,path(id,"assessments"),Map.of("expectedVersion",context(customerService,id).path("version").asInt(),"amount",amount),expected);}
    private JsonNode confirm(String id,String kind) throws Exception{return request(customerService,path(id,"confirmations/"+kind),Map.of("expectedVersion",context(customerService,id).path("version").asInt()),200);}
    private JsonNode start(String id,int expected) throws Exception {var c=context(shop,id);return request(shop,path(id,"start-repair"),Map.of("expectedVersion",c.path("version").asInt(),"assignmentVersion",c.path("assignmentVersion").asInt()),expected);}
    private String upload(String id,String category,String replace,int expected) throws Exception {
        var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf","%PDF-1.7\nSYNTHETIC\n%%EOF".getBytes())).param("category",category).header("Authorization","Bearer "+customerService).header("Idempotency-Key",UUID.randomUUID().toString());if(replace!=null)req.param("replace",replace);
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
    }
    private void authorized(String id) throws Exception {upload(id,"LOSS_ASSESSMENT",null,200);assess(id,"0.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");assertThat(context(shop,id).path("authorized").asBoolean()).isTrue();}
    @Test void immutablePricesFixedPercentageZeroAndExternalExportHaveExactTotals() throws Exception {
        String id=arrived();var body=rawBody(id,List.of(Map.of("description","=SUM(1,2)\n合成配件","quantity",2,"unitPrice","30.00"),Map.of("description","工时","quantity",1,"unitPrice","40.00"),Map.of("description","赠送","quantity",5,"unitPrice","0.00")));
        request(shop,path(id,"raw-quotes"),body,200);review(id,"FIXED_AMOUNT","10.00",409);
        request(customerService,"materials/cases/"+id+"/missing-notice",Map.of("reason","合成验收缺失"),200);
        var fixed=review(id,"FIXED_AMOUNT","10.00",200).path("formalQuote");String fixedId=fixed.path("id").asText();
        assertThat(fixed.path("total").asText()).isEqualTo("110.00");assertThat(fixed.path("lines").get(0).path("externalAmount").asText()).isEqualTo("66.00");assertThat(fixed.path("lines").get(2).path("externalAmount").asText()).isEqualTo("0.00");
        var rate=review(id,"PERCENTAGE","1.23",200).path("formalQuote");assertThat(rate.path("total").asText()).isEqualTo("101.23");assertThat(rate.path("version").asInt()).isEqualTo(2);
        assertThat(db.queryForObject("select total from quote_formal where id=?::uuid",java.math.BigDecimal.class,fixedId)).isEqualByComparingTo("110.00");
        assertThat(db.queryForObject("select unit_price from quote_raw_item where raw_quote_id=?::uuid and line_no=1",java.math.BigDecimal.class,context(shop,id).path("rawQuote").path("id").asText())).isEqualByComparingTo("30.00");
        String csv=mvc.perform(get("/api/v1/quotations/cases/{id}/formal-quotes/{quote}/export",id,fixedId).header("Authorization","Bearer "+customerService)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv).contains("66.00","44.00","110.00","\"'=SUM(1,2)\\n合成配件\"".replace("\\n","\n")).doesNotContain("markup","original","30.00");
        for(String token:List.of(shop,owner))mvc.perform(get("/api/v1/quotations/cases/{id}/formal-quotes/{quote}/export",id,fixedId).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotations/cases/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        var shopView=context(shop,id);assertThat(shopView.has("formalQuote")).isFalse();assertThat(shopView.has("assessment")).isFalse();assertThat(shopView.toString()).doesNotContain("markup","originalUnitPrice","externalUnitPrice");
        mvc.perform(get("/api/v1/quotations/cases/{id}/history?type=FORMAL",id).header("Authorization","Bearer "+shop)).andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update quote_formal set total=total+1 where id=?::uuid",fixedId));
        flyway.validate();
    }
    @Test void allFourIndividualMissingConditionsAreRejectedAndNothingElseIsAdded() throws Exception {
        for(int omitted=0;omitted<4;omitted++) {
            String id=arrived();if(omitted!=0)upload(id,"LOSS_ASSESSMENT",null,200);if(omitted!=1)assess(id,"0.00",200);if(omitted!=2)confirm(id,"INSURER");if(omitted!=3)confirm(id,"CUSTOMER_SERVICE");
            var view=context(shop,id);assertThat(view.path("gate").path("allowed").asBoolean()).isFalse();assertThat(view.path("gate").path("missing").size()).isEqualTo(1);start(id,409);
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
        confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");raw(id,"0.02");var afterRaw=context(customerService,id);assertThat(afterRaw.path("formalQuote").isNull()).isTrue();assertThat(afterRaw.path("authorized").asBoolean()).isFalse();
        review(id,"PERCENTAGE","0.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");start(id,200);
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
        String file=upload(id,"LOSS_ASSESSMENT",null,200);assess(id,"1.00",200);confirm(id,"INSURER");confirm(id,"CUSTOMER_SERVICE");var c=context(shop,id);var startBody=Map.of("expectedVersion",c.path("version").asInt(),"assignmentVersion",c.path("assignmentVersion").asInt());
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
            assertThat(result.path("status").asText()).isEqualTo("COMPLETED");assertThat(result.path("version").asInt()).isEqualTo(2);
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
    private String receivedRepair() throws Exception {String id=completedRepair();request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",1),200);return id;}
    @Test void fundsPartialMultipleReceiptsAndPaymentsRemainSeparateFromRepairAndRoleFiltered() throws Exception {
        String id=receivedRepair();fundsTarget(id,"RECEIVE","100.01",200);fundsTarget(id,"PAY","80.00",200);
        String number="RECEIVE-"+UUID.randomUUID();var first=fundsEntry(id,"RECEIVE",number,"20.00");String key=UUID.randomUUID().toString();request(customerService,"funds/cases/"+id+"/entries",first,key,200);request(customerService,"funds/cases/"+id+"/entries",first,key,200);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","PAY-"+UUID.randomUUID(),"1.00"),409);
        var duplicate=request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE",number,"20.00"),200);assertThat(duplicate.path("duplicate").asBoolean()).isTrue();
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE",number,"21.00"),409);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","RECEIVE-"+UUID.randomUUID(),"80.02"),409);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","RECEIVE-"+UUID.randomUUID(),"80.01"),200);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","PAY-"+UUID.randomUUID(),"30.00"),200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","PAY-"+UUID.randomUUID(),"50.00"),200);
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
        String id=receivedRepair();fundsTarget(id,"RECEIVE","20.00",200);fundsTarget(id,"PAY","20.00",200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"RECEIVE","WITHDRAW-R-"+UUID.randomUUID(),"20.00"),200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","WITHDRAW-P-"+UUID.randomUUID(),"10.00"),200);
        var before=funds(customerService,id);request(customerService,"repairs/cases/"+id+"/receipt/withdraw",Map.of("expectedVersion",2,"reason","合成收车撤回"),200);assertThat(funds(customerService,id)).isEqualTo(before);
        request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","AFTER-WITHDRAW-"+UUID.randomUUID(),"10.00"),409);fundsTarget(id,"PAY",null,409);fundsTarget(id,"PAY","9.00",409);
        request(owner,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",3),200);request(customerService,"funds/cases/"+id+"/entries",fundsEntry(id,"PAY","AFTER-RERECEIVE-"+UUID.randomUUID(),"10.00"),200);assertThat(funds(customerService,id).path("payable").path("status").asText()).isEqualTo("SETTLED");
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
        String id=receivedRepair();fundsTarget(id,"RECEIVE","100.00",200);fundsTarget(id,"PAY","80.00",200);notificationProjector.project();
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
}
