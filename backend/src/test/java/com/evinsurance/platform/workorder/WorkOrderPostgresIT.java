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
        submit(customerService,incompleteId,false,null,"invalid-"+incompleteId,400);

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
