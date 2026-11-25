package com.evinsurance.platform.repair;

import static org.assertj.core.api.Assertions.*;
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

/** Real isolated controllers/transactions; file metadata are synthetic, not MinIO upload evidence. */
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class ShopRepairMiniappPostgresIT {
    private static final String SCHEMA="s07_repair_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        var url=System.getenv("TEST_DB_URL");
        if(url==null||!url.startsWith("jdbc:postgresql://127.0.0.1:55437/"))throw new IllegalStateException("S07 requires its isolated loopback PostgreSQL on 55437");
        var directory=Files.createTempDirectory("s07-foundation-");directory.toFile().deleteOnExit();var seed=directory.resolve("V2.1__development_foundation.sql");seed.toFile().deleteOnExit();
        try(var input=ShopRepairMiniappPostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")){Files.copy(Objects.requireNonNull(input),seed);}
        r.add("spring.flyway.locations",()->"classpath:db/migration,filesystem:"+directory);
        r.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);r.add("JWT_SECRET_BASE64",()->Base64.getEncoder().encodeToString(key));r.add("JWT_TTL_SECONDS",()->"1800");
        r.add("OCR_WORKER_ENABLED",()->"false");r.add("WECHAT_LOGIN_ENABLED",()->"false");
        String password=UUID.randomUUID().toString();for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->password);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db; @Autowired UserMapper users; @Autowired JwtService jwt;
    private long user(String name){return users.accessByUsername(name).id();}
    private String token(String name){return jwt.issue(users.accessByUsername(name).current()).accessToken();}
    private JsonNode read(String token,String path,int expected) throws Exception {var response=mvc.perform(get("/api/v1/"+path).header("Authorization","Bearer "+token)).andExpect(status().is(expected)).andReturn().getResponse();return json.readTree(response.getContentAsString()).path("data");}
    private JsonNode write(String token,String path,Object body,String key,int expected) throws Exception {var response=mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();assertThat(response.getStatus()).as("%s: %s",path,response.getContentAsString()).isEqualTo(expected);return json.readTree(response.getContentAsString()).path("data");}
    private JsonNode write(String token,String path,Object body,int expected) throws Exception {return write(token,path,body,UUID.randomUUID().toString(),expected);}
    private UUID fixture() {
        UUID id=UUID.randomUUID();long staff=user("dev_customer_service"),shop=users.accessByUsername("dev_repair_shop").shopId();
        db.update("""
            INSERT INTO work_order(id,business_no,claim_no,current_responsible_id,owner_user_id,owner_binding_status,
              owner_phone,vehicle_plate,shop_id,status,version,created_by,updated_by)
            VALUES(?,?,?,?,?,'BOUND','13800000000','S07-SYNTHETIC',?,'ARRIVED',41,?,?)
            """,id,"S07-"+id.toString().substring(0,8),"SYNTHETIC-"+id,staff,user("dev_owner"),shop,staff,staff);
        db.update("INSERT INTO work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by) VALUES(?,3,?,'ACCEPTED',?)",id,shop,staff);return id;
    }
    private UUID metadata(UUID caseId,String category,int assignment) {
        UUID file=UUID.randomUUID();long uploader=user("dev_repair_shop"),shop=users.accessByUsername("dev_repair_shop").shopId();
        db.update("""
            INSERT INTO case_file(id,work_order_id,group_id,category,version_no,state,object_key,original_name,content_type,
              byte_size,sha256,shop_id,assignment_version,uploaded_by)
            VALUES(?,?,?, ?,2,'ACTIVE',?,'synthetic.png','image/png',1,?,?,?,?)
            """,file,caseId,UUID.randomUUID(),category,"s07-not-uploaded/"+file,"0".repeat(64),shop,assignment,uploader);
        db.update("INSERT INTO audit_log(actor_id,actor_roles,action,object_type,object_id,summary,trace_id) VALUES(?,'REPAIR_SHOP','FILE_UPLOAD','CASE_FILE',?,'S07 synthetic metadata fixture',?)",uploader,file.toString(),UUID.randomUUID().toString());return file;
    }
    private void authorize(UUID caseId) throws Exception {
        String id=caseId.toString(),shop=token("dev_repair_shop"),staff=token("dev_customer_service"),base="quotations/cases/"+id;
        metadata(caseId,"ARRIVAL_PHOTO",3);metadata(caseId,"SCENE_PHOTO",3);
        var c=write(shop,base+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",3,"lines",List.of(Map.of("description","S07 synthetic part","quantity",1,"unitPrice","100.00"))),200);
        c=write(staff,base+"/formal-quotes",Map.of("expectedVersion",c.path("version").asInt(),"rawQuoteId",c.path("rawQuote").path("id").asText(),"mode","FIXED_AMOUNT","fixedAmount","0.00","reviewedLines",List.of(Map.of("description","S07 verified part","quantity",1,"unitPrice","100.00"))),200);
        c=write(staff,base+"/assessments",Map.of("expectedVersion",c.path("version").asInt(),"amount","100.00"),200);
        c=write(staff,base+"/confirmations/INSURER",Map.of("expectedVersion",c.path("version").asInt()),200);
        c=write(staff,base+"/confirmations/CUSTOMER_SERVICE",Map.of("expectedVersion",c.path("version").asInt(),"assessmentId",c.path("assessment").path("id").asText(),"amount","100.00","originalReviewed",true),200);
        assertThat(c.path("status").asText()).isEqualTo("REPAIRING");assertThat(c.path("gate").path("allowed").asBoolean()).isTrue();
    }
    @Test void repairProgressCompletionFrozenEvidenceOwnerReceiptAndPublicReviewShareOneCase() throws Exception {
        UUID caseId=fixture();String id=caseId.toString(),shop=token("dev_repair_shop"),owner=token("dev_owner"),staff=token("dev_customer_service"),base="repairs/cases/"+id;
        write(shop,base+"/progress",Map.of("expectedVersion",0,"assignmentVersion",3,"note","not authorized","photoIds",List.of()),409);
        authorize(caseId);UUID progress=metadata(caseId,"PROGRESS_PHOTO",3),completion=metadata(caseId,"COMPLETION_PHOTO",3);
        var body=Map.of("expectedVersion",0,"assignmentVersion",3,"note","S07 checked","photoIds",List.of(progress));String key=UUID.randomUUID().toString();
        write(shop,base+"/progress",body,key,200);write(shop,base+"/progress",body,key,200);
        assertThat(read(owner,base+"/progress?page=1&size=1",200).path("total").asInt()).isEqualTo(1);
        write(shop,base+"/completion",Map.of("expectedVersion",1,"assignmentVersion",3,"photoIds",List.of()),400);
        write(shop,base+"/completion",Map.of("expectedVersion",1,"assignmentVersion",3,"photoIds",List.of(progress)),409);
        write(shop,base+"/completion",Map.of("expectedVersion",0,"assignmentVersion",3,"photoIds",List.of(completion)),409);
        var finish=Map.of("expectedVersion",1,"assignmentVersion",3,"photoIds",List.of(completion));key=UUID.randomUUID().toString();
        var done=write(shop,base+"/completion",finish,key,200);write(shop,base+"/completion",finish,key,200);
        assertThat(done.path("status").asText()).isEqualTo("WAITING_OWNER_CONFIRMATION");assertThat(done.path("version").asInt()).isEqualTo(2);
        var frozen=done.path("completion");assertThat(frozen.path("photos").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(read(owner,base,200).path("completion")).isEqualTo(frozen);
        write(staff,"materials/"+completion+"/void",Map.of(),409);
        assertThatThrownBy(()->db.update("UPDATE case_file SET state='VOID' WHERE id=?",completion)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM repair_completion WHERE work_order_id=?",Long.class,caseId)).isEqualTo(1);
        write(shop,base+"/receipt",Map.of("expectedVersion",2),403);
        var received=write(owner,base+"/receipt",Map.of("expectedVersion",2),200);assertThat(received.path("version").asInt()).isEqualTo(3);
        write(owner,base+"/review",Map.of("expectedVersion",3,"text","S07 owner review","score",5),200);
        var visible=read(shop,base,200);assertThat(visible.path("receipt").path("withdrawn").asBoolean()).isFalse();assertThat(visible.path("review").path("original").path("score").asInt()).isEqualTo(5);
        var funds=read(shop,"funds/cases/"+id,200);assertThat(funds.has("receivable")).isFalse();assertThat(funds.has("canRecordPay")).isFalse();assertThat(funds.has("payable")).isTrue();
        read(owner,"funds/cases/"+id,403);read(shop,"funds/cases/"+id+"/entries",403);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='REPAIR_COMPLETE'",Long.class,id)).isEqualTo(1);
    }
    @Test void foreignShopOwnerOldAssignmentAndUnauthorizedRepairCannotMutate() throws Exception {
        UUID caseId=fixture();String id=caseId.toString(),shop=token("dev_repair_shop"),owner=token("dev_owner"),base="repairs/cases/"+id;
        long anotherShop=db.queryForObject("INSERT INTO repair_shop(name,code) VALUES('S07 synthetic foreign shop',?) RETURNING id",Long.class,"S07-"+UUID.randomUUID().toString().substring(0,16));
        long account=db.queryForObject("INSERT INTO app_user(username,display_name,password_hash,activated) VALUES(?,'S07 foreign synthetic shop','not-a-password',true) RETURNING id",Long.class,"s07_"+UUID.randomUUID().toString().replace("-",""));
        users.addRole(account,"REPAIR_SHOP");users.addShopAccount(account,anotherShop,user("dev_admin"));String foreign=jwt.issue(users.accessById(account).current()).accessToken();
        read(foreign,base,403);read(foreign,"funds/cases/"+id,403);
        for(String actor:List.of(foreign,owner))write(actor,base+"/completion",Map.of("expectedVersion",0,"assignmentVersion",3,"photoIds",List.of()),403);
        write(shop,"quotations/cases/"+id+"/start-repair",Map.of("expectedVersion",0,"assignmentVersion",3),409);
        authorize(caseId);UUID old=metadata(caseId,"COMPLETION_PHOTO",2);
        write(shop,base+"/completion",Map.of("expectedVersion",0,"assignmentVersion",2,"photoIds",List.of(old)),403);
        write(shop,base+"/completion",Map.of("expectedVersion",0,"assignmentVersion",3,"photoIds",List.of(old)),409);
        write(shop,"funds/cases/"+id+"/entries",Map.of(),403);
        assertThat(db.queryForObject("SELECT count(*) FROM repair_completion WHERE work_order_id=?",Long.class,caseId)).isZero();
    }
}
