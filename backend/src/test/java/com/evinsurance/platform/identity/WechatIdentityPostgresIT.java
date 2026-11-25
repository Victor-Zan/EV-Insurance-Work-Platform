package com.evinsurance.platform.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.application.AccountActivationService;
import com.evinsurance.platform.identity.infrastructure.WechatCodeExchange;
import com.evinsurance.platform.identity.infrastructure.WechatLoginConfiguration;
import com.evinsurance.platform.identity.infrastructure.JwtService;
import com.evinsurance.platform.identity.infrastructure.UserMapper;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class WechatIdentityPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SCHEMA="wechat_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String APP_ID="wx-s03-synthetic";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.flyway.locations",()->String.join(",",IdentityTestMigrations.locations()));
        String url=System.getenv("TEST_DB_URL");
        if(url==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        r.add("JWT_SECRET_BASE64",()->Base64.getEncoder().encodeToString(key));r.add("JWT_TTL_SECONDS",()->"1800");
        r.add("OCR_WORKER_ENABLED",()->"false");r.add("WECHAT_LOGIN_ENABLED",()->"true");
        r.add("WECHAT_APP_ID",()->APP_ID);r.add("WECHAT_APP_SECRET",()->"synthetic-server-secret");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder; @Autowired Flyway flyway; @Autowired WechatLoginConfiguration configuration;
    @Autowired JwtService jwt; @Autowired UserMapper users;
    @MockitoBean WechatCodeExchange exchange;
    private final ConcurrentMap<String,String> codes=new ConcurrentHashMap<>();
    private String admin;
    private long adminId,shopId;
    @BeforeEach void setup() throws Exception {
        configuration.setEnabled(true);configuration.setAppId(APP_ID);db.update("DELETE FROM account_rate_limit");reset(exchange);codes.clear();
        when(exchange.exchange(anyString())).thenAnswer(call->{
            String identity=codes.remove(call.getArgument(0));
            if(identity==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"WECHAT_CODE_INVALID","Synthetic code invalid");
            return identity;
        });
        admin=legacy("dev_admin",PASSWORD,"ADMIN");
        adminId=db.queryForObject("SELECT id FROM app_user WHERE username='dev_admin'",Long.class);
        shopId=db.queryForObject("SELECT id FROM repair_shop WHERE code='DEV-SHOP'",Long.class);
    }
    private String code(String identity) { String code=UUID.randomUUID().toString();codes.put(code,identity);return code; }
    private String identity() { return "synthetic-"+UUID.randomUUID(); }
    private JsonNode post(String path,String bearer,Object body,int status) throws Exception {
        var request=postRequest(path,bearer,body);
        var result=mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse();
        if(path.contains("wechat"))assertThat(result.getHeader("Cache-Control")).contains("no-store");
        return json.readTree(result.getContentAsString());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postRequest(String path,String bearer,Object body) {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1"+path)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);
        return request;
    }
    private String legacy(String username,String password,String portal) throws Exception {
        return post("/auth/login",null,Map.of("username",username,"password",password,"portal",portal),200).path("data").path("accessToken").asString();
    }
    private JsonNode create(String role,boolean pending) throws Exception {
        String username="s03_"+UUID.randomUUID().toString().replace("-","");
        var body=new HashMap<String,Object>();body.put("username",username);body.put("displayName","合成微信账号");
        if(role.equals("REPAIR_SHOP"))body.put("shopId",shopId);
        if(pending)body.put("role",role);else { body.put("roles",List.of(role));body.put("password",PASSWORD); }
        return post(pending?"/admin/account-invitations/accounts":"/admin/users",admin,body,200).path("data");
    }
    private JsonNode bind(JsonNode user,String identity) throws Exception {
        return post("/auth/wechat/bindings",null,Map.of("code",code(identity),"username",user.path("username").asString(),"password",PASSWORD,"confirmed",true),200).path("data");
    }
    private JsonNode invite(long userId) throws Exception { return post("/admin/users/"+userId+"/wechat-invitation",admin,Map.of(),200).path("data"); }
    private JsonNode activate(String token,String identity,int status) throws Exception {
        return post("/auth/wechat/activation/complete",null,Map.of("token",token,"code",code(identity),"password",PASSWORD,"confirmPassword",PASSWORD,"confirmed",true),status);
    }
    private String token(JsonNode login) { return login.path("accessToken").asString(); }
    private void me(String token,int status) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().is(status));
    }

    @Test void activatedOwnerBindsThenLogsInWithoutExposingPrivateIdentityOrGrantingOtherRoles() throws Exception {
        var user=create("OWNER",false);String identity=identity();
        String old=legacy(user.path("username").asString(),PASSWORD,"H5");
        var bound=bind(user,identity);me(token(bound),200);me(old,401);
        assertThat(bound.path("user").path("roles").get(0).asString()).isEqualTo("OWNER");
        assertThat(bound.path("user").has("shopId")).isTrue();assertThat(bound.path("user").path("shopId").isNull()).isTrue();
        assertThat(bound.toString()).doesNotContain(identity,"ownerPhone","openid","session_key","password");
        var login=post("/auth/wechat/login",null,Map.of("code",code(identity)),200).path("data");me(token(login),200);
        mvc.perform(get("/api/v1/quotations/cases/"+UUID.randomUUID()).header("Authorization","Bearer "+token(login))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/funds/cases/"+UUID.randomUUID()).header("Authorization","Bearer "+token(login))).andExpect(status().isForbidden());
        var firstLogin=post("/auth/wechat/login",null,Map.of("code",code(identity())),200).path("data");
        assertThat(firstLogin.path("user").path("roles").get(0).asString()).isEqualTo("OWNER");
        assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,user.path("id").asLong())).isEqualTo(1);
    }
    @Test void invitationSetsH5PasswordConsumesOnceAndRetainsExistingWebLogin() throws Exception {
        var pending=create("REPAIR_SHOP",true);long id=pending.path("userId").asLong();
        var invitation=invite(id);String raw=invitation.path("invitationToken").asString();
        assertThat(invitation.path("entryPath").asString()).isEqualTo("pages/activate/index");
        var preview=post("/auth/wechat/activation/inspect",null,Map.of("token",raw),200).path("data");
        assertThat(preview.path("usernameMasked").asString()).contains("***");
        assertThat(preview.toString()).doesNotContain(pending.path("username").asString());
        assertThat(db.queryForObject("SELECT activated FROM app_user WHERE id=?",Boolean.class,id)).isFalse();
        String identity=identity();var result=activate(raw,identity,200).path("data");me(token(result),200);
        assertThat(result.path("user").path("roles").get(0).asString()).isEqualTo("REPAIR_SHOP");
        assertThat(result.path("user").path("shopId").asLong()).isEqualTo(shopId);
        assertThat(encoder.matches(PASSWORD,db.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,id))).isTrue();
        legacy(pending.path("username").asString(),PASSWORD,"H5");
        activate(raw,identity(),409);
        assertThat(db.queryForObject("SELECT used_at IS NOT NULL FROM account_invitation WHERE token_hash=?",Boolean.class,AccountActivationService.digest(raw))).isTrue();
        post("/auth/wechat/login",null,Map.of("code",code(identity)),200);
        long foreignShop=db.queryForObject("INSERT INTO repair_shop(name,code) VALUES('Synthetic other shop',?) RETURNING id",Long.class,"S03-"+UUID.randomUUID().toString().substring(0,16));
        UUID foreignCase=UUID.randomUUID();
        db.update("INSERT INTO work_order(id,business_no,status,shop_id,current_responsible_id,created_by,updated_by,owner_phone,claim_no,vehicle_plate) VALUES(?,?,'PENDING_DISPATCH',?,?,?,?,'SYN-FOREIGN','SYN-FOREIGN','SYN-PLATE')",foreignCase,"S03-"+foreignCase.toString().substring(0,16),foreignShop,adminId,adminId,adminId);
        mvc.perform(get("/api/v1/work-orders/"+foreignCase).header("Authorization","Bearer "+token(result))).andExpect(status().isForbidden());
    }
    @Test void bindingConflictRollsBackPasswordActivationInvitationAndSuccessAudit() throws Exception {
        String taken=identity();bind(create("OWNER",false),taken);
        var pending=create("REPAIR_SHOP",true);long id=pending.path("userId").asLong();String raw=invite(id).path("invitationToken").asString();
        String original=db.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,id);
        var response=activate(raw,taken,409);assertThat(response.path("code").asString()).isEqualTo("WECHAT_BINDING_CONFLICT");
        assertThat(db.queryForObject("SELECT activated FROM app_user WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(db.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,id)).isEqualTo(original);
        assertThat(db.queryForObject("SELECT used_at IS NULL FROM account_invitation WHERE token_hash=?",Boolean.class,AccountActivationService.digest(raw))).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,id)).isZero();
        activate(raw,identity(),200);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE action='WECHAT_AUTH_FAILURE'",Long.class)).isPositive();
    }
    @Test void concurrentInvitationConsumptionHasOnlyOneWinner() throws Exception {
        var pending=create("REPAIR_SHOP",true);long id=pending.path("userId").asLong();String raw=invite(id).path("invitationToken").asString();
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{
                var body=Map.of("token",raw,"code",code(identity()),"password",PASSWORD,"confirmPassword",PASSWORD,"confirmed",true);
                barrier.await(10,TimeUnit.SECONDS);
                return mvc.perform(postRequest("/auth/wechat/activation/complete",null,body)).andReturn().getResponse().getStatus();
            }));
            assertThat(List.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void revokeInvalidatesWechatAndH5SessionsAndAllowsNewWechatAfterVerification() throws Exception {
        var user=create("OWNER",false);long id=user.path("id").asLong();String oldIdentity=identity();var bound=bind(user,oldIdentity);
        String web=legacy(user.path("username").asString(),PASSWORD,"H5");
        var page=json.readTree(mvc.perform(get("/api/v1/admin/users/"+id+"/wechat-bindings").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(page.toString()).doesNotContain(oldIdentity,"openid");String binding=page.path("records").get(0).path("id").asString();
        String sensitive="synthetic-sensitive-token-1234567890";
        var body=Map.of("expectedVersion",0,"verified",true,"reason","合成恢复，人工核实 "+sensitive);
        post("/admin/users/"+id+"/wechat-bindings/"+binding+"/revoke",admin,body,200);
        me(token(bound),401);me(web,401);
        post("/auth/wechat/login",null,Map.of("code",code(oldIdentity)),409);
        long version=db.queryForObject("SELECT auth_version FROM app_user WHERE id=?",Long.class,id);
        post("/admin/users/"+id+"/wechat-bindings/"+binding+"/revoke",admin,body,409);
        assertThat(db.queryForObject("SELECT auth_version FROM app_user WHERE id=?",Long.class,id)).isEqualTo(version);
        assertThat(db.queryForObject("SELECT revoke_reason FROM wechat_identity_binding WHERE id=?",String.class,UUID.fromString(binding))).doesNotContain(sensitive);
        assertThat(db.queryForObject("SELECT summary FROM audit_log WHERE action='WECHAT_REVOKE' AND object_id=?",String.class,binding)).doesNotContain(sensitive,"合成恢复");
        me(token(bind(user,identity())),200);legacy(user.path("username").asString(),PASSWORD,"H5");
        assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,id)).isEqualTo(2);
    }
    @Test void administratorAssignmentRenewsWechatSnapshotButMultipleRolesDisabledAccountsAndUnapprovedChangesAreDenied() throws Exception {
        var user=create("OWNER",false);long id=user.path("id").asLong();String identity=identity();var bound=bind(user,identity);
        mvc.perform(put("/api/v1/admin/users/"+id+"/assignment").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("roles",List.of("REPAIR_SHOP"),"shopId",shopId)))).andExpect(status().isOk());
        me(token(bound),401);
        var shopLogin=post("/auth/wechat/login",null,Map.of("code",code(identity)),200).path("data");
        assertThat(shopLogin.path("user").path("roles").get(0).asString()).isEqualTo("REPAIR_SHOP");
        assertThat(shopLogin.path("user").path("shopId").asLong()).isEqualTo(shopId);me(token(shopLogin),200);
        assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=? AND revoked_at IS NULL",Long.class,id)).isEqualTo(1);
        // Changes outside the audited administrator operation must still fail the snapshot check.
        users.replaceRolesDelete(id);users.addRole(id,"OWNER");users.deleteShopAccount(id);users.ensureOwner(id,"合成账号");
        me(token(shopLogin),401);
        assertThat(post("/auth/wechat/login",null,Map.of("code",code(identity)),409).path("code").asString()).isEqualTo("WECHAT_IDENTITY_CONFLICT");
        var mixed=create("OWNER",false);long mixedId=mixed.path("id").asLong();
        mvc.perform(put("/api/v1/admin/users/"+mixedId+"/assignment").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("roles",List.of("OWNER","REPAIR_SHOP"),"shopId",shopId)))).andExpect(status().isOk());
        post("/auth/wechat/bindings",null,Map.of("code",code(identity()),"username",mixed.path("username").asString(),"password",PASSWORD,"confirmed",true),409);
        var disabled=create("OWNER",false);String disabledIdentity=identity();var disabledSession=bind(disabled,disabledIdentity);
        mvc.perform(patch("/api/v1/admin/users/"+disabled.path("id").asLong()+"/enabled").header("Authorization","Bearer "+admin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        me(token(disabledSession),401);post("/auth/wechat/login",null,Map.of("code",code(disabledIdentity)),401);
    }
    @Test void unknownIdentityFieldsNoConfirmationAndOversizedUtf8PasswordNeverExchangeCode() throws Exception {
        for(var body:List.of(Map.of("code","unused","role","OWNER"),Map.of("code","unused","openid","forged")))
            post("/auth/wechat/login",null,body,400);
        post("/auth/wechat/bindings",null,Map.of("code","unused","username","dev_owner","password",PASSWORD,"confirmed",false),400);
        post("/auth/wechat/bindings",null,Map.of("code","unused","username","dev_owner","password","字".repeat(30),"confirmed",true),400);
        verify(exchange,never()).exchange(anyString());
        mvc.perform(get("/api/v1/auth/wechat/login")).andExpect(status().isUnauthorized());
    }
    @Test void correctlySignedWechatTokenStillRequiresExistingBindingAndConfiguredApp() throws Exception {
        var user=create("OWNER",false);long id=user.path("id").asLong();var session=bind(user,identity());
        me(jwt.issueWechat(users.accessById(id).current(),UUID.randomUUID()).accessToken(),401);
        configuration.setAppId("different-synthetic-app");
        try { me(token(session),401); }
        finally { configuration.setAppId(APP_ID); }
    }
    @Test void disabledEntryAndRateLimitHaveNoFakeSuccessOrExtraProviderCalls() throws Exception {
        configuration.setEnabled(false);
        assertThat(post("/auth/wechat/login",null,Map.of("code","unused"),503).path("code").asString()).isEqualTo("WECHAT_UNAVAILABLE");
        verify(exchange,never()).exchange(anyString());configuration.setEnabled(true);
        db.update("INSERT INTO account_rate_limit(bucket_hash,window_started,requests) VALUES(?,now(),20)",AccountActivationService.digest("wechat-ip:127.0.0.1"));
        mvc.perform(postRequest("/auth/wechat/login",null,Map.of("code","unused"))).andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After","600"));
        verify(exchange,never()).exchange(anyString());
    }
    @Test void ownerInvitationUsesVerifiedCaseScopeAndNeverSelfClaimsByPhone() throws Exception {
        String staff=legacy("dev_customer_service",PASSWORD,"ADMIN"),identity=identity();
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO work_order(id,business_no,status,current_responsible_id,created_by,updated_by,owner_name,owner_phone,claim_no,vehicle_plate) VALUES(?,?,'PENDING_DISPATCH',?,?,?,?,?,?,?)",
            id,"S03-"+id.toString().substring(0,16),adminId,adminId,adminId,"合成车主","SYN-"+UUID.randomUUID().toString().substring(0,16),"SYN-CLAIM","SYN-PLATE");
        var body=Map.of("expectedVersion",0,"verified",true,"reason","合成案件归属已人工核实");
        var invitation=post("/work-orders/"+id+"/owner-wechat-invitation",staff,body,200).path("data");
        long userId=invitation.path("userId").asLong();
        var session=activate(invitation.path("invitationToken").asString(),identity,200).path("data");
        mvc.perform(get("/api/v1/work-orders/"+id).header("Authorization","Bearer "+token(session))).andExpect(status().isOk());
        UUID other=UUID.randomUUID();
        db.update("INSERT INTO work_order(id,business_no,status,current_responsible_id,created_by,updated_by,owner_phone,claim_no,vehicle_plate) VALUES(?,?,'PENDING_DISPATCH',?,?,?,'SYN-FOREIGN','SYN-FOREIGN','SYN-PLATE')",other,"S03-"+other.toString().substring(0,16),adminId,adminId,adminId);
        mvc.perform(get("/api/v1/work-orders/"+other).header("Authorization","Bearer "+token(session))).andExpect(status().isForbidden());
        post("/admin/users/"+userId+"/wechat-invitation",staff,Map.of(),403);
        var existing=post("/work-orders/"+id+"/owner-wechat-invitation",staff,
            Map.of("expectedVersion",db.queryForObject("SELECT version FROM work_order WHERE id=?",Long.class,id),"verified",true,"reason","已激活账号复用"),200).path("data");
        assertThat(existing.path("alreadyActive").asBoolean()).isTrue();assertThat(existing.path("invitationToken").isNull()).isTrue();
    }
    @Test void migrationUpgradesExistingSchemaAndProtectsUniqueBindingsAndHistory() throws Exception {
        String schema=SCHEMA+"_upgrade",url=System.getenv("TEST_DB_URL"),username=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        Flyway before=Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).target("32").load();before.migrate();
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,username,password);
        var isolated=new JdbcTemplate(source);
        isolated.update("INSERT INTO app_user(username,display_name,password_hash) VALUES('preserved','Synthetic preserved account','not-a-real-password')");
        Flyway after=Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(2);after.validate();flyway.validate();
        assertThat(isolated.queryForObject("SELECT count(*) FROM app_user WHERE username='preserved'",Long.class)).isEqualTo(1);
        var user=create("OWNER",false);bind(user,identity());long id=user.path("id").asLong();
        UUID binding=db.queryForObject("SELECT id FROM wechat_identity_binding WHERE user_id=?",UUID.class,id);
        assertThatThrownBy(()->db.update("UPDATE wechat_identity_binding SET role='REPAIR_SHOP' WHERE id=?",binding)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("DELETE FROM wechat_identity_binding WHERE id=?",binding)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("INSERT INTO wechat_identity_binding(id,app_id,openid,user_id,role) VALUES(?,?,?,?, 'OWNER')",UUID.randomUUID(),APP_ID,identity(),id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void firstWechatLoginCreatesOwnerAtomicallyWithoutPasswordInvitationOrAutomaticCaseClaim() throws Exception {
        String identity=identity();long before=users.selectCount(null);
        var result=post("/auth/wechat/login",null,Map.of("code",code(identity)),200).path("data");
        long id=result.path("user").path("id").asLong();me(token(result),200);
        assertThat(users.selectCount(null)).isEqualTo(before+1);
        assertThat(result.path("user").path("roles").toString()).isEqualTo("[\"OWNER\"]");
        assertThat(result.path("user").path("enabled").asBoolean()).isTrue();assertThat(result.path("user").path("activated").asBoolean()).isTrue();
        assertThat(result.toString()).doesNotContain(identity,"openid","session_key","password");
        assertThat(db.queryForObject("SELECT invitation_managed FROM owner_profile WHERE user_id=?",Boolean.class,id)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM work_order WHERE owner_user_id=?",Long.class,id)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM account_invitation WHERE user_id=?",Long.class,id)).isZero();
        assertThat(encoder.matches(PASSWORD,users.accessById(id).passwordHash())).isFalse();
        mvc.perform(get("/api/v1/work-orders?page=1&size=20").header("Authorization","Bearer "+token(result)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        var next=post("/auth/wechat/login",null,Map.of("code",code(identity)),200).path("data");
        assertThat(next.path("user").path("id").asLong()).isEqualTo(id);assertThat(users.selectCount(null)).isEqualTo(before+1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE action='USER_CREATE' AND object_id=?",Long.class,Long.toString(id))).isEqualTo(1);
    }
    @Test void simultaneousFirstLoginForTheSameWechatCreatesOnlyOneOwnerAndBinding() throws Exception {
        String identity=identity();long before=users.selectCount(null);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Long>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{
                String code=code(identity);barrier.await(10,TimeUnit.SECONDS);
                return post("/auth/wechat/login",null,Map.of("code",code),200).path("data").path("user").path("id").asLong();
            }));
            long id=results.get(0).get(30,TimeUnit.SECONDS);
            assertThat(results.get(1).get(30,TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,id)).isEqualTo(1);
        }
        assertThat(users.selectCount(null)).isEqualTo(before+1);
    }
    @Test void firstLoginOwnerCanBeAssignedToShopByAdministratorAndRevokedWechatCannotReregister() throws Exception {
        String identity=identity();var first=post("/auth/wechat/login",null,Map.of("code",code(identity)),200).path("data");
        long id=first.path("user").path("id").asLong();
        var assignment=Map.of("roles",List.of("REPAIR_SHOP"),"shopId",shopId);
        mvc.perform(put("/api/v1/admin/users/"+id+"/assignment").header("Authorization","Bearer "+token(first))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(assignment))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/users/"+id+"/assignment").header("Authorization","Bearer "+admin)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(assignment))).andExpect(status().isOk());
        me(token(first),401);
        var shop=post("/auth/wechat/login",null,Map.of("code",code(identity)),200).path("data");
        assertThat(shop.path("user").path("id").asLong()).isEqualTo(id);
        assertThat(shop.path("user").path("roles").get(0).asString()).isEqualTo("REPAIR_SHOP");
        assertThat(shop.path("user").path("shopId").asLong()).isEqualTo(shopId);me(token(shop),200);
        UUID binding=db.queryForObject("SELECT id FROM wechat_identity_binding WHERE user_id=? AND revoked_at IS NULL",UUID.class,id);
        post("/admin/users/"+id+"/wechat-bindings/"+binding+"/revoke",admin,Map.of("expectedVersion",0,"verified",true,"reason","合成恢复核实"),200);
        me(token(shop),401);long before=users.selectCount(null);
        assertThat(post("/auth/wechat/login",null,Map.of("code",code(identity)),409).path("code").asString()).isEqualTo("WECHAT_IDENTITY_REVOKED");
        assertThat(users.selectCount(null)).isEqualTo(before);
    }
}
