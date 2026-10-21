package com.evinsurance.platform.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.evinsurance.platform.identity.application.AccountActivationService;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import tools.jackson.databind.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.flywaydb.core.Flyway;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class AccountActivationPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SCHEMA="activation_it_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        String url=System.getenv("TEST_DB_URL");if(url==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);r.add("JWT_SECRET_BASE64",()->Base64.getEncoder().encodeToString(key));
        r.add("JWT_TTL_SECONDS",()->"1800");r.add("OCR_WORKER_ENABLED",()->"false");
        r.add("ACCOUNT_ADMIN_PUBLIC_URL",()->"https://staff.example.test/platform");r.add("ACCOUNT_H5_PUBLIC_URL",()->"https://mobile.example.test");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;@Autowired Flyway flyway;@Autowired WorkOrderMapper orders;
    String admin,staff,shop,owner;long shopId,staffId,adminId;
    @BeforeEach void authenticate() throws Exception {
        db.update("DELETE FROM account_rate_limit");
        admin=legacy("dev_admin","ADMIN");staff=legacy("dev_customer_service","ADMIN");shop=legacy("dev_repair_shop","H5");owner=legacy("dev_owner","H5");
        shopId=db.queryForObject("SELECT id FROM repair_shop WHERE code='DEV-SHOP'",Long.class);
        adminId=db.queryForObject("SELECT id FROM app_user WHERE username='dev_admin'",Long.class);
        staffId=db.queryForObject("SELECT id FROM app_user WHERE username='dev_customer_service'",Long.class);
    }
    private JsonNode call(String path,String bearer,Object body,int expected) throws Exception {
        var request=post("/api/v1"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String legacy(String username,String portal) throws Exception {return call("/auth/login",null,Map.of("username",username,"password",PASSWORD,"portal",portal),200).path("accessToken").asText();}
    private JsonNode prepare(String role) throws Exception {
        var body=new HashMap<String,Object>();body.put("username","invited_"+UUID.randomUUID().toString().replace("-",""));body.put("displayName","合成受邀账号");body.put("role",role);
        if(role.equals("REPAIR_SHOP"))body.put("shopId",shopId);
        return call("/admin/account-invitations/accounts",admin,body,200);
    }
    private String raw(JsonNode invite){return invite.path("url").asText().split("#token=")[1];}
    private JsonNode activate(String raw,String password,int expected) throws Exception {return call("/auth/activation/complete",null,Map.of("token",raw,"password",password,"confirmPassword",password),expected);}
    private void enabled(long id,boolean enabled) throws Exception {mvc.perform(patch("/api/v1/admin/users/"+id+"/enabled").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled",enabled)))).andExpect(status().isOk());}
    private UUID caseFixture(String phone,String state,long creator){
        UUID id=UUID.randomUUID();db.update("INSERT INTO work_order(id,business_no,current_responsible_id,created_by,updated_by,status,owner_name,owner_phone,claim_no,vehicle_plate) VALUES(?,?,?,?,?,?,?,?,?,?)",id,state.equals("DRAFT")?null:"SYN-"+id.toString().substring(0,16),creator,creator,creator,state,"合成车主",phone,"SYN-"+id,"TEST-PLATE");return id;
    }
    private Map<String,Object> ownerBody(UUID id){return Map.of("expectedVersion",db.queryForObject("SELECT version FROM work_order WHERE id=?",Long.class,id),"verified",true,"reason","合成测试：经线下核实本人及案件归属");}

    @Test void adminPreparesInvitedStaffAndMultipleAccountsForOneValidShop() throws Exception {
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","REPAIR_SHOP")){
            var invite=prepare(role);long id=invite.path("userId").asLong();String raw=raw(invite);
            assertThat(invite.path("url").asText()).startsWith(role.equals("REPAIR_SHOP")?"https://mobile.example.test/activate#":"https://staff.example.test/platform/activate#");
            assertThat(db.queryForObject("SELECT activated FROM app_user WHERE id=?",Boolean.class,id)).isFalse();
            assertThat(db.queryForObject("SELECT token_hash FROM account_invitation WHERE user_id=?",String.class,id)).isEqualTo(AccountActivationService.digest(raw)).doesNotContain(raw);
            call("/auth/sign-in",null,Map.of("username",invite.path("username").asText(),"password",PASSWORD),401);
            var preview=call("/auth/activation/inspect",null,Map.of("token",raw),200);assertThat(preview.path("username").asText()).contains("***");
            assertThat(db.queryForObject("SELECT activated FROM app_user WHERE id=?",Boolean.class,id)).isFalse();
            var complete=activate(raw,PASSWORD,200);assertThat(complete.path("username")).isEqualTo(invite.path("username"));
            var destination=call("/auth/sign-in",null,Map.of("username",invite.path("username").asText(),"password",PASSWORD),200);
            String portal=role.equals("REPAIR_SHOP")?"H5":"ADMIN";assertThat(destination.path("portal").asText()).isEqualTo(portal);
            assertThat(call("/auth/handoff",null,Map.of("code",destination.path("code").asText(),"portal",portal),200).path("accessToken").asText()).isNotBlank();
            if(portal.equals("H5"))assertThat(db.queryForObject("SELECT shop_id FROM shop_account WHERE user_id=?",Long.class,id)).isEqualTo(shopId);
        }
        call("/admin/account-invitations/accounts",admin,Map.of("username","invalid_shop","displayName","测试","role","REPAIR_SHOP","shopId",Long.MAX_VALUE),400);
        call("/admin/account-invitations/accounts",admin,Map.of("username","invalid_owner","displayName","测试","role","OWNER"),400);
    }
    @Test void resendExpiryForgeryAndDisabledStateNeverResetOrEnableAccounts() throws Exception {
        var invite=prepare("CUSTOMER_SERVICE");long id=invite.path("userId").asLong();String original=raw(invite);
        var replacement=call("/admin/account-invitations/users/"+id,admin,Map.of(),200);
        activate(original,PASSWORD,409);activate(AccountActivationService.token(),PASSWORD,409);
        db.update("UPDATE account_invitation SET created_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE user_id=? AND revoked_at IS NULL",id);
        activate(raw(replacement),PASSWORD,409);
        var current=call("/admin/account-invitations/users/"+id,admin,Map.of(),200);enabled(id,false);activate(raw(current),PASSWORD,409);enabled(id,true);activate(raw(current),PASSWORD,409);
        assertThat(db.queryForObject("SELECT activated FROM app_user WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(db.queryForObject("SELECT enabled FROM app_user WHERE id=?",Boolean.class,id)).isTrue();
        call("/admin/users/"+id+"/password-reset",admin,Map.of("password",PASSWORD),409);
    }
    @Test void usedInvitationCannotResetPasswordAndActivationIsAtomic() throws Exception {
        var invite=prepare("CUSTOMER_SERVICE");String raw=raw(invite),chosen=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(2)){
            var jobs=List.<java.util.concurrent.Callable<Integer>>of(()->activationStatus(raw,chosen),()->activationStatus(raw,chosen));
            var results=new ArrayList<Integer>();for(var future:pool.invokeAll(jobs))results.add(future.get());assertThat(results).containsExactlyInAnyOrder(200,409);
        }
        activate(raw,UUID.randomUUID().toString(),409);
        long id=invite.path("userId").asLong();assertThat(encoder.matches(chosen,db.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,id))).isTrue();
        call("/admin/account-invitations/users/"+id,admin,Map.of(),409);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE action='ACCOUNT_ACTIVATE' AND object_id=?",Long.class,Long.toString(id))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT actor_id FROM audit_log WHERE action='ACCOUNT_ACTIVATE' AND object_id=?",Long.class,Long.toString(id))).isEqualTo(id);
    }
    private int activationStatus(String raw,String password) throws Exception {return mvc.perform(post("/api/v1/auth/activation/complete").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("token",raw,"password",password,"confirmPassword",password)))).andReturn().getResponse().getStatus();}
    @Test void ownerInvitationIsFixedRoleExplicitCaseAndNeverPhoneClaiming() throws Exception {
        String phone="SYN-"+UUID.randomUUID().toString().substring(0,20);UUID first=caseFixture(phone,"PENDING_DISPATCH",staffId);
        var requested=new HashMap<>(ownerBody(first));requested.put("role","ADMIN"); // ignored: fixed-role service owns assignment
        var invite=call("/work-orders/"+first+"/owner-invitation",staff,requested,200);long id=invite.path("userId").asLong();
        assertThat(db.queryForList("SELECT r.code FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=?",String.class,id)).containsExactly("OWNER");
        assertThat(db.queryForObject("SELECT owner_user_id FROM work_order WHERE id=?",Long.class,first)).isEqualTo(id);
        assertThat(db.queryForObject("SELECT invitation_managed FROM owner_profile WHERE user_id=?",Boolean.class,id)).isTrue();
        var resent=call("/work-orders/"+first+"/owner-invitation",staff,ownerBody(first),200);assertThat(resent.path("userId").asLong()).isEqualTo(id);activate(raw(invite),PASSWORD,409);activate(raw(resent),PASSWORD,200);
        assertThat(orders.ownersByPhone(phone)).isEmpty(); // newly invited identities never enter legacy auto phone matching
        UUID second=caseFixture(phone,"PENDING_DISPATCH",staffId);call("/work-orders/"+second+"/owner-invitation",staff,ownerBody(second),409);
        assertThat(db.queryForObject("SELECT owner_user_id FROM work_order WHERE id=?",Long.class,second)).isNull();
        var arbitrary=new HashMap<>(ownerBody(second));arbitrary.put("existingOwnerId",id);call("/work-orders/"+second+"/owner-invitation",staff,arbitrary,403);
        String token=legacy(invite.path("username").asText(),"H5");
        mvc.perform(get("/api/v1/work-orders/"+first).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/work-orders/"+second).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        var verified=new HashMap<>(ownerBody(second));verified.put("existingOwnerId",id);
        var reused=call("/work-orders/"+second+"/owner-invitation",admin,verified,200);
        assertThat(reused.path("alreadyActive").asBoolean()).isTrue();assertThat(reused.path("userId").asLong()).isEqualTo(id);
        assertThat(reused.path("url").asText()).isEqualTo("https://mobile.example.test/login");
        assertThat(db.queryForObject("SELECT count(*) FROM account_invitation WHERE user_id=?",Long.class,id)).isEqualTo(2);
        mvc.perform(get("/api/v1/work-orders/"+second).header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void ownerInvitationRejectsWrongScopeStatusIdentityAndStaleVersion() throws Exception {
        UUID hidden=caseFixture("SYN-DRAFT","DRAFT",adminId);call("/work-orders/"+hidden+"/owner-invitation",staff,ownerBody(hidden),403);
        for(String state:List.of("CANCELLED","COMPLETED","CLOSED")){UUID id=caseFixture("SYN-"+state,state,staffId);call("/work-orders/"+id+"/owner-invitation",staff,ownerBody(id),409);}
        UUID id=caseFixture("SYN-"+UUID.randomUUID().toString().substring(0,20),"PENDING_DISPATCH",staffId);
        UUID incomplete=caseFixture("SYN-INCOMPLETE","PENDING_DISPATCH",staffId);db.update("UPDATE work_order SET owner_name=NULL WHERE id=?",incomplete);
        call("/work-orders/"+incomplete+"/owner-invitation",staff,ownerBody(incomplete),400);
        call("/work-orders/"+id+"/owner-invitation",staff,Map.of("expectedVersion",99,"verified",true,"reason","合成核实"),409);
        call("/work-orders/"+id+"/owner-invitation",staff,Map.of("expectedVersion",0,"verified",false,"reason","未核实"),400);
        for(String token:List.of(shop,owner))call("/work-orders/"+id+"/owner-invitation",token,ownerBody(id),403);
        String existingPhone=db.queryForObject("SELECT contact_phone FROM owner_profile WHERE user_id=(SELECT id FROM app_user WHERE username='dev_owner')",String.class);
        if(existingPhone==null){existingPhone="SYN-EXISTING";db.update("UPDATE owner_profile SET contact_phone=? WHERE user_id=(SELECT id FROM app_user WHERE username='dev_owner')",existingPhone);}
        UUID conflict=caseFixture(existingPhone,"PENDING_DISPATCH",staffId);call("/work-orders/"+conflict+"/owner-invitation",staff,ownerBody(conflict),409);
    }
    @Test void onlyAdminsCanPrepareReadAndReissueGeneralInvitations() throws Exception {
        var invite=prepare("CUSTOMER_SERVICE");long id=invite.path("userId").asLong();
        for(String token:List.of(staff,shop,owner)){
            for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP"))call("/admin/account-invitations/accounts",token,Map.of("username","forbidden","displayName","测试","role",role),403);
            call("/admin/account-invitations/users/"+id,token,Map.of(),403);
            mvc.perform(get("/api/v1/admin/account-invitations/users/"+id).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
        call("/admin/account-invitations/users/"+id,null,Map.of(),401);
        String status=mvc.perform(get("/api/v1/admin/account-invitations/users/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING")).andReturn().getResponse().getContentAsString();
        assertThat(status).doesNotContain(raw(invite),"token_hash");
        mvc.perform(get("/api/v1/auth/activation/complete")).andExpect(status().isUnauthorized());
    }
    @Test void handoffIsAudienceBoundOneUseExpiredAndCurrentAccountChecked() throws Exception {
        for(String role:List.of("admin","customer_service","repair_shop","owner")){
            String portal=role.equals("admin")||role.equals("customer_service")?"ADMIN":"H5";
            var destination=call("/auth/sign-in",null,Map.of("username","dev_"+role,"password",PASSWORD,"role","ADMIN"),200);
            assertThat(destination.path("portal").asText()).isEqualTo(portal);assertThat(destination.has("accessToken")).isFalse();
            String code=destination.path("code").asText();call("/auth/handoff",null,Map.of("code",code,"portal",portal.equals("ADMIN")?"H5":"ADMIN"),403);
            call("/auth/handoff",null,Map.of("code",code,"portal",portal),200);call("/auth/handoff",null,Map.of("code",code,"portal",portal),409);
        }
        var pending=prepare("CUSTOMER_SERVICE");activate(raw(pending),PASSWORD,200);long id=pending.path("userId").asLong();
        var destination=call("/auth/sign-in",null,Map.of("username",pending.path("username").asText(),"password",PASSWORD),200);enabled(id,false);
        call("/auth/handoff",null,Map.of("code",destination.path("code").asText(),"portal","ADMIN"),403);
        var expired=call("/auth/sign-in",null,Map.of("username","dev_admin","password",PASSWORD),200);db.update("UPDATE login_handoff SET expires_at=now()-interval '1 second' WHERE token_hash=?",AccountActivationService.digest(expired.path("code").asText()));
        call("/auth/handoff",null,Map.of("code",expired.path("code").asText(),"portal","ADMIN"),409);
    }
    @Test void auditsContainNoPasswordsTokensOrInvitationLinksAndFailurePersists() throws Exception {
        var invite=prepare("CUSTOMER_SERVICE");String raw=raw(invite);activate(raw,PASSWORD,200);activate(raw,PASSWORD,409);
        call("/auth/sign-in",null,Map.of("username","dev_admin","password",UUID.randomUUID().toString()),401);
        var destination=call("/auth/sign-in",null,Map.of("username","dev_admin","password",PASSWORD),200);
        String logs=json.writeValueAsString(db.queryForList("SELECT * FROM audit_log"));
        assertThat(logs).contains("INVITATION_ISSUE","ACCOUNT_ACTIVATE","ACCOUNT_ACTIVATION_FAILURE","LOGIN_FAILURE").doesNotContain(raw,invite.path("url").asText(),PASSWORD,destination.path("code").asText(),"$2a$");
        flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("25");
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/auth/activation/complete'].post.responses['429']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/admin/account-invitations/accounts'].post.security[0].bearerAuth").exists());
    }
    @Test void backendRateLimitsVerificationAndTargetResends() throws Exception {
        for(int i=0;i<20;i++)call("/auth/activation/inspect",null,Map.of("token","forged"),409);
        call("/auth/activation/inspect",null,Map.of("token","forged"),429);
        var invite=prepare("CUSTOMER_SERVICE");long id=invite.path("userId").asLong();
        call("/admin/account-invitations/users/"+id,admin,Map.of(),200);call("/admin/account-invitations/users/"+id,admin,Map.of(),200);call("/admin/account-invitations/users/"+id,admin,Map.of(),429);
        // Four legacy fixture logins already consumed the same IP's shared login budget.
        for(int i=0;i<16;i++)call("/auth/sign-in",null,Map.of("username","absent","password",PASSWORD),401);
        call("/auth/sign-in",null,Map.of("username","absent","password",PASSWORD),429);
    }
    @Test void assignmentChangeOrDisabledShopInvalidatesPendingInvitation() throws Exception {
        var invite=prepare("REPAIR_SHOP");long id=invite.path("userId").asLong();
        mvc.perform(put("/api/v1/admin/users/"+id+"/assignment").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("roles",List.of("OWNER"))))).andExpect(status().isOk());
        activate(raw(invite),PASSWORD,409);
        var other=prepare("REPAIR_SHOP");
        db.update("UPDATE repair_shop SET enabled=false WHERE id=?",shopId);
        try{activate(raw(other),PASSWORD,409);call("/admin/account-invitations/users/"+other.path("userId").asLong(),admin,Map.of(),409);}
        finally{db.update("UPDATE repair_shop SET enabled=true WHERE id=?",shopId);}
    }
}
