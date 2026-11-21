package com.evinsurance.platform.quotation;

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

/** Dedicated PostgreSQL/API tests. Arrival metadata are synthetic fixtures, not MinIO/native-upload evidence. */
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class ShopMiniappPostgresIT {
    private static final String SCHEMA="s06_shop_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        var url=System.getenv("TEST_DB_URL");
        if(url==null || !url.startsWith("jdbc:postgresql://127.0.0.1:55436/"))throw new IllegalStateException("S06 requires its isolated loopback PostgreSQL on 55436");
        var directory=Files.createTempDirectory("s06-foundation-");directory.toFile().deleteOnExit();var seed=directory.resolve("V2.1__development_foundation.sql");seed.toFile().deleteOnExit();
        try(var input=ShopMiniappPostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")){Files.copy(Objects.requireNonNull(input),seed);}
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
    private JsonNode getAs(String token,String path,int expected) throws Exception {var response=mvc.perform(get("/api/v1/"+path).header("Authorization","Bearer "+token)).andExpect(status().is(expected)).andReturn().getResponse();return json.readTree(response.getContentAsString()).path("data");}
    private JsonNode postAs(String token,String path,Object body,String key,int expected) throws Exception {var response=mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse();return json.readTree(response.getContentAsString()).path("data");}
    private UUID fixture() {
        UUID id=UUID.randomUUID();long staff=user("dev_customer_service"),shop=users.accessByUsername("dev_repair_shop").shopId();
        db.update("""
            INSERT INTO work_order(id,business_no,claim_no,current_responsible_id,owner_user_id,owner_binding_status,
              owner_phone,vehicle_plate,shop_id,status,version,created_by,updated_by)
            VALUES(?,?,?,?,?,'BOUND','13800000000','S06-SYNTHETIC',?,'PENDING_ACCEPTANCE',41,?,?)
            """,id,"S06-"+id.toString().substring(0,8),"SYNTHETIC-"+id,staff,user("dev_owner"),shop,staff,staff);
        db.update("INSERT INTO work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by) VALUES(?,3,?,'PENDING',?)",id,shop,staff);return id;
    }
    private void arrivalMetadata(UUID caseId) {
        UUID file=UUID.randomUUID();long uploader=user("dev_repair_shop"),shop=users.accessByUsername("dev_repair_shop").shopId();
        db.update("""
            INSERT INTO case_file(id,work_order_id,group_id,category,version_no,state,object_key,original_name,content_type,
              byte_size,sha256,shop_id,assignment_version,uploaded_by)
            VALUES(?,?,?,'ARRIVAL_PHOTO',1,'ACTIVE',?,'synthetic.png','image/png',1,?,?,3,?)
            """,file,caseId,UUID.randomUUID(),"s06-not-uploaded/"+file,"0".repeat(64),shop,uploader);
        db.update("INSERT INTO audit_log(actor_id,actor_roles,action,object_type,object_id,summary,trace_id) VALUES(?,'REPAIR_SHOP','FILE_UPLOAD','CASE_FILE',?,'S06 synthetic metadata fixture',?)",uploader,file.toString(),UUID.randomUUID().toString());
    }
    @Test void acceptArrivalExceptionAndQuotePreserveVersionGateIdempotencyAndMoney() throws Exception {
        UUID caseId=fixture();String id=caseId.toString(),shop=token("dev_repair_shop"),order="work-orders/"+id,quote="quotations/cases/"+id;
        postAs(shop,order+"/accept",Map.of("assignmentVersion",2),UUID.randomUUID().toString(),409);
        String key=UUID.randomUUID().toString();postAs(shop,order+"/accept",Map.of("assignmentVersion",3),key,200);postAs(shop,order+"/accept",Map.of("assignmentVersion",3),key,200);
        postAs(shop,order+"/arrival-exceptions",Map.of("assignmentVersion",3,"reason","S06 synthetic delay"),UUID.randomUUID().toString(),200);
        postAs(shop,order+"/continue-waiting",Map.of("assignmentVersion",3),UUID.randomUUID().toString(),200);
        postAs(shop,order+"/arrive",Map.of("assignmentVersion",3),UUID.randomUUID().toString(),200);
        var body=Map.of("expectedVersion",0,"assignmentVersion",3,"lines",List.of(Map.of("description","S06 synthetic part","quantity",3,"unitPrice","0.10")));
        assertThat(getAs(shop,quote,200).path("arrivalPhotoUploaded").asBoolean()).isFalse();postAs(shop,quote+"/raw-quotes",body,UUID.randomUUID().toString(),409);
        arrivalMetadata(caseId);assertThat(getAs(shop,quote,200).path("arrivalPhotoUploaded").asBoolean()).isTrue();
        key=UUID.randomUUID().toString();var result=postAs(shop,quote+"/raw-quotes",body,key,200);var replay=postAs(shop,quote+"/raw-quotes",body,key,200);
        assertThat(result.path("rawQuote").path("total").asText()).isEqualTo("0.30");assertThat(replay.path("rawQuote").path("id")).isEqualTo(result.path("rawQuote").path("id"));
        assertThat(db.queryForObject("SELECT count(*) FROM quote_raw WHERE work_order_id=?",Long.class,caseId)).isEqualTo(1);
        assertThat(result.has("formalQuote")).isFalse();assertThat(result.has("assessment")).isFalse();assertThat(result.path("authorized").asBoolean()).isFalse();
        var changed=Map.of("expectedVersion",result.path("version").asInt(),"assignmentVersion",3,"lines",List.of(Map.of("description","S06 new version","quantity",3,"unitPrice","0.20")));
        var next=postAs(shop,quote+"/raw-quotes",changed,UUID.randomUUID().toString(),200);assertThat(next.path("rawQuote").path("total").asText()).isEqualTo("0.60");
        assertThat(db.queryForObject("SELECT count(*) FROM quote_raw WHERE work_order_id=?",Long.class,caseId)).isEqualTo(2);
        assertThat(getAs(shop,quote+"/history?type=RAW&page=1&size=1",200).path("total").asInt()).isEqualTo(2);
        postAs(shop,quote+"/start-repair",Map.of("expectedVersion",next.path("version").asInt(),"assignmentVersion",3),UUID.randomUUID().toString(),409);
    }
    @Test void foreignShopOwnerAndStaleAssignmentCannotUseShopQuoteOrInternalEndpoints() throws Exception {
        UUID caseId=fixture();String id=caseId.toString(),shop=token("dev_repair_shop"),owner=token("dev_owner");
        long otherShop=db.queryForObject("INSERT INTO repair_shop(name,code) VALUES('S06 synthetic foreign shop',?) RETURNING id",Long.class,"S06-"+UUID.randomUUID().toString().substring(0,16));
        long account=db.queryForObject("INSERT INTO app_user(username,display_name,password_hash,activated) VALUES(?,'S06 foreign synthetic shop','not-a-password',true) RETURNING id",Long.class,"s06_"+UUID.randomUUID().toString().replace("-",""));
        users.addRole(account,"REPAIR_SHOP");users.addShopAccount(account,otherShop,user("dev_admin"));String foreign=jwt.issue(users.accessById(account).current()).accessToken();
        getAs(foreign,"work-orders/"+id,403);getAs(foreign,"quotations/cases/"+id,403);getAs(owner,"quotations/cases/"+id,403);
        for(String actor:List.of(foreign,owner))postAs(actor,"work-orders/"+id+"/accept",Map.of("assignmentVersion",3),UUID.randomUUID().toString(),403);
        postAs(shop,"work-orders/"+id+"/accept",Map.of("assignmentVersion",3),UUID.randomUUID().toString(),200);postAs(shop,"work-orders/"+id+"/arrive",Map.of("assignmentVersion",3),UUID.randomUUID().toString(),200);arrivalMetadata(caseId);
        var body=Map.of("expectedVersion",0,"assignmentVersion",2,"lines",List.of(Map.of("description","S06 stale quote","quantity",1,"unitPrice","1.00")));
        for(String actor:List.of(foreign,owner,shop))postAs(actor,"quotations/cases/"+id+"/raw-quotes",body,UUID.randomUUID().toString(),403);
        getAs(shop,"quotations/cases/"+id+"/history?type=FORMAL",403);getAs(shop,"work-orders/"+id+"/workflow",403);
        postAs(shop,"quotations/cases/"+id+"/assessments",Map.of("expectedVersion",0,"amount","1.00"),UUID.randomUUID().toString(),403);
        assertThat(db.queryForObject("SELECT count(*) FROM quote_raw WHERE work_order_id=?",Long.class,caseId)).isZero();
    }
}
