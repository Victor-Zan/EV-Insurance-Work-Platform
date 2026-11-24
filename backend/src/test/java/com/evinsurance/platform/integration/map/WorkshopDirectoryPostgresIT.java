package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;

@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("dev")
class WorkshopDirectoryPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SCHEMA="directory_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String SECRET=secret();
    private static volatile boolean simulationEnabled=false;
    private static String secret(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        String url=System.getenv("TEST_DB_URL");if(url==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        r.add("MAP_TEST_SESSION_ENABLED",()->"true");r.add("PUBLIC_WORKSHOP_ENABLED",()->"true");
        r.add("MAP_SIMULATION_DESTINATIONS_ENABLED",()->simulationEnabled);
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))r.add(name,()->System.getenv("TEST_"+name));
        r.add("JWT_SECRET_BASE64",()->SECRET);r.add("JWT_TTL_SECONDS",()->"1800");r.add("OCR_WORKER_ENABLED",()->"false");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
    }
    @MockitoBean MapProvider provider;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;@Autowired MapRecommendationService recommendations;@Autowired MapLocationService mapLocations;
    private long sourceShop;
    private String admin,cs,shop,owner;private long province,city,district,model,actor;private UUID caseId;private final List<Long> shops=new ArrayList<>();private AtomicInteger routes;
    @BeforeEach void setup() throws Exception {
        simulationEnabled=false;
        db.update("update repair_shop set name='开发模拟维修网点' where code='DEV-SHOP'");
        db.update("update repair_shop set code='HIST-SIM-'||id,name='历史合成模拟目的地',enabled=false where code like 'DEV-SIM-%'");
        db.update("delete from shop_service_region where shop_id=(select id from repair_shop where code='DEV-SHOP') and region_id in(select id from region where code like '44%')");
        ((Map<?,?>)ReflectionTestUtils.getField(mapLocations,"sessions")).clear();
        reset(provider);when(provider.providerId()).thenReturn("AMAP_WEB_V1");routes=new AtomicInteger();
        when(provider.resolve(any())).thenAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);return List.of(new MapProvider.AddressCandidate("合成停车位置",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"门牌号","REQUIRES_CONFIRMATION",false));});
        when(provider.locate(any())).thenReturn(new MapProvider.AddressCandidate("合成位置",new MapProvider.Point(23.1,113.1),"广东省","深圳市","龙岗区","440307","REVERSE","REQUIRES_CONFIRMATION",false));
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{routes.incrementAndGet();var from=(MapProvider.Point)inv.getArgument(0);var to=(MapProvider.Point)inv.getArgument(1);assertThat(from).isEqualTo(new MapProvider.Point(23,113));return new MapProvider.DrivingRoute(to.longitude()<113.15?9000:1000,600L,32,0,false);});
        db.update("delete from account_rate_limit");
        admin=login("dev_admin","ADMIN");cs=login("dev_customer_service","ADMIN");shop=login("dev_repair_shop","H5");owner=login("dev_owner","H5");actor=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
        db.update("update repair_shop set enabled=false where code like 'MAP-TEST-%'");
        province=db.queryForObject("insert into region(code,name,level) values('440000','广东省',1) on conflict(code) do update set enabled=true returning id",Long.class);
        city=db.queryForObject("insert into region(code,name,level,parent_id) values('440300','深圳市',2,?) on conflict(code) do update set enabled=true returning id",Long.class,province);
        district=db.queryForObject("insert into region(code,name,level,parent_id) values('440307','龙岗区',3,?) on conflict(code) do update set enabled=true returning id",Long.class,city);
        String suffix=UUID.randomUUID().toString();long brand=db.queryForObject("insert into price_brand(code,name,created_by,updated_by) values(?,?,?,?) returning id",Long.class,suffix,"合成品牌"+suffix,actor,actor);
        model=db.queryForObject("insert into price_model(brand_id,code,name,created_by,updated_by) values(?,?,?,?,?) returning id",Long.class,brand,suffix,"合成车型"+suffix,actor,actor);
        Map<String,Object> draft=new HashMap<>(Map.of("insuranceCompany","合成测试保司","claimNo",suffix,"ownerName","合成车主","ownerPhone","13800000000","vehicleBrand","合成品牌"+suffix,"vehicleModel","合成车型"+suffix,"vehicleVin",suffix,"accidentAt",Instant.now().toString(),"accidentRegionId",district,"accidentDescription","合成测试事故"));
        draft.put("surveyorName","合成查勘员");draft.put("surveyorPhone","13800000001");
        caseId=UUID.fromString(postJson("/api/v1/work-orders/drafts",cs,draft,null,200).path("data").path("id").asString());
        byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5S8AAAAASUVORK5CYII=");
        mvc.perform(multipart("/api/v1/materials/cases/{id}",caseId).file(new org.springframework.mock.web.MockMultipartFile("file","synthetic-scene.png","image/png",image)).param("category","SCENE_PHOTO").header("Authorization","Bearer "+cs).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());
        var version=db.queryForObject("select version from work_order where id=?",Long.class,caseId);
        mvc.perform(put("/api/v1/work-orders/"+caseId+"/dispatch-location").header("Authorization","Bearer "+cs).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",version,"regionId",district,"address","合成独立停车地址","confirmed",true)))).andExpect(status().isConflict()); // Drafts must not bypass the existing state gate.
        postJson("/api/v1/work-orders/"+caseId+"/submit",cs,Map.of("confirmPossibleDuplicate",true,"duplicateReason","合成独立测试"),UUID.randomUUID().toString(),200);
        version=db.queryForObject("select version from work_order where id=?",Long.class,caseId);
        mvc.perform(put("/api/v1/work-orders/"+caseId+"/dispatch-location").header("Authorization","Bearer "+cs).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",version,"regionId",district,"address","合成独立停车地址","confirmed",true)))).andExpect(status().isOk());
        assertThat(db.queryForObject("select accident_region_id from work_order where id=?",Long.class,caseId)).isEqualTo(district);
        assertThat(db.queryForObject("select accident_address from work_order where id=?",String.class,caseId)).isNull();
        shops.clear();for(int i=0;i<3;i++){
            long id=postJson("/api/v1/admin/shops",admin,Map.of("code","MAP-TEST-"+UUID.randomUUID().toString().replace("-","").substring(0,20),"name","合成网点"+i,"enabled",true,"address","合成测试街道"+i+"号"),null,200).path("data").path("id").asLong();shops.add(id);
            mvc.perform(put("/api/v1/admin/shops/"+id+"/service-regions").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("regionIds",List.of(district))))).andExpect(status().isOk());
            capability(id,List.of(model));
            postJson("/api/v1/maps/admin/shops/"+id+"/location",admin,Map.of("districtId",district,"longitude",java.math.BigDecimal.valueOf(113.1).add(java.math.BigDecimal.valueOf(i).movePointLeft(1)).toPlainString(),"latitude",23.1,"gcj02Confirmed",true,"synthetic",false),null,200);
        }
        for(long existing:shops)db.update("update repair_shop set enabled=false where id=?",existing);
        sourceShop=db.queryForObject("insert into repair_shop(code,name,address,contact_phone,enabled,created_by,updated_by) values(?,?,?,'075500000001',false,?,?) returning id",Long.class,"AMAP-"+UUID.randomUUID().toString().replace("-", "").substring(0,20),"【待核验】合成电动车维修"+suffix.substring(0,8),"广东省深圳市龙岗区合成测试街10号",actor,actor);
    }
    private String login(String user,String portal) throws Exception{return postJson("/api/v1/auth/login",null,Map.of("username",user,"password",PASSWORD,"portal",portal),null,200).path("data").path("accessToken").asString();}
    private JsonNode postJson(String path,String token,Object body,String key,int statusCode) throws Exception {var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);var response=mvc.perform(request).andReturn().getResponse();var payload=json.readTree(response.getContentAsString());assertThat(response.getStatus()).as(path+" code="+payload.path("code").asString()).isEqualTo(statusCode);return payload;}
    private void capability(long shop,List<Long> models) throws Exception{mvc.perform(put("/api/v1/maps/admin/shops/"+shop+"/capabilities").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("modelIds",models)))).andExpect(status().isOk());}
    private UUID position() throws Exception {var choices=postJson("/api/v1/maps/locations/resolve",cs,Map.of("districtId",district,"detail","合成停车位置10号"),null,200).path("data");UUID token=UUID.fromString(choices.get(0).path("token").asString());postJson("/api/v1/maps/locations/"+token+"/confirm",cs,Map.of(),null,200);return token;}
    private JsonNode query(UUID token,String key,Long cityId,int expected) throws Exception {var body=new HashMap<String,Object>();body.put("locationToken",token);body.put("shopCityId",cityId);return postJson("/api/v1/maps/cases/"+caseId+"/recommendations",cs,body,key,expected);}

    private String path(){return "/api/v1/maps/cases/"+caseId+"/workshop-directory";}
    private JsonNode plan()throws Exception{
        return postJson("/api/v1/maps/cases/"+caseId+"/automatic-dispatch",cs,Map.of("address","深圳市龙岗区合成停车地址10号","requestKey",UUID.randomUUID()),null,200).path("data");
    }
    private Map<String,Object> choice()throws Exception{
        var p=plan();assertThat(p.path("location").isMissingNode()).isFalse();
        var target=postJson(path()+"/prepare",cs,Map.of("sourceShopId",sourceShop),null,200).path("data").path("choices").get(0);
        return new HashMap<>(Map.of("planId",p.path("id").asString(),"sourceShopId",sourceShop,"shopLocationToken",target.path("token").asString(),"verified",true,"confirmPossibleDuplicate",true,"duplicateReason","合成独立验证"));
    }
    private long assignments(){return db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,caseId);}
    @Test void disabledDirectoryCandidatesAreSearchableWithoutChangingEligibilityOrCallingProvider()throws Exception{
        clearInvocations(provider);
        var response=mvc.perform(get(path()).param("query","合成电动车维修").param("size","1").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse();
        var page=json.readTree(response.getContentAsString()).path("data");assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);assertThat(page.path("records").size()).isEqualTo(1);
        mvc.perform(get(path()).param("query","%").header("Authorization","Bearer "+cs)).andExpect(status().isOk());
        mvc.perform(get(path()).param("size","101").header("Authorization","Bearer "+cs)).andExpect(status().isBadRequest());
        for(String denied:List.of(admin,shop,owner))mvc.perform(get(path()).header("Authorization","Bearer "+denied)).andExpect(status().isForbidden());
        verifyNoInteractions(provider);assertThat(assignments()).isZero();
        assertThat(db.queryForObject("select enabled from repair_shop where id=?",Boolean.class,sourceShop)).isFalse();
        assertThat(db.queryForObject("select count(*) from shop_service_region where shop_id=?",Long.class,sourceShop)).isZero();
    }
    @Test void explicitConfirmationDispatchesOnceWithoutEnablingOriginalDirectoryShop()throws Exception{
        var body=choice();String key=UUID.randomUUID().toString();
        var done=postJson(path()+"/dispatch",cs,body,key,200).path("data");long target=done.path("shopId").asLong();
        assertThat(done.path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(target).isNotEqualTo(sourceShop);
        postJson(path()+"/dispatch",cs,body,key,200);assertThat(assignments()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from case_workshop_binding where work_order_id=?",Long.class,caseId)).isEqualTo(1);
        assertThat(db.queryForObject("select enabled from repair_shop where id=?",Boolean.class,sourceShop)).isFalse();
        assertThat(db.queryForObject("select count(*) from shop_service_region where shop_id=?",Long.class,sourceShop)).isZero();
        assertThat(db.queryForObject("select destination_shop_id from work_order_assignment where work_order_id=?",Long.class,caseId)).isEqualTo(target);
        body.put("sourceShopId",sourceShop+99999);postJson(path()+"/dispatch",cs,body,key,409);assertThat(assignments()).isEqualTo(1);
    }
    @Test void unverifiedOrUnknownLocationCannotRegisterOrDispatch()throws Exception{
        var body=choice();body.put("verified",false);postJson(path()+"/dispatch",cs,body,UUID.randomUUID().toString(),400);
        body.put("verified",true);body.put("shopLocationToken",UUID.randomUUID());postJson(path()+"/dispatch",cs,body,UUID.randomUUID().toString(),409);
        assertThat(assignments()).isZero();assertThat(db.queryForObject("select count(*) from case_workshop_binding where work_order_id=?",Long.class,caseId)).isZero();
    }
    @Test void directoryReusesAnAlreadyVerifiedShopOfThisCaseInsteadOfDuplicatingIt()throws Exception{
        var body=choice();String name=db.queryForObject("select name from repair_shop where id=?",String.class,sourceShop);
        long prior=db.queryForObject("insert into repair_shop(code,name,address,enabled,created_by,updated_by) values(?,?,?,true,?,?) returning id",Long.class,"TMP-"+UUID.randomUUID().toString().substring(0,20),"【临时／仅本案】"+WorkshopDirectoryService.displayName(name),"合成停车位置",actor,actor);
        db.update("insert into case_workshop_binding(shop_id,work_order_id,accident_region_id,source_kind,identity_hash,created_by) values(?,?,?,'PUBLIC_POI_VERIFIED',?,?)",prior,caseId,district,"a".repeat(64),actor);
        db.update("insert into shop_service_region(shop_id,region_id,created_by) values(?,?,?)",prior,district,actor);
        var done=postJson(path()+"/dispatch",cs,body,UUID.randomUUID().toString(),200).path("data");
        assertThat(done.path("shopId").asLong()).isEqualTo(prior);assertThat(db.queryForObject("select count(*) from case_workshop_binding where work_order_id=?",Long.class,caseId)).isEqualTo(1);
    }
    @Test void changedSourceOrCaseRollsBackAllFinalWrites()throws Exception{
        var body=choice();var before=db.queryForMap("select version,parking_address from work_order where id=?",caseId);
        db.update("update repair_shop set address='广东省深圳市龙岗区更正街20号' where id=?",sourceShop);
        postJson(path()+"/dispatch",cs,body,UUID.randomUUID().toString(),409);
        assertThat(db.queryForMap("select version,parking_address from work_order where id=?",caseId)).isEqualTo(before);
        assertThat(assignments()).isZero();assertThat(db.queryForObject("select count(*) from case_workshop_binding where work_order_id=?",Long.class,caseId)).isZero();
    }
    @Test void missingAccidentDistrictAndFrozenCasesRemainBlocked()throws Exception{
        var body=choice();db.update("update work_order set accident_region_id=null where id=?",caseId);
        postJson(path()+"/dispatch",cs,body,UUID.randomUUID().toString(),409);assertThat(assignments()).isZero();
        db.update("update work_order set deletion_pending=true where id=?",caseId);
        postJson(path()+"/prepare",cs,Map.of("sourceShopId",sourceShop),null,409);assertThat(assignments()).isZero();
    }
}

