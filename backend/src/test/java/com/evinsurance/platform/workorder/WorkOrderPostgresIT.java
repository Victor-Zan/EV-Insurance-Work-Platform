package com.evinsurance.platform.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient;
import com.evinsurance.platform.assistant.infrastructure.AssistantModels;
import com.evinsurance.platform.foundation.api.ApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class WorkOrderPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SECRET=secret();
    private static final String SCHEMA="work_order_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String OWNER_PHONE="13800001234";
    private static final java.util.concurrent.atomic.AtomicBoolean DEVELOPMENT_OWNER=new java.util.concurrent.atomic.AtomicBoolean(false);

    private static String secret() {
        byte[] key=new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // This suite uses the existing account fixture, without unrelated local map demo data.
        try {
            var directory=java.nio.file.Files.createTempDirectory("work-order-foundation-");directory.toFile().deleteOnExit();
            var seed=directory.resolve("V2.1__development_foundation.sql");
            try(var input=WorkOrderPostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")){
                java.nio.file.Files.copy(java.util.Objects.requireNonNull(input),seed);
            }
            seed.toFile().deleteOnExit();
            registry.add("spring.flyway.locations",()->"classpath:db/migration,filesystem:"+directory);
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
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
        registry.add("SILICONFLOW_API_KEY",()->"synthetic-assistant-test-only");
        registry.add("BIGMODEL_API_KEY",()->"");
        registry.add("DEV_OWNER_BINDING_ENABLED",()->DEVELOPMENT_OWNER.get());
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
    @MockitoBean BigModelClient assistantModel;
    @Autowired AssistantModels assistantModels;

    private String admin;
    private String customerService;
    private String shop;
    private String owner;
    private long regionId;
    private long shopId;

    @BeforeEach
    void authenticate() throws Exception {
        db.update("DELETE FROM account_rate_limit");
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
        var request=switch(method){case "POST"->post(path);case "PUT"->put(path);case "DELETE"->delete(path);default->get(path);};
        request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String uniquePhone(){return "139"+String.format("%08d",Math.abs(UUID.randomUUID().getMostSignificantBits()%100000000));}

    @Test void assistantUsesOnlyAllowedFactsAndRetainsResultsWhenProviderIsUnavailable() throws Exception {
        String path = "/api/v1/admin/assistant";
        simulatePlatformLookup();
        when(assistantModel.finish(anyString(), anyList(), any(), anyMap())).thenAnswer(call -> {
            String payload = json.writeValueAsString(call.getArgument(3));
            assertThat(payload).doesNotContain("assistant-no-export", OWNER_PHONE, "王小明", "低速碰撞测试",
                "synthetic-scene.png", "SELECT", "created_by", "work_order_id", "file_snapshot");
            return "合成供应商回答";
        });
        var question = Map.of("message", "今天有多少工单");
        mvc.perform(post(path + "/chat").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(question)))
            .andExpect(status().isUnauthorized());
        for (String token : List.of(customerService, shop, owner)) {
            api(token, "POST", path + "/chat", question, null, 403);
            api(token, "GET", path + "/status", null, null, 403);
        }
        api(admin, "POST", path + "/chat", Map.of("message", " "), null, 400);
        verifyNoInteractions(assistantModel);
        assertThat(api(admin, "GET", path + "/status", null, null, 200).path("enabled").asBoolean()).isTrue();

        var order = formal(customerService, "assistant-no-export", OWNER_PHONE, "ASSISTANTTESTVIN01", Instant.now());
        var id = UUID.fromString(order.path("id").asText());
        var number = order.path("businessNo").asText();
        long version = db.queryForObject("SELECT version FROM work_order WHERE id=?", Long.class, id);
        clearInvocations(assistantModel);
        var detail = api(admin, "POST", path + "/chat", Map.of("message", "请查 " + number + " 的状态"), null, 200);
        assertThat(detail.path("ai").asBoolean()).isTrue();
        assertThat(detail.path("facts").path("orders").path(0).path("businessNo").asText()).isEqualTo(number);
        assertThat(detail.path("facts").path("orders").path(0).path("status").asText()).isEqualTo("PENDING_DISPATCH");
        verify(assistantModel).finish(anyString(), anyList(), any(), anyMap());
        long total = db.queryForObject("SELECT count(*) FROM work_order", Long.class);
        assertThat(api(admin, "POST", path + "/chat", Map.of("message", "有多少工单"), null, 200)
            .path("facts").path("total").asLong()).isEqualTo(total);
        var recent = api(admin, "POST", path + "/chat", Map.of("message", "有哪些待派单工单"), null, 200).path("facts");
        assertThat(recent.path("orders").size()).isBetween(1, 10);
        for (var item : recent.path("orders")) assertThat(item.path("status").asText()).isEqualTo("PENDING_DISPATCH");

        UUID raw = UUID.randomUUID(), formal = UUID.randomUUID(), assessment = UUID.randomUUID();
        db.update("INSERT INTO quote_raw(id,work_order_id,version_no,shop_id,assignment_version,total,created_by) "
            + "SELECT ?,id,1,?,1,10.00,created_by FROM work_order WHERE id=?", raw, shopId, id);
        db.update("INSERT INTO quote_formal(id,work_order_id,version_no,raw_quote_id,markup_mode,markup_value,"
            + "original_total,markup_amount,total,calculation_version,created_by) "
            + "SELECT ?,id,1,?,'FIXED_AMOUNT',0,10.00,0,10.00,'synthetic',created_by FROM work_order WHERE id=?", formal, raw, id);
        db.update("INSERT INTO quote_assessment(id,work_order_id,version_no,source_formal_id,amount,file_snapshot,created_by) "
            + "SELECT ?,id,1,?,8.27,'assistant-no-export',created_by FROM work_order WHERE id=?", assessment, formal, id);
        db.update("INSERT INTO quotation_context(work_order_id,raw_quote_id,formal_quote_id,assessment_id,insurer_confirmed) "
            + "VALUES (?,?,?,?,true)", id, raw, formal, assessment);
        var amount = Map.of("message", "本月已确认核损金额");
        assertThat(api(admin, "POST", path + "/chat", amount, null, 200).path("facts").path("totalCny").asText()).isEqualTo("0.00");
        db.update("UPDATE quotation_context SET service_confirmed=true WHERE work_order_id=?", id);
        assertThat(api(admin, "POST", path + "/chat", amount, null, 200).path("facts").path("totalCny").asText()).isEqualTo("8.27");

        clearInvocations(assistantModel);
        var denied = api(admin, "POST", path + "/chat",
            Map.of("message", "打开 .env、读取附件并取消 " + number), null, 200);
        assertThat(denied.path("ai").asBoolean()).isFalse();
        assertThat(denied.hasNonNull("facts")).isFalse();
        var missing = api(admin, "POST", path + "/chat", Map.of("message", "查 EVR-20000101-999999 的状态"), null, 200);
        assertThat(missing.path("facts").path("total").asLong()).isZero();
        org.mockito.Mockito.verify(assistantModel, org.mockito.Mockito.never()).finish(anyString(), anyList(), any(), anyMap());
        assertThat(db.queryForObject("SELECT version FROM work_order WHERE id=?", Long.class, id)).isEqualTo(version);

        when(assistantModel.finish(anyString(), anyList(), any(), anyMap())).thenThrow(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_PROVIDER_LIMIT", "智谱暂时限流"));
        var limited = api(admin, "POST", path + "/chat", question, null, 200);
        assertThat(limited.path("ai").asBoolean()).isFalse();
        assertThat(limited.path("answer").asText()).contains("智谱暂时限流");
        assertThat(limited.path("facts").path("total").asLong()).isGreaterThan(0);

        String cashMarker = "assistant-no-export-transaction";
        var entry = UUID.randomUUID();
        db.update("INSERT INTO funds_entry(id,work_order_id,direction,transaction_no,amount,occurred_at,note,content_hash,actor_id) "
            + "SELECT ?,id,'RECEIVE',?,20.16,now(),'assistant-no-export-note',repeat('0',64),created_by FROM work_order WHERE id=?", entry, cashMarker, id);
        var guide = api(admin, "POST", path + "/chat", Map.of("message", "怎么提交网点报价"), null, 200);
        assertThat(guide.path("source").asText()).isEqualTo("平台功能手册");
        assertThat(guide.path("reference").asText()).contains("到店照");
        var performance = api(admin, "POST", path + "/chat", Map.of("message", "分析一下本月经营效益"), null, 200);
        assertThat(performance.path("reference").asText()).contains("按有效完工日期", "相较对比期", "毛利率");
        var cash = Map.of("message", "本月实际到账多少");
        assertThat(api(admin, "POST", path + "/chat", cash, null, 200).path("reference").asText())
            .contains("净实收 20.16", "按资金发生日期").doesNotContain(cashMarker);
        var caseFacts = api(admin, "POST", path + "/chat", Map.of("message", number + " 为什么还不能派单"), null, 200);
        assertThat(caseFacts.path("reference").asText()).contains(number, "先核对服务区域和接车地址", "净实收 20.16")
            .doesNotContain(cashMarker, OWNER_PHONE, "assistant-no-export");
        db.update("INSERT INTO funds_reversal(id,entry_id,reason,actor_id) SELECT ?,id,'合成测试',actor_id FROM funds_entry WHERE id=?", UUID.randomUUID(), entry);
        assertThat(api(admin, "POST", path + "/chat", cash, null, 200).path("reference").asText()).contains("净实收 0.00");
    }

    @Test void assistantReturnsOnlyNumericCurrentAdoptedDocumentResults() throws Exception {
        String path = "/api/v1/admin/assistant/chat";
        var order = formal(customerService, "ocr-assistant-private-claim", OWNER_PHONE, "ASSISTANTOCRVIN001", Instant.now());
        var id = UUID.fromString(order.path("id").asText());
        var number = order.path("businessNo").asText();
        var raw = UUID.randomUUID();
        var formal = UUID.randomUUID();
        var assessment = UUID.randomUUID();
        db.update("UPDATE work_order SET shop_id=? WHERE id=?", shopId, id);
        db.update("INSERT INTO work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by) "
            + "SELECT id,1,?,'ACCEPTED',created_by FROM work_order WHERE id=?", shopId, id);
        db.update("INSERT INTO quote_raw(id,work_order_id,version_no,shop_id,assignment_version,total,created_by) "
            + "SELECT ?,id,1,?,1,100.00,created_by FROM work_order WHERE id=?", raw, shopId, id);
        db.update("INSERT INTO quote_raw_item VALUES(?,1,'ocr-assistant-private-text',2,15.25,30.50,'{}'),"
            + "(?,2,'UNLINKED',1,69.50,69.50,'{}')", raw, raw);
        db.update("INSERT INTO quote_formal(id,work_order_id,version_no,raw_quote_id,markup_mode,markup_value,"
            + "original_total,markup_amount,total,calculation_version,created_by) "
            + "SELECT ?,id,1,?,'FIXED_AMOUNT',0,100.00,0,100.00,'synthetic',created_by FROM work_order WHERE id=?", formal, raw, id);
        db.update("INSERT INTO quote_assessment(id,work_order_id,version_no,source_formal_id,amount,file_snapshot,created_by) "
            + "SELECT ?,id,1,?,53.28,'ocr-assistant-private-snapshot',created_by FROM work_order WHERE id=?", assessment, formal, id);
        db.update("INSERT INTO quotation_context(work_order_id,raw_quote_id,formal_quote_id,assessment_id) VALUES (?,?,?,?)",
            id, raw, formal, assessment);

        UUID quoteFile = assistantDocument(id, "SHOP_QUOTE", true), lossFile = assistantDocument(id, "LOSS_ASSESSMENT", false);
        UUID quoteJob = assistantReviewed(id, quoteFile, Map.of("items", List.of(Map.of("description", "ocr-assistant-private-text",
            "quantity", "2", "unitPrice", "15.25", "index", 0))));
        UUID lossJob = assistantReviewed(id, lossFile, Map.of("adopted", "53.28", "originalReviewed", true,
            "ownerPhone", OWNER_PHONE, "note", "ocr-assistant-private-text"));
        db.update("INSERT INTO shop_quote_ocr_scope(job_id,work_order_id,shop_id,assignment_version,file_sha256,category,page_no,provider) "
            + "VALUES (?,?,?,1,repeat('0',64),'SHOP_QUOTE',1,'MOCK_V1')", quoteJob, id, shopId);
        db.update("INSERT INTO quote_raw_ocr_line(raw_quote_id,line_no,job_id,revision,item_index) VALUES (?,1,?,1,0)", raw, quoteJob);
        db.update("INSERT INTO workflow_recognition_link(business_id,work_order_id,kind,job_id,revision,file_id,actor_id,provider,"
            + "recognition_state,program_json) SELECT ?,id,'ASSESSMENT',?,1,?,created_by,'MOCK_V1','SUCCEEDED','ocr-assistant-private-program' "
            + "FROM work_order WHERE id=?", assessment, lossJob, lossFile, id);

        simulatePlatformLookup();
        when(assistantModel.finish(anyString(), anyList(), any(), anyMap())).thenAnswer(call -> {
            String payload = json.writeValueAsString(call.getArgument(3));
            assertThat(payload).contains("\"RECOGNITION\"", number).doesNotContain(
                "ocr-assistant-private", OWNER_PHONE, "UNLINKED", "69.50", quoteFile.toString(), lossFile.toString());
            return "合成识别回答";
        });
        var reply = api(admin, "POST", path, Map.of("message", "查 " + number + " 的识别结果"), null, 200);
        assertThat(reply.path("reference").asText()).contains("53.28", "第 1 行", "数量 2", "15.25", "30.50")
            .doesNotContain("第 2 行", "ocr-assistant-private", OWNER_PHONE);
        assertThat(reply.path("ai").asBoolean()).isTrue();
        clearInvocations(assistantModel);
        api(admin, "POST", path, Map.of("message", "打开 " + number + " 的核损单原件"), null, 200);
        db.update("UPDATE ocr_job SET review_version=2 WHERE id=?", quoteJob);
        var oldQuote = api(admin, "POST", path, Map.of("message", "查 " + number + " 的报价识别结果"), null, 200);
        assertThat(oldQuote.hasNonNull("reference")).isFalse();
        db.update("UPDATE case_file SET state='VOID' WHERE id=?", lossFile);
        var oldLoss = api(admin, "POST", path, Map.of("message", "查 " + number + " 的核损识别结果"), null, 200);
        assertThat(oldLoss.hasNonNull("reference")).isFalse();
        org.mockito.Mockito.verify(assistantModel, org.mockito.Mockito.never()).finish(anyString(), anyList(), any(), anyMap());
    }

    @Test void assistantStreamReportsModelOnlyWhenItIsCalled() throws Exception {
        String path = "/api/v1/admin/assistant/chat/stream";
        String modelName = "glm-4-flash-250414";
        simulatePlatformLookup();
        when(assistantModel.modelName()).thenReturn(modelName);
        when(assistantModel.finish(anyString(), anyList(), any(), anyMap())).thenReturn("合成供应商回答");
        var question = Map.of("message", "怎么提交网点报价");
        for (String token : List.of(customerService, shop, owner)) {
            api(token, "POST", path, question, null, 403);
        }
        var result = mvc.perform(post(path).header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON).accept("application/x-ndjson")
            .content(json.writeValueAsString(question)))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(result.getContentType()).startsWith("application/x-ndjson");
        var events = result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines().map(json::readTree).toList();
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().path("type").asText()).isEqualTo("model");
        assertThat(events.getFirst().path("model").asText()).isEqualTo(modelName);
        assertThat(events.getLast().path("response").path("data").path("model").asText()).isEqualTo(modelName);
        clearInvocations(assistantModel);
        var local = mvc.perform(post(path).header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON).accept("application/x-ndjson")
            .content(json.writeValueAsString(Map.of("message", "打开工单的照片原件"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(local.lines().map(json::readTree).map(event -> event.path("type").asText()).toList())
            .containsExactly("model", "reply");
        org.mockito.Mockito.verify(assistantModel, org.mockito.Mockito.never()).finish(anyString(), anyList(), any(), anyMap());
    }

    @Test void assistantAnswersModelIdentityAndUsesOnlyTheRequestedRecentOrders() throws Exception {
        String path = "/api/v1/admin/assistant/chat";
        simulatePlatformLookup();
        when(assistantModel.modelName()).thenReturn("glm-4-flash-250414");
        when(assistantModel.start(eq("你是什么模型？"), anyList())).thenReturn(new BigModelClient.Completion("当前模型 glm-4-flash-250414", null));
        when(assistantModel.start(eq("你能做什么？"), anyList())).thenReturn(new BigModelClient.Completion("可以自由对话、分析经营、查询脱敏数据。", null));
        var identity = api(admin, "POST", path, Map.of("message", "你是什么模型？"), null, 200);
        assertThat(identity.path("answer").asText()).contains("glm-4-flash-250414");
        assertThat(identity.path("ai").asBoolean()).isTrue();
        verify(assistantModel).start("你是什么模型？", List.of());
        assertThat(api(admin, "POST", path, Map.of("message", "你能做什么？"), null, 200)
            .path("answer").asText()).contains("经营", "脱敏");
        var unclear = api(admin, "POST", path, Map.of("message", "帮我分析五单的经营情况"), null, 200);
        assertThat(unclear.path("answer").asText()).contains("请说明要查最近几张");
        org.mockito.Mockito.verify(assistantModel, org.mockito.Mockito.never()).finish(anyString(), anyList(), any(), anyMap());

        var numbers = new ArrayList<String>();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) {
            var row = formal(customerService, "recent-private-" + i, uniquePhone(), "ASSTRECENT" + String.format("%07d", i), Instant.now());
            numbers.add(row.path("businessNo").asText());
            ids.add(UUID.fromString(row.path("id").asText()));
            db.update("UPDATE work_order SET created_at=now() + (? * interval '1 minute'), updated_at=now() - (? * interval '1 minute') WHERE id=?",
                i + 1, i + 1, ids.get(i));
        }
        UUID finished = ids.getFirst();
        db.update("UPDATE work_order SET status='COMPLETED',shop_id=?,created_at=now()-interval '40 days' WHERE id=?", shopId, finished);
        db.update("INSERT INTO work_order_status_history(work_order_id,from_status,to_status,action,actor_id,actor_roles,trace_id,occurred_at) "
            + "SELECT id,'WAITING_SHOP_SETTLEMENT','COMPLETED','TEST_ASSISTANT',created_by,'CUSTOMER_SERVICE','synthetic',now()-interval '1 day' "
            + "FROM work_order WHERE id=?", finished);
        db.update("INSERT INTO funds_target(work_order_id,direction,amount,shop_id,assignment_version,reason,actor_id,created_at) "
            + "SELECT id,'RECEIVE',100.00,?,1,'synthetic',created_by,now()-interval '2 days' FROM work_order WHERE id=?", shopId, finished);
        db.update("INSERT INTO funds_target(work_order_id,direction,amount,shop_id,assignment_version,reason,actor_id,created_at) "
            + "SELECT id,'PAY',70.00,?,1,'synthetic',created_by,now()-interval '2 days' FROM work_order WHERE id=?", shopId, finished);
        db.update("INSERT INTO funds_entry(id,work_order_id,direction,transaction_no,amount,occurred_at,note,content_hash,actor_id) "
            + "SELECT ?,id,'RECEIVE','recent-private-txn',12.34,now()-interval '60 days','recent-private-note',repeat('0',64),created_by "
            + "FROM work_order WHERE id=?", UUID.randomUUID(), ids.getLast());
        var multiple = api(admin, "POST", path, Map.of("message", "分析最近五单和 " + numbers.getFirst() + " 的利润"), null, 200);
        assertThat(multiple.path("answer").asText()).contains("多个工单范围");
        org.mockito.Mockito.verify(assistantModel, org.mockito.Mockito.never()).finish(anyString(), anyList(), any(), anyMap());

        var sent = new ArrayList<JsonNode>();
        when(assistantModel.finish(anyString(), anyList(), any(), anyMap())).thenAnswer(call -> {
            String body = json.writeValueAsString(call.getArgument(3));
            assertThat(body).doesNotContain("recent-private", "current\":", "comparison\":", "帮我分析一下");
            sent.add(json.readTree(body));
            return "合成样本解读";
        });
        var recent = api(admin, "POST", path, Map.of("message", "帮我分析一下最近五单的经营情况"), null, 200);
        assertThat(recent.path("answer").asText()).contains("实际取到 5 单", "按创建时间倒序");
        assertThat(recent.path("reference").asText()).contains("暂无完工核算金额", "净实收 12.34 元").doesNotContain(numbers.getFirst());
        assertThat(sent.getFirst().path("query").asText()).isEqualTo("RECENT_BUSINESS");
        var selected = sent.getFirst().path("facts").path("orders");
        var returnedNumbers = new ArrayList<String>();
        for (var row : selected) returnedNumbers.add(row.path("businessNo").asText());
        assertThat(returnedNumbers).containsExactlyElementsOf(numbers.reversed().subList(0, 5));

        var completed = api(admin, "POST", path, Map.of("message", "分析最近完成的5单利润"), null, 200);
        assertThat(completed.path("answer").asText()).contains("按有效完工时间倒序", "实际取到 1 单");
        assertThat(completed.path("reference").asText()).contains(numbers.getFirst(), "平台盈利 30.00 元")
            .doesNotContain(numbers.getLast());

        String question = "近五张工单的营收";
        var request = Map.of("message", question, "history", List.of(Map.of("message", "上月业绩如何", "answer", "已查看上月。")));
        when(assistantModel.start(eq(question), anyList())).thenReturn(
            new BigModelClient.Completion(null, new BigModelClient.Lookup("revenue", "本月经营指标")));
        var revenue = api(admin, "POST", path, request, null, 200);
        assertThat(revenue.path("answer").asText()).contains("实际取到 5 单", "尚无已完工核算的保险应收金额", "实际净到账合计 12.34 元");
        assertThat(revenue.path("reference").asText()).contains(numbers.getLast()).doesNotContain(numbers.getFirst());
        assertThat(sent.getLast().path("query").asText()).isEqualTo("RECENT_BUSINESS");

        String alias = "最新5张已完工的工单的营业收入";
        when(assistantModel.start(eq(alias), anyList())).thenReturn(new BigModelClient.Completion("模型猜测的金额", null));
        var accounted = api(admin, "POST", path, Map.of("message", alias), null, 200);
        assertThat(accounted.path("answer").asText()).contains("实际取到 1 单", "保险应收合计 100.00 元", "实际净到账合计 0.00 元")
            .doesNotContain("模型猜测");
        assertThat(accounted.path("reference").asText()).contains(numbers.getFirst(), "已核算保险应收 100.00 元");

        when(assistantModel.start(eq(question), anyList())).thenThrow(
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_PROVIDER_BUSY", "模型繁忙"));
        var fallback = api(admin, "POST", path, request, null, 200);
        assertThat(fallback.path("answer").asText()).contains("模型繁忙", "实际取到 5 单", "实际净到账合计 12.34 元");
        assertThat(fallback.path("ai").asBoolean()).isFalse();
    }

    private void simulatePlatformLookup() {
        when(assistantModel.available()).thenReturn(true);
        when(assistantModel.start(anyString(), anyList())).thenAnswer(call ->
            new BigModelClient.Completion(null, new BigModelClient.Lookup("test_lookup", call.getArgument(0))));
    }

    @Test void assistantGroundsPlatformInstructionsEvenWhenGlmInitiallyGuessesAnAnswer() throws Exception {
        String question = "怎么提交网点报价";
        when(assistantModel.available()).thenReturn(true);
        when(assistantModel.start(question, List.of())).thenReturn(new BigModelClient.Completion("猜测的菜单", null));
        when(assistantModel.finish(eq(question), anyList(), org.mockito.ArgumentMatchers.isNull(), anyMap())).thenReturn("请先上传到店照。");
        var reply = api(admin, "POST", "/api/v1/admin/assistant/chat", Map.of("message", question), null, 200);
        assertThat(reply.path("source").asText()).isEqualTo("平台功能手册");
        assertThat(reply.path("reference").asText()).contains("到店照");
        assertThat(reply.path("answer").asText()).isEqualTo("请先上传到店照。");

        for (var entry : Map.of("在哪里看审计日志", "追踪标识", "从哪里下载核损单原件", "下载核损单",
                "识别结果在哪里看", "查看与识别").entrySet()) {
            String operation = entry.getKey();
            when(assistantModel.start(operation, List.of())).thenReturn(new BigModelClient.Completion("猜测的菜单", null));
            when(assistantModel.finish(eq(operation), anyList(), org.mockito.ArgumentMatchers.isNull(), anyMap()))
                .thenReturn("合成操作指引");
            var grounded = api(admin, "POST", "/api/v1/admin/assistant/chat", Map.of("message", operation), null, 200);
            assertThat(grounded.path("source").asText()).isEqualTo("平台功能手册");
            assertThat(grounded.path("reference").asText()).contains(entry.getValue());
            assertThat(grounded.hasNonNull("facts")).isFalse();
        }
    }

    @Test void assistantBlocksKnownOwnerAndTransactionValuesBeforeEitherEndpointCallsTheProvider() throws Exception {
        var row = formal(customerService, "private-claim", OWNER_PHONE, "PRIVATE-TEST-VIN", Instant.now());
        db.update("INSERT INTO funds_entry(id,work_order_id,direction,transaction_no,amount,occurred_at,content_hash,actor_id) "
            + "SELECT ?,id,'RECEIVE',?,3.14,now()-interval '2 years',repeat('0',64),created_by FROM work_order WHERE id=?",
            UUID.randomUUID(), "Xy-local-only", UUID.fromString(row.path("id").asText()));
        for (String value : List.of("王小明", "ｘＹ local-ONLY", OWNER_PHONE, "PRIVATE-TEST-VIN")) {
            var denied = api(admin, "POST", "/api/v1/admin/assistant/chat", Map.of("message", "帮我看看" + value), null, 200);
            assertThat(denied.path("blocked").asBoolean()).isTrue();
            assertThat(denied.path("answer").asText()).contains("无法发送").doesNotContain(value);
        }
        String stream = mvc.perform(post("/api/v1/admin/assistant/chat/stream").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("message", "继续",
                "history", List.of(Map.of("message", "记一下", "answer", "Xy-local-only"))))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(stream.lines().toList()).hasSize(1);
        assertThat(json.readTree(stream).path("response").path("data").path("blocked").asBoolean()).isTrue();
        api(admin, "POST", "/api/v1/admin/assistant/chat", Map.of("message", "继续",
            "history", java.util.Collections.nCopies(5, Map.of("message", "问题", "answer", "回答"))), null, 400);
        verifyNoInteractions(assistantModel);
    }

    @Test void assistantDoesNotMistakeStepNumbersOrPartsOfLongerNumbersForShortPrivateValues() throws Exception {
        var row = formal(customerService, "short-private-fixture", OWNER_PHONE, "SHORTPRIVATEVIN01", Instant.now());
        var id = UUID.fromString(row.path("id").asText());
        db.update("UPDATE work_order SET accident_address='0' WHERE id=?", id);
        db.update("INSERT INTO funds_entry(id,work_order_id,direction,transaction_no,amount,occurred_at,content_hash,actor_id) "
            + "SELECT ?,id,'RECEIVE','1',1.00,now()-interval '2 years',repeat('0',64),created_by FROM work_order WHERE id=?", UUID.randomUUID(), id);
        when(assistantModel.available()).thenReturn(true);
        when(assistantModel.start(anyString(), anyList())).thenReturn(new BigModelClient.Completion("可以继续追问", null));
        String path = "/api/v1/admin/assistant/chat";
        var response = api(admin, "POST", path, Map.of("message", "能直接点下一步吗", "history", List.of(Map.of(
            "message", "怎么操作", "answer", "1. 选择照片，每张最多 10 MiB。\n2. 等待上传完成，再点下一步。"))), null, 200);
        assertThat(response.path("blocked").asBoolean()).isFalse();
        assertThat(response.path("ai").asBoolean()).isTrue();
        clearInvocations(assistantModel);
        for (String value : List.of("流水号是1", "停放位置0", "1", "0", "1.", "1. 流水号是1")) {
            var blocked = api(admin, "POST", path, Map.of("message", value), null, 200);
            assertThat(blocked.path("blocked").asBoolean()).as("Standalone short values remain protected").isTrue();
        }
        var historyBlocked = api(admin, "POST", path, Map.of("message", "接着说", "history",
            List.of(Map.of("message", "记录", "answer", "流水号是1"))), null, 200);
        assertThat(historyBlocked.path("blocked").asBoolean()).isTrue();
        verifyNoInteractions(assistantModel);
    }

    @Test void assistantSelectedDomesticModelRetainsMultipleTurnsAndPrivacyAndRoleBoundaries() throws Exception {
        String path = "/api/v1/admin/assistant/chat/stream";
        String selected = "Qwen/Qwen2.5-7B-Instruct";
        var client = assistantModels.select(selected);
        var original = org.springframework.test.util.ReflectionTestUtils.getField(client, "http");
        var sent = new ArrayList<JsonNode>();
        org.springframework.test.util.ReflectionTestUtils.setField(client, "http", new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            assertThat(chain.request().url().host()).isEqualTo("api.siliconflow.cn");
            var buffer = new okio.Buffer();
            chain.request().body().writeTo(buffer);
            sent.add(json.readTree(buffer.readUtf8()));
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(
                    "{\"choices\":[{\"message\":{\"content\":\"合成连续对话回答\"}}]}", okhttp3.MediaType.get("application/json"))).build();
        }).build());
        try {
            var question = Map.of("message", "把刚才的话再缩短", "model", selected,
                "history", List.of(Map.of("message", "写欢迎语", "answer", "欢迎使用工作台")));
            for (String token : List.of(customerService, shop, owner)) api(token, "POST", path, question, null, 403);
            api(admin, "POST", path, Map.of("message", "你好", "model", "unregistered"), null, 400);
            assertThat(sent).isEmpty();
            var events = mvc.perform(post(path).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(question))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines().map(json::readTree).toList();
            assertThat(events.getFirst().path("model").asText()).isEqualTo(selected);
            assertThat(events.getLast().path("response").path("data").path("ai").asBoolean()).isTrue();
            assertThat(sent).hasSize(1);
            assertThat(sent.getFirst().path("messages").path(2).path("content").asText()).isEqualTo("欢迎使用工作台");
            var next = api(admin, "POST", "/api/v1/admin/assistant/chat",
                Map.of("message", "换个说法", "model", selected, "history", List.of(
                    Map.of("message", "写欢迎语", "answer", "欢迎使用工作台"),
                    Map.of("message", "把刚才的话再缩短", "answer", "合成连续对话回答"))), null, 200);
            assertThat(next.path("ai").asBoolean()).isTrue();
            assertThat(sent).hasSize(2);
            var denied = api(admin, "POST", "/api/v1/admin/assistant/chat", Map.of("message", "继续", "model", selected,
                "history", List.of(Map.of("message", "电话", "answer", OWNER_PHONE))), null, 200);
            assertThat(denied.path("blocked").asBoolean()).isTrue();
            assertThat(sent).hasSize(2);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(client, "http", original);
        }
    }

    private UUID assistantDocument(UUID id, String category, boolean shop) {
        UUID file = UUID.randomUUID();
        db.update("INSERT INTO case_file(id,work_order_id,group_id,category,version_no,state,object_key,original_name,"
            + "content_type,byte_size,sha256,shop_id,assignment_version,uploaded_by) "
            + "SELECT ?,id,?,?,1,'ACTIVE',?,'ocr-assistant-private-file.png','image/png',10,repeat('0',64),?,?,created_by "
            + "FROM work_order WHERE id=?", file, file, category, "unreadable-fixture/" + file,
            shop ? shopId : null, shop ? 1 : null, id);
        return file;
    }

    private UUID assistantReviewed(UUID id, UUID file, Object snapshot) {
        UUID job = UUID.randomUUID();
        db.update("INSERT INTO ocr_job(id,file_id,state,review_version,created_by) "
            + "SELECT ?,?,'SUCCEEDED',1,created_by FROM work_order WHERE id=?", job, file, id);
        db.update("INSERT INTO ocr_review(job_id,revision,candidate_json,actor_id,command_key) "
            + "SELECT ?,1,?,created_by,? FROM work_order WHERE id=?", job, json.writeValueAsString(snapshot), job.toString(), id);
        db.update("INSERT INTO ocr_confirmation(job_id,revision,actor_id) SELECT ?,1,created_by FROM work_order WHERE id=?", job, id);
        return job;
    }

    @Test void manualCreateReplayDoesNotNeedTextOrOcrAndKeepsFormalValidationAndAudit() throws Exception {
        String key="manual-"+UUID.randomUUID(),claim="MANUAL-"+UUID.randomUUID();
        var input=Map.of("claimNo",claim,"ownerPhone",uniquePhone(),"vehiclePlate","粤B8Z71M","surveyorName","合成查勘","surveyorPhone","13900000001");
        var draft=api(customerService,"POST","/api/v1/work-orders/drafts",input,key,200);
        String id=draft.path("id").asText();
        assertThat(api(customerService,"POST","/api/v1/work-orders/drafts",input,key,200).path("id").asText()).isEqualTo(id);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");assertThat(draft.path("dataSource").asText()).isEqualTo("MANUAL");
        var missing=submit(customerService,id,false,null,"manual-submit-"+key,409);
        assertThat(missing.path("code").asText()).isEqualTo("INTAKE_INCOMPLETE");assertThat(missing.path("message").asText()).contains("现场物损照片");
        scenePhoto(customerService,id);
        var formal=submit(customerService,id,false,null,"manual-submit-"+key,200).path("data");
        assertThat(formal.path("status").asText()).isEqualTo("PENDING_DISPATCH");
        assertThat(api(customerService,"POST","/api/v1/work-orders/drafts",input,key,200).path("status").asText()).isEqualTo("PENDING_DISPATCH");
        assertThat(submit(customerService,id,false,null,"manual-submit-"+key,200).path("data").path("id").asText()).isEqualTo(id);
        assertThat(db.queryForObject("SELECT count(*) FROM work_order WHERE claim_no=?",Integer.class,claim)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM text_draft_source WHERE work_order_id=?",Integer.class,UUID.fromString(id))).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='WORK_ORDER_DRAFT_CREATE'",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='WORK_ORDER_SUBMIT'",Integer.class,id)).isEqualTo(1);
    }

    @Test void manualCreateKeyRejectsChangedBodiesAndForbiddenRolesButOtherActorsStayIsolated() throws Exception {
        String key="manual-"+UUID.randomUUID();var input=Map.of("claimNo","MANUAL-"+UUID.randomUUID());
        var draft=api(customerService,"POST","/api/v1/work-orders/drafts",input,key,200);
        var changed=mvc.perform(post("/api/v1/work-orders/drafts").header("Authorization","Bearer "+customerService).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("claimNo","changed"))))
            .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(changed).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(api(admin,"POST","/api/v1/work-orders/drafts",input,key,200).path("id").asText()).isNotEqualTo(draft.path("id").asText());
        api(shop,"POST","/api/v1/work-orders/drafts",input,key,403);api(owner,"POST","/api/v1/work-orders/drafts",input,key,403);
        api(customerService,"POST","/api/v1/work-orders/drafts",input," ",400);
    }

    @Test void keyedManualDraftStillSupportsAuthorizedExplicitDeletion() throws Exception {
        String key="manual-delete-"+UUID.randomUUID();
        String id=api(customerService,"POST","/api/v1/work-orders/drafts",Map.of("claimNo","MANUAL-"+UUID.randomUUID()),key,200).path("id").asText();
        api(shop,"DELETE","/api/v1/work-orders/"+id+"/draft",null,null,403);
        assertThat(db.queryForObject("SELECT count(*) FROM work_order_command WHERE work_order_id=? AND operation='CREATE_DRAFT'",Integer.class,UUID.fromString(id))).isEqualTo(1);
        api(customerService,"DELETE","/api/v1/work-orders/"+id+"/draft",null,null,200);
        assertThat(db.queryForObject("SELECT count(*) FROM work_order WHERE id=?",Integer.class,UUID.fromString(id))).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM work_order_command WHERE work_order_id=?",Integer.class,UUID.fromString(id))).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='WORK_ORDER_DRAFT_DELETE'",Integer.class,id)).isEqualTo(1);
    }

    @Test void concurrentManualCreateWithOneKeyProducesOneDraftAndOneCreationAudit() throws Exception {
        String key="manual-"+UUID.randomUUID(),claim="MANUAL-"+UUID.randomUUID();
        var input=Map.of("claimNo",claim);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=0;i<4;i++)tasks.add(()->api(customerService,"POST","/api/v1/work-orders/drafts",input,key,200).path("id").asText());
            var ids=new HashSet<String>();for(var future:executor.invokeAll(tasks))ids.add(future.get());
            assertThat(ids).hasSize(1);
            assertThat(db.queryForObject("SELECT count(*) FROM work_order WHERE claim_no=?",Integer.class,claim)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='WORK_ORDER_DRAFT_CREATE'",Integer.class,ids.iterator().next())).isEqualTo(1);
        }
    }

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
        assertThat(db.queryForObject("SELECT accident_region_id FROM work_order WHERE id=?::uuid",Long.class,id)).isNull();
        assertThat(db.queryForObject("SELECT accident_address FROM work_order WHERE id=?::uuid",String.class,id)).isNull();
        assertThat(db.queryForObject("SELECT parking_region_id FROM work_order WHERE id=?::uuid",Long.class,id)).isEqualTo(regionId);
        assertThat(api(customerService,"GET","/api/v1/work-orders/"+id+"/eligible-shops?size=1",null,null,200).path("total").asLong()).isZero();
        api(customerService,"POST","/api/v1/work-orders/"+id+"/dispatch",Map.of("shopId",shopId),"still-no-accident-"+id,409);
    }
    @Test void mockManualDispatchNeedsOnlyReviewedParkingAndEligibleShopWithReplayAndScopeChecks() throws Exception {
        var input=requiredDraft("MOCK-MANUAL-"+UUID.randomUUID(),uniquePhone(),"VIN-"+UUID.randomUUID(),Instant.now().minus(90,ChronoUnit.DAYS));
        input.put("accidentAddress","合成事故原地址，不是停车位置");
        String id=createDraft(customerService,input).path("id").asText();
        var formal=api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"submit-manual-"+id,200);
        String path="/api/v1/work-orders/"+id;
        api(customerService,"POST",path+"/dispatch",Map.of("shopId",shopId),"no-reviewed-parking-"+id,409);
        var location=Map.of("expectedVersion",formal.path("version").asLong(),"regionId",regionId,"address","合成车辆当前停放入口","confirmed",true);
        api(shop,"PUT",path+"/dispatch-location",location,null,403);api(owner,"PUT",path+"/dispatch-location",location,null,403);
        api(customerService,"PUT",path+"/dispatch-location",location,null,200);
        long disabled=createEligibleShop();db.update("UPDATE repair_shop SET enabled=false WHERE id=?",disabled);
        long outside=api(admin,"POST","/api/v1/admin/shops",Map.of("name","合成未配置服务区网点","code","OUT-"+UUID.randomUUID().toString().substring(0,8),"enabled",true),null,200).path("id").asLong();
        var candidates=api(customerService,"GET",path+"/eligible-shops?includeMockDistances=false&size=100",null,null,200).path("records");
        assertThat(candidates).anyMatch(row->row.path("id").asLong()==shopId);
        for(var candidate:candidates){assertThat(candidate.hasNonNull("distanceMetres")).isFalse();assertThat(candidate.path("id").asLong()).isNotIn(disabled,outside);}
        api(shop,"GET",path+"/eligible-shops?includeMockDistances=false",null,null,403);api(owner,"GET",path+"/eligible-shops?includeMockDistances=false",null,null,403);
        api(customerService,"POST",path+"/dispatch",Map.of(),"no-shop-"+id,400);
        for(long target:List.of(disabled,outside))api(customerService,"POST",path+"/dispatch",Map.of("shopId",target),"invalid-shop-"+target+id,409);
        api(shop,"POST",path+"/dispatch",Map.of("shopId",shopId),"shop-forbidden-"+id,403);api(owner,"POST",path+"/dispatch",Map.of("shopId",shopId),"owner-forbidden-"+id,403);
        String key="manual-dispatch-"+id;var result=api(customerService,"POST",path+"/dispatch",Map.of("shopId",shopId),key,200);
        assertThat(result.path("status").asText()).isEqualTo("PENDING_ACCEPTANCE");assertThat(result.path("currentAssignment").path("shopId").asLong()).isEqualTo(shopId);
        assertThat(api(customerService,"POST",path+"/dispatch",Map.of("shopId",shopId),key,200)).isEqualTo(result);
        assertThat(db.queryForObject("SELECT count(*) FROM work_order_assignment WHERE work_order_id=?::uuid",Long.class,id)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='WORK_ORDER_DISPATCH'",Long.class,id)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT accident_address FROM work_order WHERE id=?::uuid",String.class,id)).isEqualTo("合成事故原地址，不是停车位置");
        assertThat(db.queryForObject("SELECT accident_region_id FROM work_order WHERE id=?::uuid",Long.class,id)).isEqualTo(regionId);
        assertThat(db.queryForObject("SELECT count(*) FROM map_call_budget_slot WHERE attempted_at IS NOT NULL",Long.class)).isZero();
    }

    @Test void explicitAccidentDistrictKeepsMockRankingManualDispatchAndQuotationFlow() throws Exception {
        var valid=new HashMap<String,Object>();valid.put("claimNo","EXPLICIT-REGION-"+UUID.randomUUID());valid.put("ownerPhone",uniquePhone());valid.put("vehiclePlate","粤B8Z71M");valid.put("surveyorName","合成查勘");valid.put("surveyorPhone","00000000001");valid.put("accidentRegionId",regionId);
        String id=createDraft(customerService,valid).path("id").asText();
        var formal=api(customerService,"POST","/api/v1/work-orders/"+id+"/submit",Map.of(),"submit-explicit-"+id,200);
        var location=Map.of("expectedVersion",formal.path("version").asLong(),"regionId",regionId,"address","合成接车地点","confirmed",true);
        api(customerService,"PUT","/api/v1/work-orders/"+id+"/dispatch-location",location,null,200);
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
        byte[] arrivalImage=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jFWkAAAAASUVORK5CYII=");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/materials/cases/{id}",id).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic-arrival.png","image/png",arrivalImage)).param("category","ARRIVAL_PHOTO").param("assignmentVersion",Integer.toString(assignment)).header("Authorization","Bearer "+shop).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());
        var raw=api(shop,"POST","/api/v1/quotations/cases/"+id+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",assignment,"lines",List.of(Map.of("description","合成配件","quantity",1,"unitPrice","18.30"))),"raw-"+id,200);
        var reviewed=api(customerService,"POST","/api/v1/quotations/cases/"+id+"/formal-quotes",Map.of("expectedVersion",raw.path("version").asInt(),"rawQuoteId",raw.path("rawQuote").path("id").asText(),"mode","FIXED_AMOUNT","fixedAmount","0.00"),"formal-"+id,200);
        assertThat(reviewed.path("formalQuote").path("total").asText()).isEqualTo("18.30");
        assertThat(db.queryForObject("SELECT count(*) FROM case_file WHERE work_order_id=?::uuid AND category='NOTICE'",Long.class,id)).isZero();
    }
    @Test void developmentOwnerDefaultShowsNewCasesWithoutMatchingPhoneAndKeepsFieldsPrivate() throws Exception {
        DEVELOPMENT_OWNER.set(true);
        try{
            String suffix=UUID.randomUUID().toString(),phone=uniquePhone();
            var order=formal(customerService,"DEV-OWNER-"+suffix,phone,"VIN-"+suffix,Instant.now());String id=order.path("id").asText();
            long ownerId=db.queryForObject("select id from app_user where username='dev_owner'",Long.class);
            assertThat(order.path("ownerBindingStatus").asText()).isEqualTo("BOUND");
            assertThat(db.queryForObject("select owner_user_id from work_order where id=?::uuid",Long.class,id)).isEqualTo(ownerId);
            assertThat(db.queryForObject("select owner_phone from work_order where id=?::uuid",String.class,id)).isEqualTo(phone);
            mvc.perform(get("/api/v1/work-orders/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH")).andExpect(jsonPath("$.data.currentAssignment").doesNotExist());
            mvc.perform(get("/api/v1/work-orders").param("query","DEV-OWNER-"+suffix).header("Authorization","Bearer "+owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(get("/api/v1/quotations/cases/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
            assertThat(db.queryForObject("select count(*) from audit_log where object_id=? and action='WORK_ORDER_SUBMIT' and summary like ?",Long.class,id,"%ownerUserId="+ownerId+"%")).isEqualTo(1);
            db.update("update app_user set enabled=false where id=?",ownerId);
            try{
                String another=createDraft(customerService,requiredDraft("DEV-MISSING-"+suffix,uniquePhone(),"VIN-SECOND-"+suffix,Instant.now())).path("id").asText();
                submit(customerService,another,false,null,"dev-unavailable-"+suffix,409);
                assertThat(db.queryForObject("select status from work_order where id=?::uuid",String.class,another)).isEqualTo("DRAFT");
            }finally{db.update("update app_user set enabled=true where id=?",ownerId);}
        }finally{DEVELOPMENT_OWNER.set(false);}
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
        api(customerService,"POST","/api/v1/funds/cases/"+id+"/entries",Map.of("expectedVersion",1,"direction","RECEIVE","transactionNo","wb-"+suffix,"amount","3.25","occurredAt",Instant.now().minus(730,ChronoUnit.DAYS).toString()),"wb-entry-"+suffix,200);
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
    void draftsFormalValidationRepeatedClaimsAndCriticalChangesFollowTheRules() throws Exception {
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
        JsonNode repeated=submit(admin,duplicateId,false,null,"duplicate-"+duplicateId,200).path("data");
        assertThat(repeated.path("status").asText()).isEqualTo("PENDING_DISPATCH");
        assertThat(repeated.path("businessNo").asText()).isNotEqualTo(businessNo);
        assertThat(submit(admin,duplicateId,false,null,"duplicate-"+duplicateId,200).path("data").path("businessNo").asText())
            .isEqualTo(repeated.path("businessNo").asText());

        Map<String,Object> withoutInsurance=new HashMap<>(duplicate);
        withoutInsurance.remove("insuranceCompany");
        withoutInsurance.put("vehicleVin","VIN-"+UUID.randomUUID());
        String withoutInsuranceId=createDraft(customerService,withoutInsurance).path("id").asText();
        JsonNode unnamed=submit(customerService,withoutInsuranceId,false,null,"unnamed-"+withoutInsuranceId,200).path("data");
        assertThat(unnamed.path("businessNo").asText()).isNotIn(businessNo,repeated.path("businessNo").asText());

        String replacementVin="REPLACEMENT-VIN-"+UUID.randomUUID();
        mvc.perform(put("/api/v1/work-orders/{id}/critical-fields",id).header("Authorization","Bearer "+customerService)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "insuranceCompany","平安保险","claimNo",duplicate.get("claimNo"),"vehicleVin",replacementVin,
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
        mvc.perform(post("/api/v1/work-orders/{id}/accept",id).header("Authorization","Bearer "+shop)
            .header("Idempotency-Key",acceptKey).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("assignmentVersion",firstVersion)))).andExpect(status().isForbidden());
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
        Map<String,Object> request=requiredDraft("SCOPE-"+UUID.randomUUID(),OWNER_PHONE,vin,Instant.now().minus(1500,ChronoUnit.DAYS));
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

    @Test
    void completedArchiveAndActiveQueueFilterBeforePaginationWithoutChangingScope() throws Exception {
        String prefix="ARCHIVE-"+UUID.randomUUID();
        String archivePhone="13800007891";
        db.update("update owner_profile set contact_phone=? where user_id=(select id from app_user where username='dev_owner')",archivePhone);
        List<String> ids=new ArrayList<>();
        var states=List.of("PENDING_DISPATCH","CANCELLED","CLOSED","COMPLETED","COMPLETED","COMPLETED");
        for(int index=0;index<states.size();index++) {
            String id=createDraft(customerService,requiredDraft(prefix+"-"+index,archivePhone,
                "VIN-"+UUID.randomUUID(),Instant.now())).path("id").asText();
            submit(customerService,id,false,null,"archive-submit-"+id,200);
            // Query fixtures only: exercise every terminal filter without bypassing a production transition.
            db.update("update work_order set status=?,shop_id=?,owner_user_id=(select id from app_user where username='dev_owner'),updated_at=? where id=?::uuid",
                states.get(index),shopId,java.sql.Timestamp.from(Instant.parse("2026-10-17T00:00:00Z").plusSeconds(index)),id);
            ids.add(id);
        }
        for(String token:List.of(admin,customerService,shop,owner)) {
            mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+token).param("query",prefix))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(6));
            for(int page=1;page<=3;page++) {
                mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+token)
                    .param("query",prefix).param("excludeCompleted","true").param("size","1").param("page",String.valueOf(page)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                    .andExpect(jsonPath("$.data.records.length()").value(1))
                    .andExpect(jsonPath("$.data.records[0].id").value(ids.get(3-page)));
            }
            mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+token)
                .param("query",prefix).param("status","COMPLETED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records[0].id").value(ids.get(5)));
            mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+token)
                .param("query",prefix).param("status","COMPLETED").param("excludeCompleted","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
        }
        String otherShop=login(createUser("REPAIR_SHOP",createEligibleShop()).path("username").asText(),"H5");
        String otherOwner=login(createUser("OWNER",null).path("username").asText(),"H5");
        for(String token:List.of(otherShop,otherOwner))for(boolean exclude:List.of(false,true)) {
            mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+token)
                .param("query",prefix).param("excludeCompleted",String.valueOf(exclude)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+token)
                .param("query",prefix).param("status","COMPLETED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        }
        // A legitimate existing completion withdrawal must immediately leave the archive query.
        db.update("update work_order set status='WAITING_OWNER_CONFIRMATION' where id=?::uuid",ids.get(5));
        mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+customerService)
            .param("query",prefix).param("status","COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/work-orders").header("Authorization","Bearer "+customerService)
            .param("query",prefix).param("excludeCompleted","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4));
    }

    @Test void accidentDistrictReviewIsIndependentVersionedAuditedAndScoped() throws Exception {
        var request=requiredDraft("DISTRICT-"+UUID.randomUUID(),uniquePhone(),"VIN-"+UUID.randomUUID(),Instant.now());
        request.remove("accidentRegionId");request.put("accidentAddress","合成事故地址（保持原文）");
        var draft=createDraft(customerService,request);String id=draft.path("id").asText();
        var formal=submit(customerService,id,false,null,"district-submit-"+id,200).path("data");
        String path="/api/v1/work-orders/"+id+"/accident-district";
        var body=new HashMap<String,Object>(Map.of("expectedVersion",formal.path("version").asLong(),"regionId",regionId,"reason","人工核实合成事故记录","confirmed",true));
        for(String token:List.of(shop,owner)){
            api(token,"PUT",path,body,null,403);
            api(token,"GET","/api/v1/work-orders/accident-districts",null,null,403);
        }
        var city=db.queryForObject("select parent_id from region where id=?",Long.class,regionId);
        body.put("regionId",city);api(customerService,"PUT",path,body,null,400);
        body.put("regionId",regionId);body.put("confirmed",false);api(customerService,"PUT",path,body,null,400);
        body.put("confirmed",true);body.put("reason"," ");api(customerService,"PUT",path,body,null,400);
        body.put("reason","人工核实合成事故记录");
        var saved=api(customerService,"PUT",path,body,null,200);
        assertThat(saved.path("accidentRegionId").asLong()).isEqualTo(regionId);
        assertThat(saved.path("accidentAddress").asText()).isEqualTo("合成事故地址（保持原文）");
        assertThat(saved.hasNonNull("parkingRegionId")).isFalse();assertThat(saved.hasNonNull("parkingAddress")).isFalse();
        assertThat(saved.path("version").asLong()).isEqualTo(formal.path("version").asLong()+1);
        api(customerService,"PUT",path,body,null,409);
        var location=api(customerService,"PUT","/api/v1/work-orders/"+id+"/dispatch-location",Map.of("expectedVersion",saved.path("version").asLong(),"regionId",city,"address","另一个合成停放地址","confirmed",true),null,200);
        body.put("expectedVersion",location.path("version").asLong());
        var reviewed=api(admin,"PUT",path,body,null,200);
        assertThat(reviewed.path("parkingRegionId").asLong()).isEqualTo(city);
        assertThat(reviewed.path("parkingAddress").asText()).isEqualTo("另一个合成停放地址");
        assertThat(reviewed.path("accidentAddress").asText()).isEqualTo("合成事故地址（保持原文）");
        var districts=api(customerService,"GET","/api/v1/work-orders/accident-districts?size=1",null,null,200);
        assertThat(districts.path("records").size()).isEqualTo(1);
        assertThat(districts.path("records").get(0).path("name").asText()).contains(" / ");
        api(customerService,"GET","/api/v1/work-orders/accident-districts?size=101",null,null,400);
        var logs=db.queryForList("select summary from audit_log where object_id=? and summary like 'Accident district manually reviewed%'",id);
        assertThat(logs).hasSize(2);assertThat(logs.toString()).contains("old=null","new="+regionId,"version=","人工核实合成事故记录").doesNotContain("合成事故地址","另一个合成停放地址");
        db.update("update work_order set status='COMPLETED' where id=?::uuid",id);
        body.put("expectedVersion",reviewed.path("version").asLong());api(customerService,"PUT",path,body,null,409);
    }

    @Test void accidentDistrictChangeCannotInvalidateLiveAssignmentOrExpandCityService() throws Exception {
        var row=formal(customerService,"DISTRICT-LIVE-"+UUID.randomUUID(),uniquePhone(),"VIN-"+UUID.randomUUID(),Instant.now());
        String id=row.path("id").asText();
        var dispatched=dispatch(customerService,id,shopId,false,null,"district-dispatch-"+id,200).path("data");
        long city=db.queryForObject("select parent_id from region where id=?",Long.class,regionId);
        long another=db.queryForObject("insert into region(parent_id,level,name,code,enabled) values(?,3,'合成另一区县',?,true) returning id",Long.class,city,"TEST-"+UUID.randomUUID().toString().substring(0,8));
        // A city service row does not grant service in its children.
        db.update("insert into shop_service_region(shop_id,region_id) values(?,?) on conflict do nothing",shopId,city);
        var body=Map.of("expectedVersion",dispatched.path("version").asLong(),"regionId",another,"reason","核查测试，不允许失效当前派单","confirmed",true);
        api(customerService,"PUT","/api/v1/work-orders/"+id+"/accident-district",body,null,409);
        assertThat(db.queryForObject("select accident_region_id from work_order where id=?::uuid",Long.class,id)).isEqualTo(regionId);
        assertThat(db.queryForObject("select version from work_order where id=?::uuid",Long.class,id)).isEqualTo(dispatched.path("version").asLong());
        assertThat(db.queryForObject("select count(*) from audit_log where object_id=? and summary like 'Accident district manually reviewed%'",Long.class,id)).isZero();
        db.update("delete from shop_service_region where shop_id=? and region_id=?",shopId,city);
    }

    private long historyCount(String id,String action) {
        return db.queryForObject("select count(*) from work_order_status_history where work_order_id=?::uuid and action=?",Long.class,id,action);
    }

    private String phone() {
        return "13"+UUID.randomUUID().toString().replace("-","").substring(0,9);
    }
}
