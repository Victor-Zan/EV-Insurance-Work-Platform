package com.evinsurance.platform.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.*;
import com.evinsurance.platform.pricing.application.SampleService;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("dev")
class SamplePostgresIT {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String SCHEMA="price_samples_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String SECRET=Base64.getEncoder().encodeToString((UUID.randomUUID().toString()+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var directory=Files.createTempDirectory("price-sample-foundation-");
        try(var input=SamplePostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")) {
            Files.copy(Objects.requireNonNull(input),directory.resolve("V2.1__development_foundation.sql"));
        }
        registry.add("spring.flyway.locations",()->"classpath:db/migration,filesystem:"+directory);
        String url=Objects.requireNonNull(System.getenv("TEST_DB_URL"),"TEST_DB_URL is required");
        registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
        registry.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
        registry.add("spring.flyway.schemas",()->SCHEMA);registry.add("spring.flyway.default-schema",()->SCHEMA);
        registry.add("JWT_SECRET_BASE64",()->SECRET);registry.add("JWT_TTL_SECONDS",()->"1800");
        registry.add("OCR_WORKER_ENABLED",()->"false");
        for(String name:List.of("MINIO_ENDPOINT","MINIO_ACCESS_KEY","MINIO_SECRET_KEY","MINIO_BUCKET"))
            registry.add(name,()->Objects.requireNonNull(System.getenv("TEST_"+name)));
        for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))registry.add("DEV_"+role+"_PASSWORD",()->PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;
    private String token;
    private long model,part;

    private String login(String role) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username","dev_"+role,"password",PASSWORD,"portal",List.of("admin","customer_service").contains(role)?"ADMIN":"H5"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("accessToken").asText();
    }
    private JsonNode ok(MockHttpServletRequestBuilder request,Object body) throws Exception {
        request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @BeforeEach void setup() throws Exception {
        db.update("DELETE FROM account_rate_limit");token=login("customer_service");
        String code=UUID.randomUUID().toString();
        long brand=ok(post("/api/v1/pricing/brands"),Map.of("code",code,"name","测试品牌","enabled",true)).path("id").asLong();
        model=ok(post("/api/v1/pricing/models"),Map.of("brandId",brand,"code",code,"name","测试车型","enabled",true)).path("id").asLong();
        part=ok(post("/api/v1/pricing/parts"),Map.of("internalCode",code,"name","测试配件","unit","个","oeCode","TEST-OE","manufacturerCode","TEST-FACTORY","enabled",true)).path("id").asLong();
        ok(post("/api/v1/pricing/part-models"),Map.of("partId",part,"modelId",model));
    }
    private JsonNode capture(String name) throws Exception {
        var body=Map.of("requestId",UUID.randomUUID(),"sourceKind","MARKET","values",Map.of("partName",name,"amount","18.50","unit","个"));
        var batch=ok(post("/api/v1/pricing/samples"),body);
        assertThat(ok(post("/api/v1/pricing/samples"),body)).isEqualTo(batch);
        return ok(get("/api/v1/pricing/samples").param("batchId",batch.path("batchId").asText()),null).path("records").get(0);
    }
    private Map<String,Object> review(JsonNode sample,String amount,String region,String state) {
        return Map.ofEntries(Map.entry("expectedVersion",sample.path("version").asInt()),Map.entry("status",state),
            Map.entry("partId",part),Map.entry("modelId",model),Map.entry("amount",amount),Map.entry("unit","个"),
            Map.entry("partBrand","测试配件品牌"),Map.entry("region",region),
            Map.entry("priceNature","MARKET_RETAIL"),Map.entry("reason","测试资料已人工核对"));
    }
    private JsonNode confirm(JsonNode sample,String amount,String region) throws Exception {
        return ok(post("/api/v1/pricing/samples/"+sample.path("id").asText()+"/reviews"),review(sample,amount,region,"CONFIRMED"));
    }
    private JsonNode groups() throws Exception {return ok(get("/api/v1/pricing/samples/groups").param("modelId",Long.toString(model)),null);}

    @Test void reviewVersionsGroupsAndRawEvidenceRemainConsistent() throws Exception {
        var first=capture("原始名称甲");
        assertThat(groups().path("total").asInt()).isZero();
        var confirmed=confirm(first,"18.50","测试地区");
        assertThat(confirmed.path("importedDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}")
            .isEqualTo(first.path("importedDate").asText());
        confirm(capture("原始名称乙"),"21.40","测试地区");
        confirm(capture("原始名称丙"),"9.90","其他地区");
        var result=groups();
        assertThat(result.path("total").asInt()).isEqualTo(2);
        var group=result.path("records").valueStream().filter(n->n.path("region").asText().equals("测试地区")).findFirst().orElseThrow();
        assertThat(group.path("records").asInt()).isEqualTo(2);
        assertThat(group.path("minimum").asText()).isEqualTo("18.50");
        assertThat(group.path("maximum").asText()).isEqualTo("21.40");
        var details=ok(get("/api/v1/pricing/samples").param("reference","true").param("modelId",Long.toString(model))
            .param("partId",Long.toString(part)).param("unit","个").param("partBrand","测试配件品牌")
            .param("region","测试地区").param("priceNature","MARKET_RETAIL").param("exactGroup","true").param("size","1"),null);
        assertThat(details.path("total").asInt()).isEqualTo(2);assertThat(details.path("records").size()).isEqualTo(1);
        var excluded=ok(post("/api/v1/pricing/samples/"+confirmed.path("id").asText()+"/reviews"),review(confirmed,"18.50","测试地区","EXCLUDED"));
        assertThat(excluded.path("original")).isEqualTo(first.path("original"));
        assertThat(excluded.path("importedDate")).isEqualTo(first.path("importedDate"));
        assertThat(ok(get("/api/v1/pricing/samples/"+first.path("id").asText()+"/history"),null).path("total").asInt()).isEqualTo(2);
        UUID id=UUID.fromString(first.path("id").asText());
        assertThatThrownBy(()->db.update("UPDATE price_sample SET original='{}' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("DELETE FROM price_sample_review WHERE sample_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='PRICE_SAMPLE_REVIEW'",Long.class,id.toString())).isEqualTo(2);
        flyway.validate();
    }

    @Test void confirmedSamplesRequireCompatibleUnitAndPrecisionAndRejectStaleReview() throws Exception {
        var sample=capture("需要核对");
        var invalid=new HashMap<>(review(sample,"18.501","测试地区","CONFIRMED"));
        String path="/api/v1/pricing/samples/"+sample.path("id").asText()+"/reviews";
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        invalid.put("amount","18.50");invalid.put("unit","套");
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        confirm(sample,"18.50","测试地区");
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(review(sample,"18.50","测试地区","CONFIRMED")))).andExpect(status().isConflict());
        assertThat(ok(get("/api/v1/pricing/samples/groups").param("modelId",Long.toString(model)).param("status","EXCLUDED"),null).path("total").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/pricing/samples").param("size","101").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
    }

    @Test void uploadsAreDeduplicatedAndOriginalFileIsRecoverable() throws Exception {
        byte[] content=(String.join(",",SampleService.FIELDS)+"\r\n测试配件,测试车型,18.50,个,品牌,测试地区,MARKET_RETAIL,"+UUID.randomUUID()+"\r\n未知配件,,金额待核对,,,,,\r\n").getBytes(StandardCharsets.UTF_8);
        var file=new MockMultipartFile("file","test-prices.csv","text/csv",content);
        String batch=null;
        for(int i=0;i<2;i++) {
            var response=mvc.perform(multipart("/api/v1/pricing/samples/imports").file(file).param("sourceKind","XIAOBANGBANG").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
            String current=json.readTree(response.getResponse().getContentAsString()).path("data").path("batchId").asText();
            if(batch!=null)assertThat(current).isEqualTo(batch);batch=current;
        }
        var rows=ok(get("/api/v1/pricing/samples").param("batchId",batch),null);
        assertThat(rows.path("total").asInt()).isEqualTo(2);
        assertThat(ok(get("/api/v1/pricing/samples/groups").param("batchId",batch),null).path("total").asInt()).isZero();
        var download=mvc.perform(get("/api/v1/pricing/samples/batches/"+batch+"/source").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(download).isEqualTo(content);
        for(String role:List.of("owner","repair_shop")) {
            String other=login(role);
            for(String endpoint:List.of("","/groups","/batches/"+batch+"/source","/template"))
                mvc.perform(get("/api/v1/pricing/samples"+endpoint).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/pricing/samples").header("Authorization","Bearer "+other).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
    }

    @Test void importDateFiltersUseChinaDayBoundariesForPendingSamples() throws Exception {
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='dev_customer_service'",Long.class);
        UUID batch=UUID.randomUUID();
        db.update("INSERT INTO price_sample_batch(id,source_kind,source_key,created_by) VALUES(?,'MARKET',?,?)",batch,"TEST-DATES-"+batch,actor);
        db.update("""
            INSERT INTO price_sample(id,batch_id,row_number,original,created_at) VALUES
            (gen_random_uuid(),?,1,'{}','2026-11-08 16:00:00+00'),
            (gen_random_uuid(),?,2,'{}','2026-11-09 16:00:00+00')
            """,batch,batch);
        var rows=ok(get("/api/v1/pricing/samples").param("batchId",batch.toString())
            .param("from","2026-11-09").param("to","2026-11-09"),null);
        assertThat(rows.path("total").asInt()).isEqualTo(1);
        assertThat(rows.path("records").get(0).path("importedDate").asText()).isEqualTo("2026-11-09");
    }

    @Test void hundredThousandSamplesRemainPagedWithExactAggregate() throws Exception {
        long actor=db.queryForObject("SELECT id FROM app_user WHERE username='dev_customer_service'",Long.class);
        UUID batch=UUID.randomUUID();
        db.update("INSERT INTO price_sample_batch(id,source_kind,source_key,created_by) VALUES(?,'MARKET',?,?)",batch,"TEST-PERF-"+batch,actor);
        db.update("INSERT INTO price_sample(id,batch_id,row_number,original,revision) SELECT gen_random_uuid(),?,n,jsonb_build_object('partName','性能测试配件'),1 FROM generate_series(1,100000) n",batch);
        db.update("""
            INSERT INTO price_sample_review(sample_id,revision,status,part_id,model_id,amount,unit,price_nature,reason,created_by)
            SELECT id,1,'CONFIRMED',?,?,10.00+(row_number%100),'个','MARKET_RETAIL','TEST ONLY',?
            FROM price_sample WHERE batch_id=?
            """,part,model,actor,batch);
        db.execute("ANALYZE price_sample");db.execute("ANALYZE price_sample_review");
        var result=groups();
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("records").get(0).path("records").asInt()).isEqualTo(100000);
        assertThat(result.path("records").get(0).path("minimum").asText()).isEqualTo("10.00");
        var details=ok(get("/api/v1/pricing/samples").param("reference","true").param("modelId",Long.toString(model)).param("size","20").param("page","5000"),null);
        assertThat(details.path("total").asInt()).isEqualTo(100000);assertThat(details.path("records").size()).isEqualTo(20);
        var plan=db.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT min(amount),max(amount),count(*) FROM price_sample_review r JOIN price_sample s ON s.id=r.sample_id AND s.revision=r.revision WHERE r.status='CONFIRMED' AND r.model_id=?",String.class,model);
        System.out.println("Price sample 100k execution plan\n"+String.join("\n",plan));
    }

    @Test void concurrentReviewAppendsOnlyOneVersion() throws Exception {
        var sample=capture("并发核对");
        String body=json.writeValueAsString(review(sample,"18.50","测试地区","CONFIRMED"));
        String path="/api/v1/pricing/samples/"+sample.path("id").asText()+"/reviews";
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Integer> action=()->{gate.await();return mvc.perform(post(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();};
            var first=executor.submit(action);var second=executor.submit(action);gate.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(ok(get("/api/v1/pricing/samples/"+sample.path("id").asText()+"/history"),null).path("total").asInt()).isEqualTo(1);
    }

    @Test void formalWorkbookArchivesBeforeImportingSeparateCorrectedQuotes() throws Exception {
        byte[] source=fullHistoricalFixture();
        var file=new MockMultipartFile("file","historical-synthetic.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",source);
        long liveCount=db.queryForObject("SELECT count(*) FROM work_order",Long.class);
        var response=mvc.perform(multipart("/api/v1/historical-cases/imports/full").file(file)
            .param("excludeRows","7").param("supplierCorrectionRow","5").param("supplierCorrectionAmount","95")
            .header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        var preview=json.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(preview.path("totalRows").asInt()).isEqualTo(2);
        String path="/api/v1/historical-cases/imports/"+preview.path("id").asText();
        mvc.perform(post(path+"/prices").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        assertThat(ok(post(path+"/confirm"),null).path("imported_count").asInt()).isEqualTo(2);
        var archived=ok(get(path+"/rows"),null).path("records");
        assertThat(archived.get(0).path("fullRecord").path("details").size()).isEqualTo(2);
        var detail=ok(get("/api/v1/historical-cases/"+archived.get(0).path("archiveId").asText()),null);
        assertThat(detail.path("source_name").asText()).isEqualTo("正式历史导入");
        assertThat(detail.path("platform_order_no").asText()).isEqualTo("TEST-ORDER-01");
        assertThat(detail.path("full_record").path("details").get(0).path("original").path("配件商明细报价").asText()).isEqualTo("0");
        assertThat(detail.path("full_record").path("details").get(0).path("correctedSupplierAmount").asText()).isEqualTo("95");
        assertThat(ok(post(path+"/prices"),null).path("importedPrices").asInt()).isEqualTo(4);
        assertThat(ok(post(path+"/prices"),null).path("existingPrices").asInt()).isEqualTo(4);
        var groups=ok(get("/api/v1/pricing/samples/groups").param("sourceKind","HISTORICAL_ARCHIVE").param("priceNature","SUPPLIER_QUOTED"),null);
        assertThat(groups.path("total").asInt()).isEqualTo(2);
        assertThat(groups.path("records").valueStream().map(r->r.path("minimum").asText())).containsExactlyInAnyOrder("95.00","22.00");
        var supplierSamples=ok(get("/api/v1/pricing/samples").param("sourceKind","HISTORICAL_ARCHIVE")
            .param("priceNature","SUPPLIER_QUOTED").param("reference","true"),null).path("records");
        var corrected=supplierSamples.valueStream().filter(row->row.path("original").path("lineNumber").asInt()==5)
            .findFirst().orElseThrow();
        var priceDetails=ok(get("/api/v1/pricing/samples/"+corrected.path("id").asText()),null);
        assertThat(priceDetails.path("record")).isEqualTo(corrected);
        var quotes=priceDetails.path("relatedQuotes");
        assertThat(quotes.valueStream().map(row->row.path("priceNature").asText())).containsExactly("SUPPLIER_QUOTED","CP_QUOTED");
        assertThat(quotes.valueStream().map(row->row.path("amount").asText())).containsExactly("95.00","95.00");
        assertThat(quotes.get(0).path("original").path("amount").asText()).isEqualTo("0");
        assertThat(db.queryForObject("SELECT count(*) FROM work_order",Long.class)).isEqualTo(liveCount);
        var download=mvc.perform(get(path+"/source").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(source);
    }

    private byte[] fullHistoricalFixture() throws Exception {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new java.io.ByteArrayOutputStream()) {
            var sheet=book.createSheet("导入文档");
            sheet.createRow(0).createCell(0).setCellValue("合成历史档案");
            var fields=List.of("序号","保司","机构","定损员","案件发出日期","车主电话","车主姓名","案件号","电动车车牌号","电动车品牌","电动车车型","报价配件商","电动车主意愿","受损部位","单位","配件商报价金额","cp报价金额","网点名称","省","市","区","详细地址","联系人","联系电话","车主-报价金额","配件商-报价金额","创配-报价金额","最终定损金额","基本毛利率","是否为低毛利率案件","施工是否完成","施工完成日期","是否打款","打款日期","打款金额","是否与配件商结算","结算门店名称","结算日期","结算价格","实际毛利率","整车照片","备注","负责人","协同人","创建人","创建时间","更新时间","最后跟进时间","分配时间","是否归档");
            for(int r=1;r<=3;r++)sheet.createRow(r);
            for(int c=0;c<fields.size();c++)sheet.getRow(c>=18&&c<=21?3:c>=13&&c<=23?2:1).createCell(c).setCellValue(fields.get(c));
            for(int i=0;i<4;i++) {
                var row=sheet.createRow(4+i);
                String suffix=i<2?"01":i==2?"SKIP":"02";
                row.createCell(0).setCellValue("TEST-ORDER-"+suffix);
                row.createCell(5).setCellValue("13800000000");
                row.createCell(6).setCellValue("合成车主");
                row.createCell(7).setCellValue("TEST-CASE-"+suffix);
                row.createCell(9).setCellValue("合成品牌"+suffix);
                row.createCell(13).setCellValue(i==1?"工时费":"合成泵");
                row.createCell(14).setCellValue(i==1?"/":"个");
                row.createCell(15).setCellValue(i==0?0:22);
                row.createCell(16).setCellValue(i==0?95:30);
                if(i==0||i==3)row.createCell(17).setCellValue("合成维修网点"+suffix);
            }
            book.write(output);return output.toByteArray();
        }
    }
}
