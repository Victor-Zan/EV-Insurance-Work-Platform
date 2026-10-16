package com.evinsurance.platform.workorder;

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
class WorkOrderPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SECRET=secret();
    private static final String SCHEMA="work_order_it_"+UUID.randomUUID().toString().replace("-","");
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
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))registry.add(name,()->{String value=System.getenv("TEST_"+name);if(value==null||value.isBlank())throw new IllegalStateException("Real MinIO test configuration required");return value;});
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

    private JsonNode api(String token,String method,String path,Object body,String key,int code) throws Exception {
        var request=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};
        request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String uniquePhone(){return "139"+String.format("%08d",Math.abs(UUID.randomUUID().getMostSignificantBits()%100000000));}

    @Test void minimalIntakeNeedsRequiredFieldsAndScenePhotoButVehicleDescriptionsAreOptional() throws Exception {
        var valid=new HashMap<String,Object>();valid.put("claimNo","INTAKE-"+UUID.randomUUID());valid.put("ownerPhone",uniquePhone());valid.put("vehiclePlate","粤B8Z71M");valid.put("surveyorName","合成查勘");valid.put("surveyorPhone","13926018473");
        var draft=api(customerService,"POST","/api/v1/work-orders/drafts",valid,null,200);String id=draft.path("id").asText();
        api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"missing-photo-"+id,409);
        String photo=scenePhoto(customerService,id);
        var badPlate=new HashMap<>(valid);badPlate.put("vehiclePlate","无牌???");
        api(customerService,"PUT","/api/v1/work-orders/"+id+"/draft",badPlate,null,200);
        api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"bad-plate-"+id,409);
        for(String field:valid.keySet()){
            var missing=new HashMap<>(valid);missing.remove(field);
            api(customerService,"PUT","/api/v1/work-orders/"+id+"/draft",missing,null,200);
            api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"missing-"+field+id,409);
        }
        api(customerService,"PUT","/api/v1/work-orders/"+id+"/draft",valid,null,200);
        var formal=api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"valid-"+id,200);
        assertThat(formal.path("status").asText()).isEqualTo("PENDING_DISPATCH");assertThat(formal.path("surveyorName").asText()).isEqualTo("合成查勘");
        for(String field:List.of("vehicleColor","vehicleBrand","vehicleModel"))assertThat(formal.hasNonNull(field)).isFalse();
        assertThat(formal.hasNonNull("insuranceCompany")).isFalse();assertThat(formal.hasNonNull("accidentRegionId")).isFalse();
        assertThat(api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"valid-"+id,200).path("id")).isEqualTo(formal.path("id"));
        api(customerService,"POST","/api/v1/work-orders/"+id+"/dispatch",Map.of("shopId",shopId),"no-address-"+id,409);
        var location=Map.of("expectedVersion",formal.path("version").asLong(),"regionId",regionId,"address","合成接车地点","confirmed",true);
        api(shop,"PUT","/api/v1/work-orders/"+id+"/dispatch-location",location,null,403);
        api(customerService,"PUT","/api/v1/work-orders/"+id+"/dispatch-location",location,null,200);
        api(customerService,"PUT","/api/v1/work-orders/"+id+"/dispatch-location",location,null,409);
        long jobsBefore=db.queryForObject("SELECT count(*) FROM ocr_job",Long.class);
        api(customerService,"POST","/api/v1/ocr",Map.of("fileId",photo),"scene-not-ocr-"+id,400);
        assertThat(db.queryForObject("SELECT count(*) FROM ocr_job",Long.class)).isEqualTo(jobsBefore);
        long nearest=createEligibleShop();db.update("UPDATE repair_shop SET address=? WHERE id=?","合成接车地点",nearest);db.update("UPDATE repair_shop SET address=? WHERE id=?","另一个合成地点",shopId);
        var shops=api(customerService,"GET","/api/v1/work-orders/"+id+"/eligible-shops?size=1",null,null,200);
        assertThat(shops.path("records").size()).isEqualTo(1);
        assertThat(shops.path("records").get(0).path("id").asLong()).isEqualTo(nearest);assertThat(shops.path("records").get(0).path("distanceMetres").asDouble()).isZero();
        var next=api(customerService,"GET","/api/v1/work-orders/"+id+"/eligible-shops?size=1&page=2",null,null,200);assertThat(next.path("records").get(0).path("id").asLong()).isNotEqualTo(nearest);assertThat(next.path("records").get(0).path("distanceMetres").asDouble()).isGreaterThanOrEqualTo(0);
        var mapped=api(customerService,"GET","/api/v1/maps/cases/"+id+"/shops?size=1",null,null,200);
        assertThat(mapped.path("records").get(0).path("id").asLong()).isEqualTo(nearest);
        assertThat(mapped.path("records").get(0).path("distanceMetres").asDouble()).isZero();
        assertThat(mapped.path("records").get(0).path("mockLocation").path("mock").asBoolean()).isTrue();
        var dispatched=api(customerService,"POST","/api/v1/work-orders/"+id+"/dispatch",Map.of("shopId",shopId),"dispatch-"+id,200);
        var shopView=api(shop,"GET","/api/v1/work-orders/"+id,null,null,200);assertThat(shopView.hasNonNull("surveyorPhone")).isFalse();
        int assignment=dispatched.path("currentAssignment").path("assignmentVersion").asInt();
        api(shop,"POST","/api/v1/work-orders/"+id+"/accept",Map.of("assignmentVersion",assignment),"accept-"+id,200);
        api(shop,"POST","/api/v1/work-orders/"+id+"/arrive",Map.of("assignmentVersion",assignment),"arrive-"+id,200);
        var raw=api(shop,"POST","/api/v1/quotations/cases/"+id+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",assignment,"lines",List.of(Map.of("description","合成配件","quantity",1,"unitPrice","18.30"))),"raw-"+id,200);
        var reviewed=api(customerService,"POST","/api/v1/quotations/cases/"+id+"/formal-quotes",Map.of("expectedVersion",raw.path("version").asInt(),"rawQuoteId",raw.path("rawQuote").path("id").asText(),"mode","FIXED_AMOUNT","fixedAmount","0.00"),"formal-"+id,200);
        assertThat(reviewed.path("formalQuote").path("total").asText()).isEqualTo("18.30");
        assertThat(db.queryForObject("SELECT count(*) FROM case_file WHERE work_order_id=?::uuid AND category='NOTICE'",Long.class,id)).isZero();
    }
    @Test void ownerContactAndPendingBindingUseRealApisAndNeverTransferOwnership() throws Exception {
        String phone=uniquePhone(),suffix=UUID.randomUUID().toString();
        var pending=formal(customerService,"BIND-"+suffix,phone,"VIN-"+suffix,Instant.now().minus(100,ChronoUnit.DAYS));
        String id=pending.path("id").asText();assertThat(pending.path("ownerBindingStatus").asText()).isEqualTo("PENDING");
        var account=createUser("OWNER",null);long userId=account.path("id").asLong();String newOwner=login(account.path("username").asText(),"H5");
        api(newOwner,"GET","/api/v1/work-orders/"+id,null,null,403);
        api(customerService,"PUT","/api/v1/admin/users/"+userId+"/owner-contact",Map.of("phone",phone,"reason","核对车主资料"),null,403);
        api(admin,"PUT","/api/v1/admin/users/"+userId+"/owner-contact",Map.of("phone",phone,"reason","核对车主资料"),null,200);
        var body=Map.of("expectedVersion",pending.path("version").asLong(),"reason","补齐车主账号后重新核对");String key="bind-"+suffix;
        var bound=api(customerService,"POST","/api/v1/work-orders/"+id+"/owner-binding",body,key,200);
        assertThat(bound.path("ownerBindingStatus").asText()).isEqualTo("BOUND");
        var repeated=api(customerService,"POST","/api/v1/work-orders/"+id+"/owner-binding",body,key,200);
        assertThat(repeated.path("version").asLong()).isEqualTo(bound.path("version").asLong());
        api(customerService,"POST","/api/v1/work-orders/"+id+"/owner-binding",Map.of("expectedVersion",pending.path("version").asLong(),"reason","different"),key,409);
        api(newOwner,"GET","/api/v1/work-orders/"+id,null,null,200);api(owner,"GET","/api/v1/work-orders/"+id,null,null,403);
        api(newOwner,"POST","/api/v1/work-orders/"+id+"/owner-binding",body,"owner-denied",403);
        api(customerService,"POST","/api/v1/work-orders/"+id+"/owner-binding",Map.of("expectedVersion",bound.path("version").asLong(),"reason","不得转移"),"second-"+suffix,409);
        api(admin,"PUT","/api/v1/admin/users/"+userId+"/owner-contact",Map.of("phone",uniquePhone(),"reason","资料修正"),null,200);
        api(newOwner,"GET","/api/v1/work-orders/"+id,null,null,200);
        assertThat(db.queryForObject("select owner_user_id from work_order where id=?",Long.class,UUID.fromString(id))).isEqualTo(userId);
        assertThat(db.queryForObject("select count(*) from work_order_status_history where work_order_id=? and action='OWNER_BIND'",Long.class,UUID.fromString(id))).isEqualTo(1L);
        assertThat(db.queryForObject("select count(*) from audit_log where action='OWNER_CONTACT_UPDATE' and summary like ?",Long.class,"%"+phone+"%")).isZero();
        var automatic=api(admin,"POST","/api/v1/admin/users",Map.of("username","phone_"+UUID.randomUUID().toString().replace("-",""),"displayName","新车主","password",PASSWORD,"roles",List.of("OWNER"),"ownerPhone",phone),null,200);
        var next=formal(customerService,"AUTO-"+suffix,phone,"AUTO-VIN-"+suffix,Instant.now().minus(200,ChronoUnit.DAYS));
        assertThat(db.queryForObject("select owner_user_id from work_order where id=?",Long.class,UUID.fromString(next.path("id").asText()))).isEqualTo(automatic.path("id").asLong());
    }
    @Test void ambiguousOwnerRemainsPendingAndConcurrentMatchingBindsOnlyOnce() throws Exception {
        String phone=uniquePhone(),suffix=UUID.randomUUID().toString();
        var one=createUser("OWNER",null);var two=createUser("OWNER",null);
        for(var account:List.of(one,two))api(admin,"PUT","/api/v1/admin/users/"+account.path("id").asLong()+"/owner-contact",Map.of("phone",phone,"reason","核对资料"),null,200);
        var pending=formal(customerService,"MULTI-"+suffix,phone,"MULTI-VIN-"+suffix,Instant.now().minus(300,ChronoUnit.DAYS));String id=pending.path("id").asText();
        var body=Map.of("expectedVersion",pending.path("version").asLong(),"reason","待匹配核对");
        var matched=api(customerService,"POST","/api/v1/work-orders/"+id+"/owner-binding",body,"multi-"+suffix,200);
        assertThat(matched.path("ownerBindingStatus").asText()).isEqualTo("PENDING");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/users/{id}/enabled",two.path("id").asLong()).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        List<Callable<Integer>> calls=new ArrayList<>();
        for(int i=0;i<2;i++){String key="concurrent-bind-"+suffix+"-"+i;calls.add(()->mvc.perform(post("/api/v1/work-orders/"+id+"/owner-binding").header("Authorization","Bearer "+customerService).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getStatus());}
        try(var pool=Executors.newFixedThreadPool(2)){var result=pool.invokeAll(calls);assertThat(List.of(result.get(0).get(),result.get(1).get())).containsExactlyInAnyOrder(200,409);}
        api(admin,"POST","/api/v1/work-orders/"+id+"/cancel",Map.of("reason","取消测试","description","取消测试说明","notifyOwner",false,"notifyShop",false),"cancel-bind-"+suffix,200);
        api(customerService,"POST","/api/v1/work-orders/"+id+"/owner-binding",body,"cancelled-bind-"+suffix,409);
    }
    @Test void cancellationNoticesRemainReadableOnlyForSelectedBoundOwnerAndOriginalShop() throws Exception {
        String suffix=UUID.randomUUID().toString();var order=formal(customerService,"NOTICE-"+suffix,OWNER_PHONE,"NOTICE-VIN-"+suffix,Instant.now().minus(400,ChronoUnit.DAYS));String id=order.path("id").asText();
        dispatch(customerService,id,shopId,false,null,"notice-dispatch-"+suffix,200);
        var cancel=Map.of("reason","客户取消预约","description","仅内部取消说明","notifyOwner",true,"notifyShop",true);String key="notice-cancel-"+suffix;
        api(customerService,"POST","/api/v1/work-orders/"+id+"/cancel",cancel,key,200);api(customerService,"POST","/api/v1/work-orders/"+id+"/cancel",cancel,key,200);
        long notices=db.queryForObject("select count(*) from in_app_notification n join notification_event e on e.audit_id=n.event_id where e.work_order_id=? and e.kind='CASE_CANCELLED'",Long.class,UUID.fromString(id));assertThat(notices).isEqualTo(2L);
        for(String token:List.of(owner,shop)){
            var inbox=api(token,"GET","/api/v1/notifications?size=100",null,null,200);
            JsonNode notice=null;for(var row:inbox.path("records"))if(row.path("caseId").asText().equals(id)&&row.path("kind").asText().equals("CASE_CANCELLED"))notice=row;
            assertThat(notice).isNotNull();assertThat(notice.path("reason").asText()).isEqualTo("客户取消预约");
            assertThat(notice.toString()).doesNotContain(OWNER_PHONE,"仅内部取消说明","amount","ownerName","storageKey");
            api(token,"POST","/api/v1/notifications/"+notice.path("id").asLong()+"/read",Map.of(),null,200);
            api(customerService,"POST","/api/v1/notifications/"+notice.path("id").asLong()+"/read",Map.of(),null,403);
        }
        var noNotice=formal(customerService,"SILENT-"+suffix,OWNER_PHONE,"SILENT-VIN-"+suffix,Instant.now().minus(500,ChronoUnit.DAYS));String silent=noNotice.path("id").asText();
        api(customerService,"POST","/api/v1/work-orders/"+silent+"/cancel",Map.of("reason","不通知","description","测试说明","notifyOwner",false,"notifyShop",false),"silent-"+suffix,200);
        assertThat(db.queryForObject("select count(*) from in_app_notification n join notification_event e on e.audit_id=n.event_id where e.work_order_id=? and e.kind='CASE_CANCELLED'",Long.class,UUID.fromString(silent))).isZero();
    }
    @Test void staffWorkbenchesFilterIndependentStatusesAndDenyExternalRoles() throws Exception {
        String suffix=UUID.randomUUID().toString();var order=formal(customerService,"FUNDS-WB-"+suffix,OWNER_PHONE,"FUNDS-VIN-"+suffix,Instant.now().minus(600,ChronoUnit.DAYS));String id=order.path("id").asText();
        api(customerService,"POST","/api/v1/funds/cases/"+id+"/targets",Map.of("expectedVersion",0,"direction","RECEIVE","amount","10.00","reason","人工应收"),"wb-target-"+suffix,200);
        api(customerService,"POST","/api/v1/funds/cases/"+id+"/entries",Map.of("expectedVersion",1,"direction","RECEIVE","transactionNo","wb-"+suffix,"amount","3.25","occurredAt",Instant.now().toString()),"wb-entry-"+suffix,200);
        var result=api(admin,"GET","/api/v1/funds/workbench?query=FUNDS-WB-"+suffix+"&status=PENDING_DISPATCH&receivableStatus=PARTIAL&payableStatus=UNSET",null,null,200);
        assertThat(result.path("total").asLong()).isEqualTo(1);var row=result.path("records").get(0);assertThat(row.path("receivable").path("net").asText()).isEqualTo("3.25");assertThat(row.toString()).doesNotContain(OWNER_PHONE,"markup","rawTotal","storageKey");
        var targetHistory=api(admin,"GET","/api/v1/funds/cases/"+id+"/targets",null,null,200);assertThat(targetHistory.path("records").get(0).path("actorId").asLong()).isPositive();
        UUID complaint=UUID.randomUUID();db.update("insert into complaint(id,work_order_id,owner_id,shop_id,assignment_version,description,photo_snapshot) values(?,?,?,?,?,?,?)",complaint,UUID.fromString(id),db.queryForObject("select id from app_user where username='dev_owner'",Long.class),shopId,1,"工作台查询测试","[]");
        var complaints=api(customerService,"GET","/api/v1/complaints?query=FUNDS-WB-"+suffix+"&status=SUBMITTED",null,null,200);assertThat(complaints.path("total").asLong()).isEqualTo(1);
        assertThat(api(admin,"GET","/api/v1/complaints?query=FUNDS-WB-"+suffix+"&status=CLOSED",null,null,200).path("total").asLong()).isZero();
        for(String token:List.of(owner,shop)){api(token,"GET","/api/v1/funds/workbench",null,null,403);api(token,"GET","/api/v1/complaints",null,null,403);}
        api(admin,"GET","/api/v1/funds/workbench?receivableStatus=WRONG",null,null,400);api(admin,"GET","/api/v1/complaints?status=WRONG",null,null,400);
        api(admin,"GET","/api/v1/funds/workbench?size=101",null,null,400);
        flyway.validate();
    }

    private int assignmentVersion(JsonNode order) {
        return order.path("currentAssignment").path("assignmentVersion").asInt();
    }

    @Test
    void draftsFormalValidationStrongDuplicatesAndCriticalChangesFollowTheRules() throws Exception {
        JsonNode second=createUser("CUSTOMER_SERVICE",null);
        String secondToken=login(second.path("username").asText(),"ADMIN");
        Map<String,Object> valid=requiredDraft("CLAIM-"+UUID.randomUUID(),phone(),"VIN-"+UUID.randomUUID(),Instant.now());
        JsonNode draft=createDraft(customerService,valid);
        String id=draft.path("id").asText();
        assertThat(draft.path("dataSource").asText()).isEqualTo("MANUAL");
        assertThat(draft.path("currentResponsibleId").asLong()).isEqualTo(draft.path("createdBy").asLong());
        assertThat(draft.hasNonNull("businessNo")).isFalse();
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+secondToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+admin)).andExpect(status().isOk());

        String disposable=createDraft(secondToken,valid).path("id").asText();
        mvc.perform(delete("/api/v1/work-orders/{id}/draft",disposable).header("Authorization","Bearer "+secondToken)).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from work_order where id=?::uuid",Long.class,disposable)).isZero();

        Map<String,Object> incomplete=new HashMap<>(valid);
        incomplete.remove("vehicleVin");
        String incompleteId=createDraft(customerService,incomplete).path("id").asText();
        submit(customerService,incompleteId,false,null,"invalid-"+incompleteId,409);

        JsonNode formal=submit(customerService,id,false,null,"formal-"+id,200).path("data");
        String businessNo=formal.path("businessNo").asText();
        assertThat(businessNo).matches("EVR-\\d{8}-\\d{6}");
        Map<String,Object> duplicate=new HashMap<>(valid);
        duplicate.put("insuranceCompany","平安保险");
        duplicate.put("claimNo",String.valueOf(valid.get("claimNo")).toLowerCase());
        duplicate.put("vehicleVin","VIN-"+UUID.randomUUID());
        String duplicateId=createDraft(admin,duplicate).path("id").asText();
        JsonNode conflict=submit(admin,duplicateId,false,null,"duplicate-"+duplicateId,409);
        assertThat(conflict.path("code").asText()).isEqualTo("DUPLICATE_CASE");
        assertThat(conflict.path("message").asText()).contains(businessNo);

        String replacementVin="REPLACEMENT-VIN-"+UUID.randomUUID();
        mvc.perform(put("/api/v1/work-orders/{id}/critical-fields",id).header("Authorization","Bearer "+customerService)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "insuranceCompany","太平洋保险","claimNo","UPDATED-"+UUID.randomUUID(),"vehicleVin",replacementVin,
                "vehiclePlate","沪A12345","vehicleOtherIdentifier","OTHER-1","reason","保险公司修正报案信息"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicleVin").value(replacementVin));
        String audit=db.queryForObject("select summary from audit_log where object_id=? and action='WORK_ORDER_CRITICAL_UPDATE' order by id desc limit 1",String.class,id);
        assertThat(audit).contains("reason=保险公司修正报案信息").doesNotContain(replacementVin);
    }

    @Test
    void possibleDuplicatesAreConfigurableAndRequireAnAuditedReason() throws Exception {
        mvc.perform(get("/api/v1/work-orders/configuration").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.possibleDuplicateDays").value(7));
        mvc.perform(put("/api/v1/work-orders/configuration").header("Authorization","Bearer "+customerService)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("possibleDuplicateDays",5,"reason","无权修改"))))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/work-orders/configuration").header("Authorization","Bearer "+admin)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("possibleDuplicateDays",7,"reason","恢复阶段默认值"))))
            .andExpect(status().isOk());

        Instant accident=Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String vin="VIN-DUP-"+UUID.randomUUID();
        formal(customerService,"BASE-"+UUID.randomUUID(),phone(),vin,accident);
        String candidate=createDraft(customerService,requiredDraft("CANDIDATE-"+UUID.randomUUID(),phone(),vin,accident.plus(6,ChronoUnit.DAYS))).path("id").asText();
        assertThat(submit(customerService,candidate,false,null,"warn-"+candidate,409).path("code").asText()).isEqualTo("POSSIBLE_DUPLICATE");
        submit(customerService,candidate,true,null,"missing-reason-"+candidate,400);
        JsonNode accepted=submit(customerService,candidate,true,"已核对为同车不同事故","confirmed-"+candidate,200).path("data");
        assertThat(accepted.path("status").asText()).isEqualTo("PENDING_DISPATCH");
        String history=db.queryForObject("select reason from work_order_status_history where work_order_id=?::uuid and action='SUBMIT'",String.class,candidate);
        assertThat(history).contains("已核对为同车不同事故");

        String repeatPhone=phone();
        JsonNode first=formal(customerService,"SHOP-A-"+UUID.randomUUID(),repeatPhone,"VIN-"+UUID.randomUUID(),accident);
        dispatch(customerService,first.path("id").asText(),shopId,false,null,"dispatch-first-"+UUID.randomUUID(),200);
        JsonNode second=formal(customerService,"SHOP-B-"+UUID.randomUUID(),repeatPhone,"VIN-"+UUID.randomUUID(),accident.plus(1,ChronoUnit.DAYS));
        String secondId=second.path("id").asText();
        assertThat(dispatch(customerService,secondId,shopId,false,null,"dispatch-warning-"+secondId,409).path("code").asText())
            .isEqualTo("POSSIBLE_DUPLICATE");
        dispatch(customerService,secondId,shopId,true,"客服确认不是重复派修","dispatch-confirmed-"+secondId,200);
    }

    @Test
    void lifecycleIsIdempotentAndRejectsOldAssignmentsAndCrossShopActions() throws Exception {
        long otherShopId=createEligibleShop();
        JsonNode otherUser=createUser("REPAIR_SHOP",otherShopId);
        String otherShop=login(otherUser.path("username").asText(),"H5");
        JsonNode order=formal(customerService,"FLOW-"+UUID.randomUUID(),phone(),"VIN-"+UUID.randomUUID(),Instant.now());
        String id=order.path("id").asText();
        order=dispatch(customerService,id,shopId,false,null,"dispatch-"+id,200).path("data");
        int firstVersion=assignmentVersion(order);
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+otherShop)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+otherShop)
            .header("Idempotency-Key","cross-shop").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion)))).andExpect(status().isForbidden());

        String acceptKey="accept-"+id;
        String firstAccept=mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key",acceptKey).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ARRIVAL"))
            .andReturn().getResponse().getContentAsString();
        String repeatedAccept=mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key",acceptKey).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(repeatedAccept).path("data")).isEqualTo(json.readTree(firstAccept).path("data"));
        mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","semantic-accept-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion)))).andExpect(status().isOk());
        assertThat(historyCount(id,"ACCEPT")).isEqualTo(1);

        mvc.perform(post("/api/v1/work-orders/{id}/arrival-exceptions",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","exception-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion,"reason","车辆运输延误"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARRIVAL_EXCEPTION"));
        mvc.perform(post("/api/v1/work-orders/{id}/continue-waiting",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","continue-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion,"reason","继续联系司机"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ARRIVAL"));
        String arriveKey="arrive-"+id;
        mvc.perform(post("/api/v1/work-orders/{id}/arrive",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key",arriveKey).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARRIVED"));
        mvc.perform(post("/api/v1/work-orders/{id}/arrive",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","semantic-arrive-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion)))).andExpect(status().isOk());
        assertThat(historyCount(id,"ARRIVE")).isEqualTo(1);

        Map<String,Object> transfer=Map.of("shopId",otherShopId,"assignmentVersion",firstVersion,"reason","管理员异常改派",
            "vehicleNotArrivedConfirmed",false,"transferDescription","拖车转运至第二网点","confirmPossibleDuplicate",false);
        mvc.perform(post("/api/v1/work-orders/{id}/reassign",id).header("Authorization","Bearer "+customerService)
            .header("Idempotency-Key","forbidden-reassign-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(transfer))).andExpect(status().isForbidden());
        String transferred=mvc.perform(post("/api/v1/work-orders/{id}/reassign",id).header("Authorization","Bearer "+admin)
            .header("Idempotency-Key","admin-reassign-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(transfer))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING_ACCEPTANCE"))
            .andReturn().getResponse().getContentAsString();
        int newVersion=assignmentVersion(json.readTree(transferred).path("data"));
        assertThat(newVersion).isEqualTo(firstVersion+1);
        mvc.perform(post("/api/v1/work-orders/{id}/arrive",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","old-arrive-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion)))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+otherShop)
            .header("Idempotency-Key","new-accept-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",newVersion))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ARRIVAL"));
    }

    @Test
    void rejectionCancellationReassignmentAndWholeOrderCancellationKeepHistory() throws Exception {
        JsonNode order=formal(customerService,"EXCEPTION-"+UUID.randomUUID(),phone(),"VIN-"+UUID.randomUUID(),Instant.now());
        String id=order.path("id").asText();
        order=dispatch(customerService,id,shopId,false,null,"dispatch-1-"+id,200).path("data");
        int version1=assignmentVersion(order);
        mvc.perform(post("/api/v1/work-orders/{id}/reject",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","reject-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",version1,"reason","当前工位不足"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH"));

        order=dispatch(customerService,id,shopId,false,null,"dispatch-2-"+id,200).path("data");
        int version2=assignmentVersion(order);
        mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","accept-2-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",version2)))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/work-orders/{id}/reassign",id).header("Authorization","Bearer "+customerService)
            .header("Idempotency-Key","invalid-reassign-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("shopId",shopId,"assignmentVersion",version2,"reason","车辆未到店改派",
                "vehicleNotArrivedConfirmed",false,"confirmPossibleDuplicate",false))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/work-orders/{id}/arrival-exceptions",id).header("Authorization","Bearer "+customerService)
            .header("Idempotency-Key","late-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",version2,"reason","联系不上司机"))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/work-orders/{id}/cancel-assignment",id).header("Authorization","Bearer "+customerService)
            .header("Idempotency-Key","cancel-assignment-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",version2,"reason","改由人工重新安排"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH"));

        order=dispatch(customerService,id,shopId,false,null,"dispatch-3-"+id,200).path("data");
        int version3=assignmentVersion(order);
        mvc.perform(post("/api/v1/work-orders/{id}/cancel",id).header("Authorization","Bearer "+customerService)
            .header("Idempotency-Key","cancel-work-order-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason","车主撤案","description","保险公司已确认撤销报案",
                "notifyOwner",true,"notifyShop",true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key","after-cancel-"+id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",version3)))).andExpect(status().isConflict());
        assertThat(db.queryForList("select assignment_version,status from work_order_assignment where work_order_id=?::uuid order by assignment_version",id))
            .hasSize(3);
        assertThat(historyCount(id,"REJECT")).isEqualTo(1);
        assertThat(historyCount(id,"CANCEL_ASSIGNMENT")).isEqualTo(1);
        assertThat(historyCount(id,"CANCEL_WORK_ORDER")).isEqualTo(1);
    }

    @Test
    void scopesAndSensitiveFieldsDifferBetweenListsAuthorizedDetailsAndOwners() throws Exception {
        long otherShopId=createEligibleShop();
        JsonNode otherShopUser=createUser("REPAIR_SHOP",otherShopId);
        String otherShop=login(otherShopUser.path("username").asText(),"H5");
        JsonNode otherOwnerUser=createUser("OWNER",null);
        String otherOwner=login(otherOwnerUser.path("username").asText(),"H5");
        String vin="SENSITIVE-VIN-123456789";
        Map<String,Object> request=requiredDraft("SCOPE-"+UUID.randomUUID(),OWNER_PHONE,vin,Instant.now());
        request.put("policyNo","POLICY-123456789");
        request.put("accidentAddress","上海市浦东新区测试路 1 号");
        String id=createDraft(customerService,request).path("id").asText();
        submit(customerService,id,false,null,"submit-"+id,200);
        dispatch(customerService,id,shopId,false,null,"dispatch-"+id,200);

        mvc.perform(get("/api/v1/work-orders").param("query",String.valueOf(request.get("claimNo")))
            .header("Authorization","Bearer "+shop)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].ownerName").value("王**"))
            .andExpect(jsonPath("$.data.records[0].ownerPhone").value("138****1234"))
            .andExpect(jsonPath("$.data.records[0].vehicleVin").value("SENS****6789"))
            .andExpect(jsonPath("$.data.records[0].accidentAddress").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].accidentDescription").doesNotExist());
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+shop))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownerPhone").value(OWNER_PHONE))
            .andExpect(jsonPath("$.data.vehicleVin").value(vin))
            .andExpect(jsonPath("$.data.policyNo").value("POLI****6789"));
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+otherShop)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentAssignment").doesNotExist());
        mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+otherOwner)).andExpect(status().isForbidden());
        String logs=json.writeValueAsString(db.queryForList("select action,summary from audit_log where object_id=?",id));
        assertThat(logs).doesNotContain(OWNER_PHONE,vin);
    }

    @Test
    void concurrentSubmissionAllocatesUniqueBusinessNumbersAndFlywayIsValid() throws Exception {
        List<String> ids=new ArrayList<>();
        for(int index=0;index<4;index++) {
            String id=createDraft(customerService,requiredDraft("CONCURRENT-"+UUID.randomUUID(),phone(),
                "VIN-"+UUID.randomUUID(),Instant.now().plus(index*20L,ChronoUnit.DAYS))).path("id").asText();
            ids.add(id);
        }
        var pool=Executors.newFixedThreadPool(ids.size());
        try {
            List<Callable<String>> tasks=ids.stream().<Callable<String>>map(id -> () ->
                submit(customerService,id,false,null,"parallel-"+id,200).path("data").path("businessNo").asText()).toList();
            var futures=pool.invokeAll(tasks);
            var numbers=new HashSet<String>();
            for(var future:futures) numbers.add(future.get());
            assertThat(numbers).hasSize(ids.size()).allMatch(number -> number.matches("EVR-\\d{8}-\\d{6}"));
        } finally {
            pool.shutdownNow();
        }
        flyway.validate();
        assertThat(db.queryForObject("select count(*) from work_order_number_counter where last_value<1 or last_value>999999",Long.class)).isZero();
    }

    private long historyCount(String id,String action) {
        return db.queryForObject("select count(*) from work_order_status_history where work_order_id=?::uuid and action=?",Long.class,id,action);
    }

    private String phone() {
        return "13"+UUID.randomUUID().toString().replace("-","").substring(0,9);
    }
}
