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
class MapRecommendationPostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SCHEMA="amap_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String SECRET=secret();
    private static volatile boolean simulationEnabled=false;
    private static String secret(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        String url=System.getenv("TEST_DB_URL");if(url==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        r.add("MAP_TEST_SESSION_ENABLED",()->"true");
        r.add("MAP_SIMULATION_DESTINATIONS_ENABLED",()->simulationEnabled);
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))r.add(name,()->System.getenv("TEST_"+name));
        r.add("JWT_SECRET_BASE64",()->SECRET);r.add("JWT_TTL_SECONDS",()->"1800");r.add("OCR_WORKER_ENABLED",()->"false");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
    }
    @MockitoBean MapProvider provider;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;@Autowired MapRecommendationService recommendations;@Autowired MapLocationService mapLocations;
    private String admin,cs,shop,owner;private long province,city,district,model,actor;private UUID caseId;private final List<Long> shops=new ArrayList<>();private AtomicInteger routes;
    @BeforeEach void setup() throws Exception {
        simulationEnabled=false;
        db.update("update repair_shop set name='开发模拟维修网点' where code='DEV-SHOP'");
        db.update("update repair_shop set code='HIST-SIM-'||id,name='历史合成模拟目的地',enabled=false where code like 'DEV-SIM-%'");
        db.update("delete from shop_service_region where shop_id=(select id from repair_shop where code='DEV-SHOP') and region_id in(select id from region where code like '44%')");
        ((Map<?,?>)ReflectionTestUtils.getField(mapLocations,"sessions")).clear();
        reset(provider);when(provider.providerId()).thenReturn("AMAP_WEB_V1");routes=new AtomicInteger();
        when(provider.resolve(any())).thenAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);return List.of(new MapProvider.AddressCandidate("合成停车位置",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"门牌号","REQUIRES_CONFIRMATION",false));});
        when(provider.locate(any())).thenReturn(new MapProvider.AddressCandidate("合成位置",new MapProvider.Point(23.1,113.1),"广东省","广州市","天河区","440106","REVERSE","REQUIRES_CONFIRMATION",false));
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{routes.incrementAndGet();var from=(MapProvider.Point)inv.getArgument(0);var to=(MapProvider.Point)inv.getArgument(1);assertThat(from).isEqualTo(new MapProvider.Point(23,113));return new MapProvider.DrivingRoute(to.longitude()<113.15?9000:1000,600L,32,0,false);});
        db.update("delete from account_rate_limit");
        admin=login("dev_admin","ADMIN");cs=login("dev_customer_service","ADMIN");shop=login("dev_repair_shop","H5");owner=login("dev_owner","H5");actor=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
        db.update("update repair_shop set enabled=false where code like 'MAP-TEST-%'");
        province=db.queryForObject("insert into region(code,name,level) values('440000','广东省',1) on conflict(code) do update set enabled=true returning id",Long.class);
        city=db.queryForObject("insert into region(code,name,level,parent_id) values('440100','广州市',2,?) on conflict(code) do update set enabled=true returning id",Long.class,province);
        district=db.queryForObject("insert into region(code,name,level,parent_id) values('440106','天河区',3,?) on conflict(code) do update set enabled=true returning id",Long.class,city);
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
    }
    private String login(String user,String portal) throws Exception{return postJson("/api/v1/auth/login",null,Map.of("username",user,"password",PASSWORD,"portal",portal),null,200).path("data").path("accessToken").asString();}
    private JsonNode postJson(String path,String token,Object body,String key,int statusCode) throws Exception {var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);var response=mvc.perform(request).andReturn().getResponse();var payload=json.readTree(response.getContentAsString());assertThat(response.getStatus()).as(path+" code="+payload.path("code").asString()).isEqualTo(statusCode);return payload;}
    private void capability(long shop,List<Long> models) throws Exception{mvc.perform(put("/api/v1/maps/admin/shops/"+shop+"/capabilities").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("modelIds",models)))).andExpect(status().isOk());}
    private UUID position() throws Exception {var choices=postJson("/api/v1/maps/locations/resolve",cs,Map.of("districtId",district,"detail","合成停车位置10号"),null,200).path("data");UUID token=UUID.fromString(choices.get(0).path("token").asString());postJson("/api/v1/maps/locations/"+token+"/confirm",cs,Map.of(),null,200);return token;}
    private JsonNode query(UUID token,String key,Long cityId,int expected) throws Exception {var body=new HashMap<String,Object>();body.put("locationToken",token);body.put("shopCityId",cityId);return postJson("/api/v1/maps/cases/"+caseId+"/recommendations",cs,body,key,expected);}

    @Autowired AutomaticDispatchService automatic;
    private JsonNode automatic(Map<String,Object> input)throws Exception{return postJson("/api/v1/maps/cases/"+caseId+"/automatic-dispatch",cs,input,null,200).path("data");}
    private String automaticPath(String id){return "/api/v1/maps/cases/"+caseId+"/automatic-dispatch/"+id;}
    @Test void automaticPlanDefersWritesReusesRequestsAndDispatchesManualChoiceOnce()throws Exception{
        db.update("update work_order set accident_address='广州市天河区合成测试街10号',accident_region_id=?,parking_address=null,parking_region_id=null,dispatch_address_confirmed=false where id=?",city,caseId);
        var before=db.queryForMap("select version,accident_region_id,accident_address,parking_address,dispatch_address_confirmed from work_order where id=?",caseId);
        var input=Map.<String,Object>of("requestKey",UUID.randomUUID());var result=automatic(input);
        assertThat(result.path("status").asString()).isEqualTo("READY");assertThat(result.path("selectedShopId").asLong()).isEqualTo(shops.get(1));assertThat(routes.get()).isEqualTo(3);
        assertThat(db.queryForMap("select version,accident_region_id,accident_address,parking_address,dispatch_address_confirmed from work_order where id=?",caseId)).isEqualTo(before);
        assertThat(automatic(Map.of("requestKey",UUID.randomUUID())).path("id").asString()).isEqualTo(result.path("id").asString());assertThat(routes.get()).isEqualTo(3);verify(provider,times(1)).resolve(any());
        String id=result.path("id").asString(),key=UUID.randomUUID().toString();var body=Map.of("shopId",shops.get(0),"confirmPossibleDuplicate",true,"duplicateReason","合成自动派单测试");
        postJson("/api/v1/maps/recommendations/"+result.path("recommendation").path("queryId").asString()+"/dispatch",cs,body,UUID.randomUUID().toString(),409);
        var done=postJson(automaticPath(id)+"/dispatch",cs,body,key,200).path("data");assertThat(done.path("shopId").asLong()).isEqualTo(shops.get(0));assertThat(done.path("dispatchAddressConfirmed").asBoolean()).isTrue();assertThat(done.path("accidentRegionId").asLong()).isEqualTo(district);
        assertThat(done.path("accidentAddress").asString()).isEqualTo("广州市天河区合成测试街10号");
        postJson(automaticPath(id)+"/dispatch",cs,body,key,200);assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,caseId)).isEqualTo(1);
        assertThat(routes.get()).isEqualTo(3);flyway.validate();
    }
    @Test void automaticImpreciseAddressAndSupplierFailureNeverRepeatUntilExplicitRetry()throws Exception{
        doAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);return List.of(new MapProvider.AddressCandidate("区县级合成位置",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"区县","NEEDS_DETAIL",false));}).when(provider).resolve(any());
        var input=Map.<String,Object>of("requestKey",UUID.randomUUID());var result=automatic(input);assertThat(result.path("status").asString()).isEqualTo("NEEDS_REVIEW");assertThat(result.hasNonNull("selectedShopId")).isFalse();assertThat(routes.get()).isZero();
        automatic(Map.of("requestKey",UUID.randomUUID()));verify(provider,times(1)).resolve(any());
        doThrow(MapFailure.of("QUOTA")).when(provider).resolve(any());automatic(Map.of("requestKey",UUID.randomUUID(),"retry",true));verify(provider,times(2)).resolve(any());assertThat(routes.get()).isZero();
    }
    @Test void automaticPartialComparisonDoesNotClaimNearestAndQualificationChangeBlocksDispatch()throws Exception{
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{routes.incrementAndGet();var to=(MapProvider.Point)inv.getArgument(1);if(to.longitude()<113.15)throw MapFailure.of("NO_ROUTE");return new MapProvider.DrivingRoute(1000,100L,32,0,false);});
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));assertThat(result.path("status").asString()).isEqualTo("PARTIAL");assertThat(result.hasNonNull("selectedShopId")).isFalse();
        var before=db.queryForMap("select version,parking_address,accident_region_id from work_order where id=?",caseId);capability(shops.get(1),List.of());
        postJson(automaticPath(result.path("id").asString())+"/dispatch",cs,Map.of("shopId",shops.get(1)),UUID.randomUUID().toString(),409);
        assertThat(db.queryForMap("select version,parking_address,accident_region_id from work_order where id=?",caseId)).isEqualTo(before);
    }
    @Test void automaticRestartAndChangedInputInvalidateOldPlansWithoutHiddenCalls()throws Exception{
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));String id=result.path("id").asString();assertThat(routes.get()).isEqualTo(3);
        ((Map<?,?>)ReflectionTestUtils.getField(automatic,"plans")).clear();assertThat(automatic(Map.of("requestKey",UUID.randomUUID())).path("status").asString()).isEqualTo("EXPIRED");assertThat(routes.get()).isEqualTo(3);
        var next=automatic(Map.of("requestKey",UUID.randomUUID(),"address","广州市天河区新接车入口20号","corrected",true));assertThat(next.path("status").asString()).as(next.path("message").asString()).isEqualTo("READY");assertThat(next.path("id").asString()).isNotEqualTo(id);assertThat(routes.get()).isEqualTo(6);
        postJson(automaticPath(id)+"/dispatch",cs,Map.of("shopId",shops.get(0)),UUID.randomUUID().toString(),404);
    }
    @Test void automaticRolesCaseVersionAndAccidentConflictsAreEnforced()throws Exception{
        String path="/api/v1/maps/cases/"+caseId+"/automatic-dispatch";
        for(String denied:List.of(admin,shop,owner))postJson(path,denied,Map.of("requestKey",UUID.randomUUID()),null,403);
        postJson(path,null,Map.of("requestKey",UUID.randomUUID()),null,401);verify(provider,never()).resolve(any());
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));db.update("update work_order set version=version+1 where id=?",caseId);
        postJson(automaticPath(result.path("id").asString())+"/dispatch",cs,Map.of("shopId",shops.get(0)),UUID.randomUUID().toString(),409);
        db.update("update work_order set accident_address='深圳市南山区合成地点10号' where id=?",caseId);
        long otherCity=db.queryForObject("insert into region(code,name,level,parent_id) values('440300','深圳市',2,?) on conflict(code) do update set enabled=true returning id",Long.class,province);
        db.update("insert into region(code,name,level,parent_id) values('440305','南山区',3,?) on conflict(code) do update set enabled=true",otherCity);
        assertThat(automatic(Map.of("requestKey",UUID.randomUUID())).path("status").asString()).isEqualTo("NEEDS_REVIEW");assertThat(routes.get()).isEqualTo(3);
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private long simulationFixtures()throws Exception{
        simulationEnabled=true;
        long receiver=db.queryForObject("select id from repair_shop where code='DEV-SHOP'",Long.class);
        db.update("update repair_shop set name='【测试／非真实合作方】合成模拟网点甲',address='合成测试街道甲10号',enabled=true where id=?",receiver);
        db.update("insert into shop_service_region(shop_id,region_id) values(?,?) on conflict do nothing",receiver,district);
        postJson("/api/v1/maps/admin/shops/"+receiver+"/location",admin,Map.of("districtId",district,"longitude",113.1,"latitude",23.1,"gcj02Confirmed",true),null,200);
        db.update("update repair_shop set code=?,name='【测试／非真实合作方】合成模拟网点乙' where id=?","DEV-SIM-"+UUID.randomUUID().toString().substring(0,8),shops.get(1));
        return receiver;
    }
    @Test void simulationsCompareAllDestinationsAndDispatchOnlyToOriginalDeveloperShop()throws Exception{
        long receiver=simulationFixtures();db.update("update work_order set vehicle_brand='未映射的合成品牌' where id=?",caseId);
        var catalog=json.readTree(mvc.perform(get("/api/v1/maps/simulation-shops").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(catalog.path("total").asLong()).isEqualTo(2);assertThat(routes.get()).isZero();
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));assertThat(result.path("status").asString()).isEqualTo("READY");assertThat(result.path("simulation").asBoolean()).isTrue();assertThat(result.path("selectedShopId").asLong()).isEqualTo(shops.get(1));assertThat(routes.get()).isEqualTo(2);
        var body=Map.of("shopId",shops.get(1),"confirmPossibleDuplicate",true,"duplicateReason","仅模拟目的地联调");String key=UUID.randomUUID().toString();
        var done=postJson(automaticPath(result.path("id").asString())+"/dispatch",cs,body,key,200).path("data");assertThat(done.path("shopId").asLong()).isEqualTo(receiver);
        mvc.perform(get("/api/v1/work-orders/"+caseId).header("Authorization","Bearer "+shop)).andExpect(status().isOk());
        postJson(automaticPath(result.path("id").asString())+"/dispatch",cs,body,key,200);assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,caseId)).isEqualTo(1);
    }
    @Test void roughSimulationCanBeDispatchedAndKeepsSelectedDestination()throws Exception{
        simulationFixtures();
        doAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);var candidate=new MapProvider.AddressCandidate("合成道路粗略位置",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"道路","NEEDS_DETAIL",false);return List.of(candidate,candidate);}).when(provider).resolve(any());
        var rough=automatic(Map.of("requestKey",UUID.randomUUID()));assertThat(rough.path("status").asString()).as(rough.path("message").asString()).isEqualTo("APPROXIMATE");assertThat(rough.path("location").hasNonNull("point")).isTrue();assertThat(rough.hasNonNull("selectedShopId")).isTrue();
        String key=UUID.randomUUID().toString();var body=Map.of("shopId",shops.get(1),"confirmPossibleDuplicate",true,"duplicateReason","合成粗定位直接派单");
        var done=postJson(automaticPath(rough.path("id").asString())+"/dispatch",cs,body,key,200).path("data");assertThat(done.path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");
        var refreshed=json.readTree(mvc.perform(get("/api/v1/work-orders/"+caseId).header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(refreshed.path("currentAssignment").path("destinationShopId").asLong()).isEqualTo(shops.get(1));assertThat(refreshed.path("currentAssignment").path("destinationName").asString()).contains("模拟网点乙");assertThat(refreshed.path("currentAssignment").path("locationPrecision").asString()).isEqualTo("ROAD");
        postJson(automaticPath(rough.path("id").asString())+"/dispatch",cs,body,key,200);assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,caseId)).isEqualTo(1);
    }
    @Test void preciseSupplementStillSavesOnlyAtFinalDispatch()throws Exception{
        simulationFixtures();var before=db.queryForMap("select version,accident_address,parking_address from work_order where id=?",caseId);
        String supplemented="广州市天河区合成事故地点20号";var precise=automatic(Map.of("requestKey",UUID.randomUUID(),"address",supplemented));assertThat(precise.path("status").asString()).isEqualTo("READY");
        assertThat(db.queryForMap("select version,accident_address,parking_address from work_order where id=?",caseId)).isEqualTo(before);
        postJson(automaticPath(precise.path("id").asString())+"/dispatch",cs,Map.of("shopId",shops.get(1),"confirmPossibleDuplicate",true,"duplicateReason","合成地址补充测试"),UUID.randomUUID().toString(),200);
        assertThat(db.queryForObject("select accident_address from work_order where id=?",String.class,caseId)).isEqualTo(supplemented);
        assertThat(db.queryForObject("select location_precision from work_order_assignment where work_order_id=?",String.class,caseId)).isEqualTo("PRECISE");
    }
    @Test void normalCooperativeRecommendationAlsoAllowsUniqueRoadLocation()throws Exception{
        doAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);return List.of(new MapProvider.AddressCandidate("合成道路位置",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"道路","NEEDS_DETAIL",false));}).when(provider).resolve(any());
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));assertThat(result.path("status").asString()).isEqualTo("APPROXIMATE");assertThat(result.path("simulation").asBoolean()).isFalse();
        postJson(automaticPath(result.path("id").asString())+"/dispatch",cs,Map.of("shopId",shops.get(1),"confirmPossibleDuplicate",true,"duplicateReason","合成合作网点粗定位派单"),UUID.randomUUID().toString(),200);
        assertThat(db.queryForObject("select location_precision from work_order_assignment where work_order_id=?",String.class,caseId)).isEqualTo("ROAD");
    }
    @Test void simulationCatalogAndResultsKeepRoleScopeAndRejectChangedDestination()throws Exception{
        simulationFixtures();for(String denied:List.of(admin,shop,owner))mvc.perform(get("/api/v1/maps/simulation-shops").header("Authorization","Bearer "+denied)).andExpect(status().isForbidden());
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));String queryId=result.path("recommendation").path("queryId").asString();
        for(String denied:List.of(admin,shop,owner))mvc.perform(get("/api/v1/maps/simulation-recommendations/"+queryId).header("Authorization","Bearer "+denied)).andExpect(status().isForbidden());
        db.update("update repair_shop set enabled=false where id=?",shops.get(1));postJson(automaticPath(result.path("id").asString())+"/dispatch",cs,Map.of("shopId",shops.get(1)),UUID.randomUUID().toString(),409);
        simulationEnabled=false;mvc.perform(get("/api/v1/maps/simulation-shops").header("Authorization","Bearer "+cs)).andExpect(status().isForbidden());
    }
    @Test void automaticFinalDuplicateFailureRollsBackLocationVersionAndAudit()throws Exception{
        db.update("update work_order set accident_address='广州市天河区合成测试街10号',accident_region_id=?,parking_address=null,parking_region_id=null,dispatch_address_confirmed=false where id=?",city,caseId);
        UUID duplicate=UUID.randomUUID();
        db.update("insert into work_order(id,business_no,status,claim_no,vehicle_vin,owner_phone,accident_at,shop_id,current_responsible_id,created_by,updated_by) select ?,'SYNTH-AUTO-DUP-'||?, 'PENDING_ACCEPTANCE',claim_no,vehicle_vin,owner_phone,accident_at,?,current_responsible_id,created_by,updated_by from work_order where id=?",duplicate,duplicate.toString().substring(0,12),shops.get(0),caseId);
        var result=automatic(Map.of("requestKey",UUID.randomUUID()));assertThat(result.path("status").asString()).isEqualTo("READY");
        var before=db.queryForMap("select version,accident_region_id,parking_region_id,parking_address,dispatch_address_confirmed from work_order where id=?",caseId);
        long audits=db.queryForObject("select count(*) from audit_log where object_id=?",Long.class,caseId.toString());
        String path=automaticPath(result.path("id").asString())+"/dispatch",key=UUID.randomUUID().toString();
        assertThat(postJson(path,cs,Map.of("shopId",shops.get(0)),key,409).path("code").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(db.queryForMap("select version,accident_region_id,parking_region_id,parking_address,dispatch_address_confirmed from work_order where id=?",caseId)).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from audit_log where object_id=?",Long.class,caseId.toString())).isEqualTo(audits);
        postJson(path,cs,Map.of("shopId",shops.get(0),"confirmPossibleDuplicate",true,"duplicateReason","合成重复确认"),key,200);
        assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,caseId)).isEqualTo(1);
    }
    @Test void automaticInFlightRefreshReusesWorkAndNewPickupDiscardsLateResult()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var geocodes=new AtomicInteger();
        doAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);if(geocodes.incrementAndGet()==1){entered.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}return List.of(new MapProvider.AddressCandidate("合成精确入口",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"门牌号","REQUIRES_CONFIRMATION",false));}).when(provider).resolve(any());
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=pool.submit(()->automatic(Map.of("requestKey",UUID.randomUUID())));
            try{
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(automatic(Map.of("requestKey",UUID.randomUUID())).path("status").asString()).isEqualTo("RUNNING");assertThat(geocodes.get()).isEqualTo(1);
                var next=automatic(Map.of("requestKey",UUID.randomUUID(),"address","广州市天河区新接车入口20号","corrected",true));assertThat(next.path("status").asString()).isEqualTo("READY");
            }finally{release.countDown();}
            var old=first.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(old.path("status").asString()).isEqualTo("NEEDS_REVIEW");assertThat(routes.get()).isEqualTo(3);
            postJson(automaticPath(old.path("id").asString())+"/dispatch",cs,Map.of("shopId",shops.get(0)),UUID.randomUUID().toString(),409);
        }
    }
    @Autowired MapTestPositions testPositions;
    @Test void explicitExtraAttemptIsAppendOnlySharedAndStopsWithOriginalRun() throws Exception {
        String run="offline-extra-"+UUID.randomUUID();
        var parameters=Map.of("city","440300","address","synthetic-expired-gate");
        String hash=MapCallBudget.fingerprint("/v3/geocode/geo",parameters);
        db.update("insert into map_call_budget_run(id,enabled) values(?,true)",run);
        db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values(?,'GEO_CUHK','GEOCODE',?)",run,hash);
        var originalEnv=new org.springframework.mock.env.MockEnvironment().withProperty("AMAP_BUDGET_RUN_ID",run).withProperty("AMAP_BUDGET_SCHEMA",SCHEMA);
        var original=new MapCallBudget(db,transactions,originalEnv);original.reserve("/v3/geocode/geo",parameters);
        var firstAttempt=db.queryForObject("select attempted_at from map_call_budget_slot where run_id=?",java.time.OffsetDateTime.class,run);
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("AMAP_BUDGET_RUN_ID",run).withProperty("AMAP_BUDGET_SCHEMA",SCHEMA).withProperty("AMAP_BUDGET_EXTRA_SCHEMA",SCHEMA);
        String url=System.getenv("TEST_DB_URL");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=public",System.getenv("TEST_DB_USERNAME"),System.getenv("TEST_DB_PASSWORD"));
        var extra=new MapCallBudget(new JdbcTemplate(source),new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),env);
        assertThat(extra.availability().remaining()).isZero();
        assertThatThrownBy(()->extra.reserve("/v3/geocode/geo",parameters)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        db.update("insert into map_call_budget_extra_attempt(ledger_schema,run_id,subject,operation,authorization_ref,request_hash) values(?,?,'GEO_CUHK','GEOCODE','synthetic-confirmed-extra',?)",SCHEMA,run,hash);
        assertThat(extra.availability().remaining()).isEqualTo(1);
        extra.stopFor("MAP_QUOTA");
        assertThatThrownBy(()->extra.reserve("/v3/geocode/geo",parameters)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        db.update("update map_call_budget_run set enabled=true where id=?",run);
        var restarted=new MapCallBudget(db,transactions,env);var successes=new AtomicInteger();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<12;i++)jobs.add(pool.submit(()->{try{extra.reserve("/v3/geocode/geo",parameters);successes.incrementAndGet();}catch(MapFailure expected){assertThat(expected.code()).isEqualTo("MAP_LOCAL_BUDGET");}}));
            for(var job:jobs)job.get();
        }
        assertThat(successes.get()).isEqualTo(1);assertThat(restarted.availability().remaining()).isZero();
        assertThat(db.queryForObject("select attempted_at from map_call_budget_slot where run_id=?",java.time.OffsetDateTime.class,run)).isEqualTo(firstAttempt);
        assertThatThrownBy(()->db.update("update map_call_budget_extra_attempt set attempted_at=null where run_id=?",run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("delete from map_call_budget_extra_attempt where run_id=?",run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("insert into map_call_budget_extra_attempt(ledger_schema,run_id,subject,operation,authorization_ref,request_hash) values(?,?,'GEO_CUHK','GEOCODE','synthetic-confirmed-extra',?)",SCHEMA,run,hash)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->new MapCallBudget(db,transactions,env.withProperty("AMAP_BUDGET_EXTRA_SCHEMA","bad;schema"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void separateBusinessSchemaUsesOriginalLedgerWithoutCopyingAllowances(){
        String run="offline-external-"+UUID.randomUUID();
        var parameters=Map.of("city","440300","address","synthetic-original-ledger");
        String hash=MapCallBudget.fingerprint("/v3/geocode/geo",parameters);
        db.update("insert into map_call_budget_run(id,enabled) values(?,true)",run);
        db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values(?,'GEO_SUSTECH','GEOCODE',?)",run,hash);
        String url=System.getenv("TEST_DB_URL");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=public",System.getenv("TEST_DB_USERNAME"),System.getenv("TEST_DB_PASSWORD"));
        var otherDb=new JdbcTemplate(source);
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("AMAP_BUDGET_RUN_ID",run).withProperty("AMAP_BUDGET_SCHEMA",SCHEMA);
        var ledger=new MapCallBudget(otherDb,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),env);
        assertThat(ledger.availability().remaining()).isEqualTo(1);
        ledger.reserve("/v3/geocode/geo",parameters);
        var original=new MapCallBudget(db,transactions,new org.springframework.mock.env.MockEnvironment().withProperty("AMAP_BUDGET_RUN_ID",run));
        assertThat(original.availability().remaining()).isZero();
        assertThatThrownBy(()->original.reserve("/v3/geocode/geo",parameters)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        ledger.stopFor("MAP_QUOTA");assertThat(original.availability().reason()).contains("关闭");
        assertThatThrownBy(()->new MapCallBudget(db,transactions,env.withProperty("AMAP_BUDGET_SCHEMA","bad;schema"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void durableBudgetIsSharedAcrossInstancesAndCannotResetAnAttempt() throws Exception {
        String run="offline-"+UUID.randomUUID();
        db.update("insert into map_call_budget_run(id,enabled) values(?,true)",run);
        var parameters=Map.of("city","440300","address","synthetic-approved-gate");
        String hash=MapCallBudget.fingerprint("/v3/geocode/geo",parameters);
        db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values(?,'GEO_SUSTECH','GEOCODE',?)",run,hash);
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("AMAP_BUDGET_RUN_ID",run);
        var first=new MapCallBudget(db,transactions,env);var restarted=new MapCallBudget(db,transactions,env);
        var successes=new AtomicInteger();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<12;i++)jobs.add(pool.submit(()->{try{restarted.reserve("/v3/geocode/geo",parameters);successes.incrementAndGet();}catch(MapFailure expected){assertThat(expected.code()).isEqualTo("MAP_LOCAL_BUDGET");}}));
            for(var job:jobs)job.get();
        }
        assertThat(successes.get()).isEqualTo(1);
        assertThat(first.availability().callsEnabled()).isFalse();
        assertThatThrownBy(()->first.reserve("/v5/direction/driving",parameters)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        assertThatThrownBy(()->db.update("update map_call_budget_slot set attempted_at=null where run_id=?",run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("select count(*) from map_call_budget_slot where run_id=? and attempted_at is not null",Integer.class,run)).isEqualTo(1);
        first.stopFor("MAP_QUOTA");assertThat(first.availability().reason()).contains("关闭");
    }
    @Test void testProviderPositionsAreTemporaryScopedAndNeverPersistSupplierCoordinates() throws Exception {
        long id=shops.get(0);db.update("update repair_shop set name=? where id=?","【测试／非真实合作方】合成目的地",id);
        for(String forbidden:List.of(cs,shop,owner))postJson("/api/v1/maps/admin/shops/"+id+"/test-location/resolve",forbidden,Map.of("districtId",district,"detail","合成入口10号"),null,403);
        var choice=postJson("/api/v1/maps/admin/shops/"+id+"/test-location/resolve",admin,Map.of("districtId",district,"detail","合成入口10号"),null,200).path("data").get(0);
        String path="/api/v1/maps/admin/shops/"+id+"/test-location/"+choice.path("token").asString()+"/confirm";
        long location=postJson(path,admin,Map.of(),null,200).path("data").path("id").asLong();
        assertThat(postJson(path,admin,Map.of(),null,200).path("data").path("id").asLong()).isEqualTo(location);
        assertThat(db.queryForObject("select count(*) from shop_map_location where id=? and longitude is null and latitude is null and source='AMAP_TEST_SESSION'",Integer.class,location)).isEqualTo(1);
        var result=query(position(),UUID.randomUUID().toString(),null,200);assertThat(routes.get()).isEqualTo(3);
        ((Map<?,?>)ReflectionTestUtils.getField(testPositions,"positions")).clear();
        mvc.perform(get("/api/v1/maps/recommendations/"+result.path("data").path("queryId").asString()).header("Authorization","Bearer "+cs)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAP_LOCATION_EXPIRED"));
        verify(provider,never()).locate(new MapProvider.Point(23,113));
    }
    @Test void parkingChangesDoNotChangeAccidentFactsAndInvalidatePriorSelection() throws Exception {
        var before=db.queryForMap("select accident_region_id,accident_address from work_order where id=?",caseId);
        var result=query(position(),UUID.randomUUID().toString(),null,200);String id=result.path("data").path("queryId").asString();
        var version=db.queryForObject("select version from work_order where id=?",Long.class,caseId);
        mvc.perform(put("/api/v1/work-orders/"+caseId+"/dispatch-location").header("Authorization","Bearer "+cs).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",version,"regionId",district,"address","另一个合成停放位置","confirmed",true)))).andExpect(status().isOk());
        assertThat(db.queryForMap("select accident_region_id,accident_address from work_order where id=?",caseId)).isEqualTo(before);
        postJson("/api/v1/maps/recommendations/"+id+"/dispatch",cs,Map.of("shopId",shops.get(1)),UUID.randomUUID().toString(),409);
        assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Integer.class,caseId)).isZero();
    }

    @Test void realModeLocalDispatchConfigurationAndEligibleShopsNeverInvokeLegacyGeocoding() throws Exception {
        clearInvocations(provider);
        for(String token:List.of(cs,admin)){
            mvc.perform(get("/api/v1/maps/configuration").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.provider").value("AMAP_WEB_V1")).andExpect(jsonPath("$.data.mock").value(false));
            mvc.perform(get("/api/v1/maps/cities").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/work-orders/"+caseId+"/eligible-shops").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.records[0].distanceMetres").doesNotExist());
        for(String token:List.of(shop,owner))for(String path:List.of("/api/v1/maps/configuration","/api/v1/maps/cities"))mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/maps/configuration")).andExpect(status().isUnauthorized());
        verify(provider,never()).geocode(anyString());verify(provider,never()).distance(any(),any());verify(provider,never()).resolve(any());verify(provider,never()).driving(any(),any(),anyInt());
    }

    @Test void roadsNotStraightLineStablePaginationAndExplicitOriginalDispatch() throws Exception {
        mvc.perform(get("/api/v1/maps/cases/"+caseId+"/recommendation-context").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andExpect(jsonPath("$.data.accidentRegion").value("广州市 / 天河区"));
        var token=position();String key=UUID.randomUUID().toString();var result=query(token,key,null,200).path("data");var rows=result.path("shops").path("records");
        assertThat(rows.get(0).path("shopId").asLong()).isEqualTo(shops.get(1));assertThat(rows.get(1).path("shopId").asLong()).isEqualTo(shops.get(2));assertThat(rows.get(2).path("metres").asLong()).isEqualTo(9000);assertThat(result.path("complete").asBoolean()).isTrue();assertThat(result.path("direction").asString()).isEqualTo("VEHICLE_TO_SHOP");
        String queryId=result.path("queryId").asString();mvc.perform(get("/api/v1/maps/recommendations/"+queryId+"?page=2&size=1").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andExpect(jsonPath("$.data.shops.records[0].shopId").value(shops.get(2)));
        query(token,key,null,200);assertThat(routes.get()).isEqualTo(3);
        assertThat(db.queryForObject("select status from work_order where id=?",String.class,caseId)).isEqualTo("PENDING_DISPATCH");
        String command=UUID.randomUUID().toString();var body=Map.of("shopId",shops.get(1),"confirmPossibleDuplicate",true,"duplicateReason","合成测试");
        var dispatched=postJson("/api/v1/maps/recommendations/"+queryId+"/dispatch",cs,body,command,200);assertThat(dispatched.path("data").path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");
        postJson("/api/v1/maps/recommendations/"+queryId+"/dispatch",cs,body,command,200);
        assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,caseId)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from audit_log where action='WORK_ORDER_DISPATCH' and object_id=?",Long.class,caseId.toString())).isEqualTo(1);
    }
    @Test void missingCapabilityDisabledOutsideAreaAndAddressChangesAreExplicit() throws Exception {
        capability(shops.get(0),List.of());db.update("update repair_shop set enabled=false where id=?",shops.get(1));db.update("update repair_shop set address='新的合成地址10号' where id=?",shops.get(2));
        var result=query(position(),UUID.randomUUID().toString(),null,200).path("data");assertThat(result.path("shops").path("total").asLong()).isZero();assertThat(routes.get()).isZero();
        var excluded=json.readTree(mvc.perform(get("/api/v1/maps/cases/"+caseId+"/recommendation-exclusions").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(excluded.path("total").asLong()).isEqualTo(2);assertThat(excluded.toString()).contains("CAPABILITY_INCOMPLETE","LOCATION_STALE").doesNotContain("没有维修能力");
        db.update("delete from shop_service_region where shop_id=?",shops.get(0));assertThat(query(position(),UUID.randomUUID().toString(),null,200).path("data").path("shops").path("total").asLong()).isZero();
    }
    @Test void selectedRecommendationRechecksCapabilitiesButManualDispatchKeepsExistingRules() throws Exception {
        String queryId=query(position(),UUID.randomUUID().toString(),null,200).path("data").path("queryId").asString();capability(shops.get(0),List.of());
        var body=Map.of("shopId",shops.get(0),"confirmPossibleDuplicate",true,"duplicateReason","合成测试");
        assertThat(postJson("/api/v1/maps/recommendations/"+queryId+"/dispatch",cs,body,UUID.randomUUID().toString(),409).path("code").asString()).isEqualTo("MAP_QUALIFICATION_CHANGED");
        postJson("/api/v1/work-orders/"+caseId+"/dispatch",cs,body,UUID.randomUUID().toString(),200);
    }
    @Test void quotaStopsFurtherCallsAndNoRouteCannotShiftAnotherShopDistance() throws Exception {
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{routes.incrementAndGet();throw MapFailure.of("QUOTA");});
        var result=query(position(),UUID.randomUUID().toString(),null,200).path("data");assertThat(routes.get()).isEqualTo(1);assertThat(result.path("complete").asBoolean()).isFalse();for(var row:result.path("shops").path("records")){assertThat(row.hasNonNull("metres")).isFalse();assertThat(row.path("status").asString()).isEqualTo("MAP_QUOTA");}
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{routes.incrementAndGet();var to=(MapProvider.Point)inv.getArgument(1);if(to.longitude()<113.15)throw MapFailure.of("NO_ROUTE");return new MapProvider.DrivingRoute(3000,300L,32,0,false);});
        var rows=query(position(),UUID.randomUUID().toString(),null,200).path("data").path("shops").path("records");assertThat(rows.get(0).path("shopId").asLong()).isEqualTo(shops.get(1));assertThat(rows.get(2).path("shopId").asLong()).isEqualTo(shops.get(0));assertThat(rows.get(2).hasNonNull("metres")).isFalse();
    }
    @Test void allFourRoleBoundariesAndCrossOperatorQueries() throws Exception {
        UUID token=position();String queryId=query(token,UUID.randomUUID().toString(),null,200).path("data").path("queryId").asString();
        for(String denied:List.of(admin,shop,owner)){postJson("/api/v1/maps/cases/"+caseId+"/recommendations",denied,Map.of("locationToken",token),UUID.randomUUID().toString(),403);mvc.perform(get("/api/v1/maps/recommendations/"+queryId).header("Authorization","Bearer "+denied)).andExpect(status().isForbidden());}
        for(String denied:List.of(cs,shop,owner)){mvc.perform(put("/api/v1/maps/admin/shops/"+shops.get(0)+"/capabilities").header("Authorization","Bearer "+denied).contentType(MediaType.APPLICATION_JSON).content("{\"modelIds\":[]}")).andExpect(status().isForbidden());}
        String username="map_cs_"+UUID.randomUUID();postJson("/api/v1/admin/users",admin,Map.of("username",username,"displayName","合成客服乙","password",PASSWORD,"roles",List.of("CUSTOMER_SERVICE")),null,200);String other=login(username,"ADMIN");
        mvc.perform(get("/api/v1/maps/recommendations/"+queryId).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());postJson("/api/v1/maps/locations/"+token+"/confirm",other,Map.of(),null,404);
    }
    @Test void restartedResultStoreAndExpiredQueryNeverRepeatVendorCalls() throws Exception {
        UUID token=position();String key=UUID.randomUUID().toString();String id=query(token,key,null,200).path("data").path("queryId").asString();assertThat(routes.get()).isEqualTo(3);
        ((Map<?,?>)ReflectionTestUtils.getField(recommendations,"snapshots")).clear();query(token,key,null,409);assertThat(routes.get()).isEqualTo(3);
        db.update("update map_recommendation_query set expires_at=current_timestamp-interval '1 second' where id=?",UUID.fromString(id));
        postJson("/api/v1/maps/recommendations/"+id+"/dispatch",cs,Map.of("shopId",shops.get(0)),UUID.randomUUID().toString(),409);assertThat(routes.get()).isEqualTo(3);
    }
    @Test void fiftyOneCandidatesAreNotTruncatedAndExplicitCityScopePreservesAccidentData() throws Exception {
        Long secondCity=db.queryForObject("insert into region(code,name,level,parent_id) values('440300','深圳市',2,?) on conflict(code) do update set enabled=true returning id",Long.class,province);
        Long secondDistrict=db.queryForObject("insert into region(code,name,level,parent_id) values('440305','南山区',3,?) on conflict(code) do update set enabled=true returning id",Long.class,secondCity);
        for(int i=0;i<48;i++){long id=db.queryForObject("insert into repair_shop(code,name,address) values(?,'合成预算测试网点','合成测试地址10号') returning id",Long.class,"MAP-TEST-"+UUID.randomUUID().toString().replace("-","").substring(0,20));db.update("insert into shop_service_region(shop_id,region_id) values(?,?)",id,district);db.update("insert into shop_model_capability(shop_id,model_id,created_by) values(?,?,?)",id,model,actor);db.update("insert into shop_map_location(shop_id,version,district_id,address_snapshot,longitude,latitude,coordinate_system,source,created_by) values(?,1,?,'合成测试地址10号',113.2,23.1,'GCJ-02','ADMIN_CONFIRMED_GCJ02',?)",id,secondDistrict,actor);}
        var token=position();assertThat(query(token,UUID.randomUUID().toString(),null,409).path("code").asString()).isEqualTo("MAP_CANDIDATE_LIMIT");assertThat(routes.get()).isZero();
        var scoped=query(token,UUID.randomUUID().toString(),city,200).path("data");assertThat(scoped.path("shops").path("total").asLong()).isEqualTo(3);assertThat(scoped.path("scope").asString()).contains("网点所在城市ID="+city);assertThat(routes.get()).isEqualTo(3);assertThat(db.queryForObject("select accident_region_id from work_order where id=?",Long.class,caseId)).isEqualTo(district);
    }
    @Test void locationHistoryAndPrivacyAreProtectedAndFlywayValidates() throws Exception {
        long shopId=shops.get(0);postJson("/api/v1/maps/admin/shops/"+shopId+"/location",admin,Map.of("districtId",district,"longitude",113.15,"latitude",23.15,"gcj02Confirmed",true),null,200);
        assertThat(db.queryForObject("select count(*) from shop_map_location where shop_id=?",Long.class,shopId)).isEqualTo(2);
        assertThatThrownBy(()->db.update("update shop_map_location set longitude=0 where shop_id=?",shopId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("insert into shop_map_location(shop_id,version,district_id,address_snapshot,longitude,latitude,coordinate_system,source,created_by) values(?,3,?,'合成',113,23,'GPS','ADMIN_CONFIRMED_GCJ02',?)",shopId,district,actor)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        query(position(),UUID.randomUUID().toString(),null,200);
        String audit=String.join("\n",db.queryForList("select summary from audit_log where action like 'MAP_%'",String.class));assertThat(audit).doesNotContain("合成停车位置","合成测试街道","113.15",PASSWORD,"access_token","key=");
        flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("30");
    }
    @Test void ambiguousModelForeignPositionAndUnknownSourceCannotBecomeRealRecommendation() throws Exception {
        when(provider.locate(any())).thenReturn(new MapProvider.AddressCandidate("合成外省",new MapProvider.Point(28,112),"湖南省","长沙市","合成区","430100","REVERSE","REQUIRES_CONFIRMATION",false));
        postJson("/api/v1/maps/admin/shops/"+shops.get(0)+"/location",admin,Map.of("districtId",district,"longitude",112,"latitude",28,"gcj02Confirmed",true),null,502);
        db.update("insert into shop_map_location(shop_id,version,district_id,address_snapshot,longitude,latitude,coordinate_system,source,created_by) select shop_id,version+1,district_id,address_snapshot,longitude,latitude,'GCJ-02','SYNTHETIC_TEST',created_by from shop_map_location where shop_id=?",shops.get(0));
        var rows=query(position(),UUID.randomUUID().toString(),null,200).path("data").path("shops").path("records");
        assertThat(rows.get(2).path("status").asString()).isEqualTo("COORDINATE_SOURCE_UNKNOWN");assertThat(rows.get(2).hasNonNull("metres")).isFalse();assertThat(routes.get()).isEqualTo(2);
        db.update("insert into price_model(brand_id,code,name,created_by,updated_by) select brand_id,?,name,created_by,updated_by from price_model where id=?",UUID.randomUUID().toString(),model);
        assertThat(query(position(),UUID.randomUUID().toString(),null,409).path("code").asString()).isEqualTo("MAP_MODEL_MAPPING_REQUIRED");
    }
    @Test void coarseOrConflictingCandidateCannotBeConfirmedAndConcurrentDuplicateDoesNotRecall() throws Exception {
        doReturn(List.of(new MapProvider.AddressCandidate("合成粗粒度",new MapProvider.Point(23,113),"广东省","广州市","天河区","440106","区县","NEEDS_DETAIL",false))).when(provider).resolve(any());
        UUID coarse=UUID.fromString(postJson("/api/v1/maps/locations/resolve",cs,Map.of("districtId",district,"detail","合成详细地址10号"),null,200).path("data").get(0).path("token").asString());
        postJson("/api/v1/maps/locations/"+coarse+"/confirm",cs,Map.of(),null,400);
        doReturn(List.of(new MapProvider.AddressCandidate("合成精确位置",new MapProvider.Point(23,113),"广东省","广州市","天河区","440106","门牌号","REQUIRES_CONFIRMATION",false))).when(provider).resolve(any());
        UUID token=position();String key=UUID.randomUUID().toString();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{int count=routes.incrementAndGet();if(count==1){entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Synthetic test release timeout");}return new MapProvider.DrivingRoute(1000,100L,32,0,false);});
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=pool.submit(()->query(token,key,null,200));assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{query(token,key,null,409);assertThat(routes.get()).isEqualTo(1);}finally{release.countDown();}
            var completed=first.get();query(token,key,null,200);assertThat(completed.path("data").path("shops").path("total").asInt()).isEqualTo(3);assertThat(routes.get()).isEqualTo(3);
        }
    }
    @Test void estimateChecksWholeQualifiedSetWithoutCallingProviderAndKeepsRoleBoundary() throws Exception {
        int before=routes.get();
        mvc.perform(get("/api/v1/maps/cases/"+caseId+"/recommendation-estimate").header("Authorization","Bearer "+cs))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates").value(3)).andExpect(jsonPath("$.data.maximumRouteCalls").value(3));
        for(String token:List.of(admin,shop,owner))mvc.perform(get("/api/v1/maps/cases/"+caseId+"/recommendation-estimate").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        assertThat(routes.get()).isEqualTo(before);
    }
    @Test void configuredOtherModelAndChangedServiceRegionCannotBypassSelectionOrReadonlyCase() throws Exception {
        String id=query(position(),UUID.randomUUID().toString(),null,200).path("data").path("queryId").asString();
        long otherModel=db.queryForObject("insert into price_model(brand_id,code,name,created_by,updated_by) select brand_id,?,'其他合成车型',created_by,updated_by from price_model where id=? returning id",Long.class,UUID.randomUUID().toString(),model);
        capability(shops.get(0),List.of(otherModel));
        mvc.perform(get("/api/v1/maps/recommendations/"+id).header("Authorization","Bearer "+cs)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAP_QUALIFICATION_CHANGED"));
        var excluded=json.readTree(mvc.perform(get("/api/v1/maps/cases/"+caseId+"/recommendation-exclusions").header("Authorization","Bearer "+cs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(excluded.toString()).contains("MODEL_NOT_MATCHED");
        db.update("delete from shop_service_region where shop_id=?",shops.get(1));
        postJson("/api/v1/maps/recommendations/"+id+"/dispatch",cs,Map.of("shopId",shops.get(1)),UUID.randomUUID().toString(),409);
        db.update("update work_order set status='CANCELLED' where id=?",caseId);
        int before=routes.get();query(position(),UUID.randomUUID().toString(),null,409);assertThat(routes.get()).isEqualTo(before);
    }

    @Test void selectedPointApiKeepsRolesAndDoesNotWriteCaseOrAccidentData()throws Exception{
        var before=db.queryForMap("select version,accident_region_id,parking_region_id,parking_address from work_order where id=?",caseId);
        for(String token:List.of(admin,shop,owner))postJson("/api/v1/maps/locations/point",token,Map.of("districtId",district,"longitude",113.1,"latitude",23.1),null,403);
        var result=postJson("/api/v1/maps/locations/point",cs,Map.of("districtId",district,"longitude",113.1,"latitude",23.1),null,200);
        assertThat(result.path("data").get(0).path("candidate").path("status").asString()).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(db.queryForMap("select version,accident_region_id,parking_region_id,parking_address from work_order where id=?",caseId)).isEqualTo(before);
    }
}
