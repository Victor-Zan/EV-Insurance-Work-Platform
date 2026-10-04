package com.evinsurance.platform.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.JwtService;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;

@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class IdentityPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SECRET=Base64.getEncoder().encodeToString(randomKey());
    private static final String SCHEMA="auth_it_" + UUID.randomUUID().toString().replace("-","");
    private static byte[] randomKey() { byte[] bytes=new byte[32]; new java.security.SecureRandom().nextBytes(bytes); return bytes; }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("TEST_DB_URL");
        if (url==null || url.isBlank()) throw new IllegalStateException("postgres-it requires a dedicated TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD");
        registry.add("spring.datasource.url",() -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username",() -> System.getenv("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("TEST_DB_PASSWORD"));
        registry.add("spring.flyway.schemas",() -> SCHEMA);
        registry.add("spring.flyway.default-schema",() -> SCHEMA);
        registry.add("JWT_SECRET_BASE64",() -> SECRET); registry.add("JWT_TTL_SECONDS",() -> "1800");
        for (String role: List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")) registry.add("DEV_"+role+"_PASSWORD",() -> PASSWORD);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db; @Autowired Flyway flyway; @Autowired PasswordEncoder encoder;
    private String admin;
    @BeforeEach void authenticateAdmin() throws Exception { admin=login("dev_admin",PASSWORD,"ADMIN").path("data").path("accessToken").asText(); }
    private JsonNode login(String username,String password,String portal) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password",password,"portal",portal))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode create(String role,Long shopId) throws Exception {
        Map<String,Object> request=new HashMap<>(); request.put("username","test_"+UUID.randomUUID().toString().replace("-",""));
        request.put("displayName","集成测试账号"); request.put("password",PASSWORD); request.put("roles",List.of(role)); if (shopId!=null) request.put("shopId",shopId);
        return json.readTree(mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void allFourAccountsLoginWithHashedCredentialsAndCorrectPortal() throws Exception {
        for (String role: List.of("admin","customer_service","repair_shop","owner")) {
            var result=login("dev_"+role,PASSWORD,List.of("admin","customer_service").contains(role) ? "ADMIN" : "H5");
            assertThat(result.path("data").path("accessToken").asText()).isNotBlank();
            assertThat(result.path("data").path("user").has("passwordHash")).isFalse();
            String hash=db.queryForObject("select password_hash from app_user where username=?",String.class,"dev_"+role);
            assertThat(hash).isNotEqualTo(PASSWORD); assertThat(encoder.matches(PASSWORD,hash)).isTrue();
        }
    }
    @Test void wrongAndUnknownCredentialsReturnIdenticalFailureAndAreAudited() throws Exception {
        String wrong=UUID.randomUUID().toString(); String message=null;
        for (String account: List.of("dev_owner","absent_"+UUID.randomUUID())) {
            String body=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",account,"password",wrong,"portal","H5"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse().getContentAsString();
            String actual=json.readTree(body).path("message").asText(); if (message==null) message=actual; else assertThat(actual).isEqualTo(message);
        }
        assertThat(db.queryForObject("select count(*) from audit_log where action='LOGIN_FAILURE'",Long.class)).isGreaterThanOrEqualTo(2);
    }
    @Test void disabledAccountCannotLoginAndOldTokenStaysInvalidAfterEnable() throws Exception {
        var user=create("CUSTOMER_SERVICE",null); long id=user.path("id").asLong();
        String token=login(user.path("username").asText(),PASSWORD,"ADMIN").path("data").path("accessToken").asText();
        enabled(id,false);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",user.path("username").asText(),"password",PASSWORD,"portal","ADMIN"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        enabled(id,true);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        login(user.path("username").asText(),PASSWORD,"ADMIN");
    }
    private void enabled(long id,boolean state) throws Exception {
        mvc.perform(patch("/api/v1/admin/users/"+id+"/enabled").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("enabled",state)))).andExpect(status().isOk());
    }
    @Test void passwordResetInvalidatesTokenAndDoesNotReturnSecret() throws Exception {
        var user=create("OWNER",null); long id=user.path("id").asLong();
        String token=login(user.path("username").asText(),PASSWORD,"H5").path("data").path("accessToken").asText(); String replacement=UUID.randomUUID().toString();
        String body=mvc.perform(post("/api/v1/admin/users/"+id+"/password-reset").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("password",replacement)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(replacement); mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        login(user.path("username").asText(),replacement,"H5");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",user.path("username").asText(),"password",PASSWORD,"portal","H5"))))
            .andExpect(status().isUnauthorized());
        String logs=json.writeValueAsString(db.queryForList("select * from audit_log")); assertThat(logs).doesNotContain(PASSWORD,replacement,token,SECRET);
        assertThat(logs).contains("PASSWORD_RESET","USER_CREATE").doesNotContain("$2a$","$2b$");
    }
    @Test void anonymousInvalidExpiredAndMissingClaimTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        long id=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
        var key=new SecretKeySpec(Base64.getDecoder().decode(SECRET),"HmacSHA256");
        JwtEncoder signer=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var expired=JwtClaimsSet.builder().issuer("ev-insurance").subject(Long.toString(id)).issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().minusSeconds(1)).claim("av",0).build();
        String token=signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),expired)).getTokenValue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        var missing=JwtClaimsSet.builder().issuer("ev-insurance").subject(Long.toString(id)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        token=signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),missing)).getTokenValue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void nonAdminsAreDeniedEveryManagementFamilyAndWrongPortal() throws Exception {
        for (String role: List.of("customer_service","repair_shop","owner")) {
            String portal=role.equals("customer_service") ? "ADMIN" : "H5";
            String token=login("dev_"+role,PASSWORD,portal).path("data").path("accessToken").asText();
            for (String endpoint: List.of("users","roles","regions","shops","shops/1/service-regions","audit-logs")) {
                mvc.perform(get("/api/v1/admin/"+endpoint).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            }
            mvc.perform(get("/api/v1/"+(portal.equals("H5") ? "admin" : "h5")+"/session").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username","dev_"+role,"password",PASSWORD,"portal",portal.equals("H5") ? "ADMIN" : "H5"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PORTAL_MISMATCH"));
            mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
    }
    @Test void organizationTreeManyToManyAssignmentAndAuditsWork() throws Exception {
        long province=createRegion(null,1), city=createRegion(province,2), district=createRegion(city,3);
        var shopReq=Map.of("name","集成测试网点","code","TEST-"+UUID.randomUUID().toString().substring(0,8),"enabled",true,"contactPhone","TEST-CONTACT");
        long shop=json.readTree(mvc.perform(post("/api/v1/admin/shops").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(shopReq))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(put("/api/v1/admin/shops/"+shop+"/service-regions").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("regionIds",List.of(city,district))))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/shops/"+shop+"/service-regions").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        var first=create("REPAIR_SHOP",shop); var second=create("REPAIR_SHOP",shop);
        assertThat(first.path("shopId").asLong()).isEqualTo(shop); assertThat(second.path("shopId").asLong()).isEqualTo(shop);
        String old=login(first.path("username").asText(),PASSWORD,"H5").path("data").path("accessToken").asText();
        mvc.perform(put("/api/v1/admin/users/"+first.path("id").asLong()+"/assignment").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("roles",List.of("OWNER"))))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("select count(*) from owner_profile where user_id=?",Long.class,first.path("id").asLong())).isEqualTo(1);
        mvc.perform(post("/api/v1/admin/regions").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("parentId",province,"level",3,"name","无效层级","code","BAD-LEVEL","enabled",true,"sortOrder",0))))
            .andExpect(status().isBadRequest());
        String logs=json.writeValueAsString(db.queryForList("select * from audit_log")); assertThat(logs).contains("SERVICE_REGION_CHANGE","SHOP_ACCOUNT_CHANGE","ROLE_CHANGE").doesNotContain("TEST-CONTACT",PASSWORD,old,SECRET);
        assertThatThrownBy(() -> db.update("update audit_log set summary='changed' where id=(select min(id) from audit_log)")).hasMessageContaining("append only");
    }
    private long createRegion(Long parent,int level) throws Exception {
        Map<String,Object> body=new HashMap<>(); if (parent!=null) body.put("parentId",parent);
        body.put("level",level);body.put("name","集成测试区域");body.put("code","T-"+UUID.randomUUID().toString().substring(0,8));body.put("enabled",true);body.put("sortOrder",0);
        return json.readTree(mvc.perform(post("/api/v1/admin/regions").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    @Test void unifiedErrorsPaginationOpenApiAndFreshFlywayAreVerified() throws Exception {
        flyway.validate();
        assertThat(Arrays.stream(flyway.info().applied()).filter(info -> info.getVersion()!=null)
            .map(info -> info.getVersion().toString()).toList()).containsExactly("1","2","2.1","3","4");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/admin/users?size=101").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/users/9999999/enabled").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled",false))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/admin/audit-logs?size=1").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1)).andExpect(jsonPath("$.data.records[0].traceId").isNotEmpty());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists()).andExpect(jsonPath("$.paths['/api/v1/admin/users'].get.responses['401']").exists());
        mvc.perform(get("/api/v1/admin/nonexistent").header("Authorization","Bearer "+admin))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void normalProfileNeverSeedsUsersAndDevelopmentHashesCanChangeWithoutChecksumDrift() {
        String url=System.getenv("TEST_DB_URL"), username=System.getenv("TEST_DB_USERNAME"), password=System.getenv("TEST_DB_PASSWORD");
        String normalSchema=SCHEMA+"_normal";
        Flyway normal=Flyway.configure().dataSource(url,username,password).schemas(normalSchema).defaultSchema(normalSchema)
            .locations("classpath:db/migration").load();
        normal.migrate(); normal.validate();
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+(url.contains("?") ? "&" : "?")+"currentSchema="+normalSchema,username,password);
        var template=new JdbcTemplate(source);
        assertThat(template.queryForObject("select count(*) from app_user",Long.class)).isZero();
        assertThat(template.queryForObject("select count(*) from app_role",Long.class)).isEqualTo(4);
        String newHash=encoder.encode(UUID.randomUUID().toString());
        Flyway restarted=Flyway.configure().dataSource(url,username,password).schemas(SCHEMA).defaultSchema(SCHEMA)
            .locations("classpath:db/migration","classpath:db/dev").placeholders(Map.of("devAdminHash",newHash,
                "devCustomerServiceHash",newHash,"devRepairShopHash",newHash,"devOwnerHash",newHash)).load();
        restarted.validate(); assertThat(restarted.migrate().migrationsExecuted).isZero();
    }
    @Test void duplicateAccountsMixedPortalRolesAndOversizedUtf8PasswordsAreSafelyRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username","dev_admin","displayName","测试","password",PASSWORD,"roles",List.of("ADMIN")))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username","mixed_test","displayName","测试","password",PASSWORD,"roles",List.of("ADMIN","OWNER")))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username","dev_owner","password","测".repeat(30),"portal","H5"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test void administratorUpdatesRegionsShopsAndFailedRelationReplacementRollsBack() throws Exception {
        long province=createRegion(null,1), city=createRegion(province,2);
        String code="UPDATED-"+UUID.randomUUID().toString().substring(0,8);
        mvc.perform(put("/api/v1/admin/regions/"+province).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name","更新测试区域","code",code,"level",1,"enabled",false,"sortOrder",7))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.sortOrder").value(7));
        String shopCode="UPDATE-SHOP-"+UUID.randomUUID().toString().substring(0,8);
        long shop=json.readTree(mvc.perform(post("/api/v1/admin/shops").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name","更新测试网点","code",shopCode,"enabled",true,"contactPhone","MOCK-CONTACT"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(put("/api/v1/admin/shops/"+shop).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name","更新后测试网点","code",shopCode,"enabled",false))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.contactPhone").doesNotExist());
        mvc.perform(put("/api/v1/admin/shops/"+shop+"/service-regions").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("regionIds",List.of(city))))).andExpect(status().isOk());
        Long auditCount=db.queryForObject("select count(*) from audit_log",Long.class);
        mvc.perform(put("/api/v1/admin/shops/"+shop+"/service-regions").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("regionIds",List.of(9999999L))))).andExpect(status().isNotFound());
        assertThat(db.queryForList("select region_id from shop_service_region where shop_id=?",Long.class,shop)).containsExactly(city);
        assertThat(db.queryForObject("select count(*) from audit_log",Long.class)).isEqualTo(auditCount);
        String logs=json.writeValueAsString(db.queryForList("select * from audit_log"));
        assertThat(logs).contains("REGION_UPDATE","SHOP_UPDATE").doesNotContain("MOCK-CONTACT","$2a$",PASSWORD,SECRET);
    }
}
