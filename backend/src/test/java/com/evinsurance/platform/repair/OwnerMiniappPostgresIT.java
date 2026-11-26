package com.evinsurance.platform.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.evinsurance.platform.identity.infrastructure.JwtService;
import com.evinsurance.platform.identity.infrastructure.UserMapper;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

/** Real PostgreSQL and API authorization, with explicitly seeded completed synthetic cases.
 * No WeChat exchange, MinIO upload/download, repair-start gate or real user data are exercised. */
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class OwnerMiniappPostgresIT {
    private static final String SCHEMA="s05_owner_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        var url=com.evinsurance.platform.support.PostgresTestEnvironment.url();
        var directory=Files.createTempDirectory("s05-foundation-");directory.toFile().deleteOnExit();
        var seed=directory.resolve("V2.1__development_foundation.sql");seed.toFile().deleteOnExit();
        try(var input=OwnerMiniappPostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")){Files.copy(Objects.requireNonNull(input),seed);}
        r.add("spring.flyway.locations",()->"classpath:db/migration,filesystem:"+directory);
        r.add("spring.datasource.url",()->com.evinsurance.platform.support.PostgresTestEnvironment.schemaUrl(SCHEMA));
        r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        r.add("JWT_SECRET_BASE64",()->Base64.getEncoder().encodeToString(key));r.add("JWT_TTL_SECONDS",()->"1800");
        r.add("OCR_WORKER_ENABLED",()->"false");r.add("WECHAT_LOGIN_ENABLED",()->"false");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->UUID.randomUUID().toString());
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired JwtService jwt; @Autowired UserMapper users;
    private String token(String username){return jwt.issue(users.accessByUsername(username).current()).accessToken();}
    private long user(String username){return users.accessByUsername(username).id();}
    private JsonNode getAs(String token,String path,int status) throws Exception {
        var response=mvc.perform(get("/api/v1/"+path).header("Authorization","Bearer "+token)).andExpect(status().is(status)).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }
    private JsonNode postAs(String token,String path,Object body,String key,int status) throws Exception {
        var response=mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }
    private record Fixture(UUID id,UUID photo) {}
    private Fixture fixture() throws Exception {
        UUID id=UUID.randomUUID(),photo=UUID.randomUUID(),completion=UUID.randomUUID();
        long staff=user("dev_customer_service"),owner=user("dev_owner"),shopUser=user("dev_repair_shop");
        long shop=users.accessByUsername("dev_repair_shop").current().shopId();
        String shortId=id.toString().substring(0,8);
        db.update("""
            INSERT INTO work_order(id,business_no,claim_no,current_responsible_id,owner_user_id,owner_binding_status,
              owner_phone,vehicle_plate,shop_id,status,version,created_by,updated_by)
            VALUES(?,?,?, ?,?,'BOUND','13800000000','S05-SYNTHETIC',?,'WAITING_OWNER_CONFIRMATION',41,?,?)
            """,id,"S05-"+shortId,"SYNTHETIC-"+id,staff,owner,shop,staff,staff);
        db.update("INSERT INTO work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by) VALUES(?,1,?,'ACCEPTED',?)",id,shop,staff);
        db.update("INSERT INTO quotation_event(work_order_id,basis_version,kind,summary,actor_id) VALUES(?,0,'REPAIR_STARTED','S05 synthetic prepared state',?)",id,staff);
        db.update("""
            INSERT INTO case_file(id,work_order_id,group_id,category,version_no,state,object_key,original_name,
              content_type,byte_size,sha256,shop_id,assignment_version,uploaded_by)
            VALUES(?,?,?,'COMPLETION_PHOTO',1,'ACTIVE',?,'synthetic.png','image/png',1,?, ?,1,?)
            """,photo,id,UUID.randomUUID(),"s05-not-uploaded/"+photo,"0".repeat(64),shop,shopUser);
        String snapshot=json.writeValueAsString(List.of(Map.of("id",photo,"version",1,"category","COMPLETION_PHOTO","contentType","image/png")));
        db.update("INSERT INTO repair_completion(id,work_order_id,shop_id,assignment_version,photo_snapshot,created_by) VALUES(?,?,?,1,?,?)",completion,id,shop,snapshot,shopUser);
        db.update("INSERT INTO repair_completion_file(completion_id,file_id,version_no) VALUES(?,?,1)",completion,photo);
        db.update("INSERT INTO repair_context(work_order_id,version) VALUES(?,7)",id);
        return new Fixture(id,photo);
    }
    @Test void receiptReviewComplaintRespectOriginalsVersionsIdempotencyAndPublicFields() throws Exception {
        var fixture=fixture();String id=fixture.id().toString(),owner=token("dev_owner"),staff=token("dev_customer_service"),admin=token("dev_admin");
        String path="repairs/cases/"+id;
        postAs(owner,path+"/receipt",Map.of("expectedVersion",41),UUID.randomUUID().toString(),409);
        String receiptKey=UUID.randomUUID().toString();var receipt=Map.of("expectedVersion",7);
        var received=postAs(owner,path+"/receipt",receipt,receiptKey,200);
        assertThat(received.path("status").asText()).isEqualTo("WAITING_INSURER_PAYMENT");
        assertThat(received.path("version").asInt()).isEqualTo(8);
        postAs(owner,path+"/receipt",receipt,receiptKey,200);
        assertThat(db.queryForObject("SELECT count(*) FROM repair_receipt WHERE work_order_id=?",Integer.class,fixture.id())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='REPAIR_RECEIPT_CONFIRM'",Integer.class,id)).isEqualTo(1);
        postAs(owner,path+"/review",Map.of("expectedVersion",8,"text",""),UUID.randomUUID().toString(),400);
        var review=Map.of("expectedVersion",8,"text","S05 original review","score",5);String reviewKey=UUID.randomUUID().toString();
        postAs(owner,path+"/review",review,reviewKey,200);postAs(owner,path+"/review",review,reviewKey,200);
        postAs(owner,path+"/review",Map.of("expectedVersion",9,"score",4),UUID.randomUUID().toString(),409);
        postAs(staff,path+"/review/corrections",Map.of("expectedVersion",9,"text","Public correction","score",3,"reason","SECRET_INTERNAL_REASON"),UUID.randomUUID().toString(),200);
        var context=getAs(owner,path,200);
        assertThat(context.path("review").path("original").path("score").asInt()).isEqualTo(5);
        assertThat(context.path("review").path("current").path("id").asText()).matches("[0-9]+");
        assertThat(context.toString()).doesNotContain("SECRET_INTERNAL_REASON","reason");
        var body=Map.of("description","S05 complaint original","photoIds",List.of(fixture.photo()));String complaintKey=UUID.randomUUID().toString();
        var complaint=postAs(owner,"complaints/cases/"+id,body,complaintKey,200);postAs(owner,"complaints/cases/"+id,body,complaintKey,200);
        assertThat(db.queryForObject("SELECT count(*) FROM complaint WHERE work_order_id=?",Integer.class,fixture.id())).isEqualTo(1);
        String complaintId=complaint.path("id").asText();
        postAs(admin,"complaints/"+complaintId+"/handle",Map.of("expectedVersion",0,"status","PROCESSING","publicNote","Public handling","internalNote","SECRET_INTERNAL_NOTE"),UUID.randomUUID().toString(),200);
        var events=getAs(owner,"complaints/"+complaintId+"/history?page=1&size=1",200);
        assertThat(events.toString()).contains("Public handling").doesNotContain("SECRET_INTERNAL_NOTE","internalNote");
        assertThat(getAs(owner,path,200)).isEqualTo(context);
        assertThat(db.queryForObject("SELECT count(*) FROM repair_completion_file WHERE completion_id=(SELECT id FROM repair_completion WHERE work_order_id=?)",Integer.class,fixture.id())).isEqualTo(1);
    }
    @Test void unrelatedOwnerCannotReadOrWriteAndOwnerCannotUseInternalEndpoints() throws Exception {
        var fixture=fixture();String owner=token("dev_owner"),shop=token("dev_repair_shop");
        long another=db.queryForObject("INSERT INTO app_user(username,display_name,password_hash,activated) VALUES(?, 'S05 unrelated synthetic owner','not-a-real-password',true) RETURNING id",Long.class,"s05_"+UUID.randomUUID().toString().replace("-",""));
        db.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM app_role WHERE code='OWNER'",another);
        users.ensureOwner(another,"S05 unrelated synthetic owner");
        String other=jwt.issue(users.accessById(another).current()).accessToken(),id=fixture.id().toString();
        getAs(other,"work-orders/"+id,403);getAs(other,"repairs/cases/"+id,403);getAs(other,"materials/cases/"+id,403);
        postAs(other,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",7),UUID.randomUUID().toString(),403);
        postAs(shop,"repairs/cases/"+id+"/receipt",Map.of("expectedVersion",7),UUID.randomUUID().toString(),403);
        getAs(owner,"work-orders/"+id+"/workflow",403);getAs(owner,"quotations/cases/"+id,403);getAs(owner,"funds/cases/"+id,403);
        var items=getAs(other,"work-orders?page=1&size=20",200).path("records");
        for(var item:items)assertThat(item.path("id").asText()).isNotEqualTo(id);
        assertThat(db.queryForObject("SELECT count(*) FROM repair_receipt WHERE work_order_id=?",Integer.class,fixture.id())).isZero();
    }
}
