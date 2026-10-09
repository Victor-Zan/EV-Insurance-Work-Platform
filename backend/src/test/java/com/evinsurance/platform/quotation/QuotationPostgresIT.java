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
}
