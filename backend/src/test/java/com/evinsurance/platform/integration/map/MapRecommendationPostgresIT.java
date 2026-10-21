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
    private static String secret(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        String url=System.getenv("TEST_DB_URL");if(url==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        r.add("MAP_TEST_SESSION_ENABLED",()->"true");
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))r.add(name,()->System.getenv("TEST_"+name));
        r.add("JWT_SECRET_BASE64",()->SECRET);r.add("JWT_TTL_SECONDS",()->"1800");r.add("OCR_WORKER_ENABLED",()->"false");
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
    }
    @MockitoBean MapProvider provider;
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired Flyway flyway;@Autowired MapRecommendationService recommendations;
    private String admin,cs,shop,owner;private long province,city,district,model,actor;private UUID caseId;private final List<Long> shops=new ArrayList<>();private AtomicInteger routes;
    @BeforeEach void setup() throws Exception {
        reset(provider);when(provider.providerId()).thenReturn("AMAP_WEB_V1");routes=new AtomicInteger();
        when(provider.resolve(any())).thenAnswer(inv->{var a=(MapProvider.Address)inv.getArgument(0);return List.of(new MapProvider.AddressCandidate("合成停车位置",new MapProvider.Point(23,113),"广东省",a.city(),a.district(),a.districtAdcode(),"门牌号","REQUIRES_CONFIRMATION",false));});
        when(provider.locate(any())).thenReturn(new MapProvider.AddressCandidate("合成位置",new MapProvider.Point(23.1,113.1),"广东省","广州市","天河区","440106","REVERSE","REQUIRES_CONFIRMATION",false));
        when(provider.driving(any(),any(),eq(32))).thenAnswer(inv->{routes.incrementAndGet();var from=(MapProvider.Point)inv.getArgument(0);var to=(MapProvider.Point)inv.getArgument(1);assertThat(from).isEqualTo(new MapProvider.Point(23,113));return new MapProvider.DrivingRoute(to.longitude()<113.15?9000:1000,600L,32,0,false);});
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
    private JsonNode postJson(String path,String token,Object body,String key,int statusCode) throws Exception {var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);return json.readTree(mvc.perform(request).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString());}
    private void capability(long shop,List<Long> models) throws Exception{mvc.perform(put("/api/v1/maps/admin/shops/"+shop+"/capabilities").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("modelIds",models)))).andExpect(status().isOk());}
    private UUID position() throws Exception {var choices=postJson("/api/v1/maps/locations/resolve",cs,Map.of("districtId",district,"detail","合成停车位置10号"),null,200).path("data");UUID token=UUID.fromString(choices.get(0).path("token").asString());postJson("/api/v1/maps/locations/"+token+"/confirm",cs,Map.of(),null,200);return token;}
    private JsonNode query(UUID token,String key,Long cityId,int expected) throws Exception {var body=new HashMap<String,Object>();body.put("locationToken",token);body.put("shopCityId",cityId);return postJson("/api/v1/maps/cases/"+caseId+"/recommendations",cs,body,key,expected);}

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired MapTestPositions testPositions;
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
        flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("23");
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
}
