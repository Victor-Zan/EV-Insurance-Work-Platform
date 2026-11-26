package com.evinsurance.platform.document;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.evinsurance.platform.identity.infrastructure.JwtService;
import com.evinsurance.platform.identity.infrastructure.UserMapper;
import com.evinsurance.platform.integration.storage.MinioObjectStorage;
import io.minio.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

/** Actual private MinIO HTTP plus PostgreSQL; synthetic accounts and generated PNGs only. */
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("dev")
class PrivateStorageMiniappPostgresIT {
    private static final String SCHEMA="s08_files_"+UUID.randomUUID().toString().replace("-",""), BUCKET="s08-private-"+UUID.randomUUID();
    private static final boolean EXTERNAL=System.getenv("TEST_MINIO_EXECUTABLE")==null;
    private static final String ACCESS=EXTERNAL?Objects.requireNonNull(System.getenv("TEST_MINIO_ACCESS_KEY")):"s08_"+UUID.randomUUID().toString().replace("-",""), SECRET=EXTERNAL?Objects.requireNonNull(System.getenv("TEST_MINIO_SECRET_KEY")):UUID.randomUUID()+UUID.randomUUID().toString();
    private static final String SOURCE=EXTERNAL?Objects.requireNonNull(System.getenv("TEST_MINIO_ENDPOINT")):"http://127.0.0.1:19082", RESTORED=EXTERNAL?Objects.requireNonNull(System.getenv("TEST_MINIO_RESTORE_ENDPOINT")):"http://127.0.0.1:19083";
    private static final List<Process> CHILDREN=new ArrayList<>();
    private static Path artifacts;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        String url=com.evinsurance.platform.support.PostgresTestEnvironment.url();
        artifacts=Path.of(Objects.requireNonNull(System.getenv("TEST_ARTIFACT_DIR"))).toAbsolutePath();Files.createDirectories(artifacts);
        Runtime.getRuntime().addShutdownHook(new Thread(PrivateStorageMiniappPostgresIT::stopStorage));
        if(EXTERNAL){
            for(String endpoint:List.of(SOURCE,RESTORED)){var uri=URI.create(endpoint);if(!"http".equals(uri.getScheme())||!List.of("127.0.0.1","localhost").contains(uri.getHost()))throw new IllegalStateException("Storage tests require loopback-only services");}
            if(SOURCE.equals(RESTORED))throw new IllegalStateException("Restore must use a separate storage service");
        }else{startStorage("source",19082,19084);startStorage("restored",19083,19085);}
        Path seedDirectory=artifacts.resolve("dev-migration");Files.createDirectories(seedDirectory);
        try(var input=PrivateStorageMiniappPostgresIT.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")){Files.copy(Objects.requireNonNull(input),seedDirectory.resolve("V2.1__development_foundation.sql"),StandardCopyOption.REPLACE_EXISTING);}
        r.add("spring.flyway.locations",()->"classpath:db/migration,filesystem:"+seedDirectory);
        r.add("spring.datasource.url",()->com.evinsurance.platform.support.PostgresTestEnvironment.schemaUrl(SCHEMA));r.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);r.add("JWT_SECRET_BASE64",()->Base64.getEncoder().encodeToString(key));r.add("JWT_TTL_SECONDS",()->"1800");
        r.add("MINIO_ENDPOINT",()->SOURCE);r.add("MINIO_ACCESS_KEY",()->ACCESS);r.add("MINIO_SECRET_KEY",()->SECRET);r.add("MINIO_BUCKET",()->BUCKET);
        r.add("NOTIFICATION_POLL_MS",()->"86400000");r.add("OCR_WORKER_ENABLED",()->"false");r.add("WECHAT_LOGIN_ENABLED",()->"false");
        String password=UUID.randomUUID().toString();for(String role:List.of("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER"))r.add("DEV_"+role+"_PASSWORD",()->password);
    }
    private static void startStorage(String name,int port,int console) throws Exception {
        try(var socket=new java.net.ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",port));}
        Path directory=artifacts.resolve("minio-"+name);Files.createDirectories(directory);
        var builder=new ProcessBuilder(Objects.requireNonNull(System.getenv("TEST_MINIO_EXECUTABLE")),"server","--address","127.0.0.1:"+port,"--console-address","127.0.0.1:"+console,directory.toString());
        builder.environment().put("MINIO_ROOT_USER",ACCESS);builder.environment().put("MINIO_ROOT_PASSWORD",SECRET);builder.environment().put("MINIO_BROWSER","off");
        builder.redirectErrorStream(true).redirectOutput(artifacts.resolve("minio-"+name+".log").toFile());Process process=builder.start();CHILDREN.add(process);
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        for(int attempt=0;attempt<100;attempt++){
            if(!process.isAlive())throw new IllegalStateException("Task MinIO exited; inspect isolated log");
            try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/minio/health/live")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}catch(IOException ignored){}
            Thread.sleep(100);
        }throw new IllegalStateException("Task MinIO startup timed out");
    }
    private static synchronized void stopStorage(){for(var process:CHILDREN)if(process.isAlive()){process.destroy();try{if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    @AfterAll static void afterAll(){stopStorage();}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db; @Autowired UserMapper users; @Autowired JwtService jwt;
    private long user(String name){return users.accessByUsername(name).id();}
    private String token(String name){return jwt.issue(users.accessByUsername(name).current()).accessToken();}
    private JsonNode write(String token,String path,Object body,int expected) throws Exception {
        var response=mvc.perform(post("/api/v1/"+path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s: %s",path,response.getContentAsString()).isEqualTo(expected);return json.readTree(response.getContentAsString()).path("data");
    }
    private JsonNode upload(String token,UUID id,String category,UUID replace,String key,byte[] bytes) throws Exception {
        var request=multipart("/api/v1/materials/cases/"+id).file(new MockMultipartFile("file","generated.png","image/png",bytes)).param("category",category).param("assignmentVersion","3").header("Authorization","Bearer "+token).header("Idempotency-Key",key);
        if(replace!=null)request.param("replace",replace.toString());
        var response=mvc.perform(request).andReturn().getResponse();assertThat(response.getStatus()).as("Upload %s: %s",category,response.getContentAsString()).isEqualTo(200);return json.readTree(response.getContentAsString()).path("data");
    }
    private byte[] png(int color) throws Exception {var image=new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB);for(int x=0;x<12;x++)for(int y=0;y<12;y++)image.setRGB(x,y,color);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
    private String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private MinioClient client(String endpoint){return MinioClient.builder().endpoint(endpoint).credentials(ACCESS,SECRET).build();}
    private void anonymousDenied(String endpoint,String key)throws Exception{int status=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint+"/"+BUCKET+"/"+key)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();assertThat(status).isEqualTo(403);}
    private List<Map<String,Object>> metrics(JdbcTemplate jdbc){
        var tables=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=? ORDER BY tablename",String.class,SCHEMA);var parts=new ArrayList<String>();
        for(String table:tables){if(!table.matches("[a-z0-9_]+"))throw new IllegalStateException("Unexpected identifier");parts.add("SELECT '"+table+"' AS table_name,count(*) AS rows,md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) AS digest FROM "+SCHEMA+"."+table+" t");}
        return jdbc.queryForList(String.join(" UNION ALL ",parts)+" ORDER BY table_name");
    }
    private List<Map<String,Object>> sequences(JdbcTemplate jdbc){
        var names=jdbc.queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind='S' ORDER BY c.relname",String.class,SCHEMA);var result=new ArrayList<Map<String,Object>>();
        for(String name:names){if(!name.matches("[a-z0-9_]+"))throw new IllegalStateException("Unexpected sequence");var item=new LinkedHashMap<>(jdbc.queryForMap("SELECT last_value,is_called FROM "+SCHEMA+"."+name));item.put("name",name);result.add(item);}return result;
    }
    private void pg(String executable,Path file,boolean restore,String database) throws Exception {
        var address=com.evinsurance.platform.support.PostgresTestEnvironment.address();
        var command=new ArrayList<String>();
        String container=System.getenv("TEST_PG_CONTAINER");
        if(container!=null){if(!container.matches("[A-Za-z0-9_.-]+"))throw new IllegalStateException("Invalid isolated database container");command.addAll(List.of("docker","exec","-i","-e","PGPASSWORD",container,executable));}
        else command.add(Path.of(Objects.requireNonNull(System.getenv("TEST_PG_BIN")),executable+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).toString());
        command.addAll(List.of("-h",address.getHost(),"-p",String.valueOf(address.getPort()<0?5432:address.getPort()),"-U",System.getenv("TEST_DB_USERNAME"),"-d",database,"--no-owner","--no-privileges"));
        if(restore)command.add("--exit-on-error");else command.addAll(List.of("-Fc","--schema",SCHEMA));
        var builder=new ProcessBuilder(command).redirectError(artifacts.resolve(executable+".log").toFile());
        builder.environment().put("PGPASSWORD",System.getenv("TEST_DB_PASSWORD"));
        if(restore)builder.redirectInput(file.toFile());else builder.redirectOutput(file.toFile());
        var process=builder.start();assertThat(process.waitFor(40,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(executable+" log retained").isZero();
    }
    @Test void actualPrivateUploadDownloadFrozenEvidenceAndConsistentDatabaseObjectRestore() throws Exception {
        UUID id=UUID.randomUUID();String shop=token("dev_repair_shop"),staff=token("dev_customer_service"),owner=token("dev_owner");long staffId=user("dev_customer_service"),shopId=users.accessByUsername("dev_repair_shop").shopId();
        db.update("INSERT INTO work_order(id,business_no,claim_no,current_responsible_id,owner_user_id,owner_binding_status,owner_phone,vehicle_plate,shop_id,status,version,created_by,updated_by) VALUES(?,?,?,?,?,'BOUND','13800000000','S08-SYNTHETIC',?,'ARRIVED',1,?,?)",id,"S08-"+id.toString().substring(0,8),"SYNTHETIC-"+id,staffId,user("dev_owner"),shopId,staffId,staffId);
        db.update("INSERT INTO work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by) VALUES(?,3,?,'ACCEPTED',?)",id,shopId,staffId);
        byte[] original=png(0x598067),replacement=png(0x426081);Files.write(artifacts.resolve("generated-original.png"),original);
        String key=UUID.randomUUID().toString();var arrival=upload(shop,id,"ARRIVAL_PHOTO",null,key,original);assertThat(upload(shop,id,"ARRIVAL_PHOTO",null,key,original).path("id")).isEqualTo(arrival.path("id"));assertThat(arrival.has("objectKey")).isFalse();assertThat(arrival.has("sha256")).isFalse();
        UUID file=UUID.fromString(arrival.path("id").asText());String objectKey=db.queryForObject("SELECT object_key FROM case_file WHERE id=?",String.class,file);anonymousDenied(SOURCE,objectKey);
        mvc.perform(get("/api/v1/materials/"+file+"/download")).andExpect(status().isUnauthorized());
        var downloaded=mvc.perform(get("/api/v1/materials/"+file+"/download").header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();assertThat(downloaded).isEqualTo(original);
        long other=db.queryForObject("INSERT INTO app_user(username,display_name,password_hash,activated) VALUES(?,'Synthetic unrelated owner','not-a-password',true) RETURNING id",Long.class,"s08_"+UUID.randomUUID().toString().replace("-",""));users.addRole(other,"OWNER");users.ensureOwner(other,"Synthetic unrelated owner");String unrelated=jwt.issue(users.accessById(other).current()).accessToken();mvc.perform(get("/api/v1/materials/"+file+"/download").header("Authorization","Bearer "+unrelated)).andExpect(status().isForbidden());
        upload(staff,id,"SCENE_PHOTO",null,UUID.randomUUID().toString(),original);String base="quotations/cases/"+id;
        var c=write(shop,base+"/raw-quotes",Map.of("expectedVersion",0,"assignmentVersion",3,"lines",List.of(Map.of("description","Generated fixture part","quantity",1,"unitPrice","100.00"))),200);
        c=write(staff,base+"/formal-quotes",Map.of("expectedVersion",c.path("version").asInt(),"rawQuoteId",c.path("rawQuote").path("id").asText(),"mode","FIXED_AMOUNT","fixedAmount","0.00","reviewedLines",List.of(Map.of("description","Verified fixture part","quantity",1,"unitPrice","100.00"))),200);
        c=write(staff,base+"/assessments",Map.of("expectedVersion",c.path("version").asInt(),"amount","100.00"),200);c=write(staff,base+"/confirmations/INSURER",Map.of("expectedVersion",c.path("version").asInt()),200);write(staff,base+"/confirmations/CUSTOMER_SERVICE",Map.of("expectedVersion",c.path("version").asInt(),"assessmentId",c.path("assessment").path("id").asText(),"amount","100.00","originalReviewed",true),200);
        var progress=upload(shop,id,"PROGRESS_PHOTO",null,UUID.randomUUID().toString(),original);var next=upload(staff,id,"PROGRESS_PHOTO",UUID.fromString(progress.path("id").asText()),UUID.randomUUID().toString(),replacement);assertThat(next.path("version").asInt()).isEqualTo(2);
        var completion=upload(shop,id,"COMPLETION_PHOTO",null,UUID.randomUUID().toString(),original);String completionId=completion.path("id").asText();
        var finished=write(shop,"repairs/cases/"+id+"/completion",Map.of("expectedVersion",0,"assignmentVersion",3,"photoIds",List.of(completionId)),200);assertThat(finished.path("status").asText()).isEqualTo("WAITING_OWNER_CONFIRMATION");write(staff,"materials/"+completionId+"/void",Map.of(),409);
        var photo=mvc.perform(get("/api/v1/materials/"+completionId+"/download").header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();assertThat(photo).isEqualTo(original);
        // Writers are quiescent here; the isolated worker runs only once at startup then waits a day.
        long started=System.nanoTime();var before=metrics(db);var beforeSequences=sequences(db);Path dump=artifacts.resolve("database.dump");String username=System.getenv("TEST_DB_USERNAME");
        pg("pg_dump",dump,false,com.evinsurance.platform.support.PostgresTestEnvironment.address().getPath().substring(1));
        String restoredDb="s08_restore_"+UUID.randomUUID().toString().replace("-","");db.execute("CREATE DATABASE "+restoredDb);
        pg("pg_restore",dump,true,restoredDb);
        var recovered=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://"+com.evinsurance.platform.support.PostgresTestEnvironment.address().getRawAuthority()+"/"+restoredDb,username,System.getenv("TEST_DB_PASSWORD")));assertThat(metrics(recovered)).isEqualTo(before);assertThat(sequences(recovered)).isEqualTo(beforeSequences);
        var source=client(SOURCE);var destination=client(RESTORED);destination.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());var objects=new ArrayList<Map<String,Object>>();
        var records=db.queryForList("SELECT object_key,content_type,sha256,state,version_no FROM "+SCHEMA+".case_file ORDER BY object_key");Path backup=artifacts.resolve("objects");Files.createDirectories(backup);
        var restoredStorage=new MinioObjectStorage(RESTORED,ACCESS,SECRET,BUCKET);int index=0;
        for(var record:records){String object=(String)record.get("object_key"),type=(String)record.get("content_type");byte[] bytes;try(var input=source.getObject(GetObjectArgs.builder().bucket(BUCKET).object(object).build())){bytes=input.readAllBytes();}assertThat(digest(bytes)).isEqualTo(record.get("sha256"));String backupName="object-"+(index++)+".bin";Files.write(backup.resolve(backupName),bytes);destination.putObject(PutObjectArgs.builder().bucket(BUCKET).object(object).contentType(type).stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).build());try(var input=restoredStorage.read(object)){assertThat(input.readAllBytes()).isEqualTo(bytes);}objects.add(Map.of("objectKey",object,"backupFile",backupName,"sha256",digest(bytes),"logicalVersion",record.get("version_no"),"state",record.get("state")));}
        assertThat(objects).hasSize(5);assertThat(records.stream().anyMatch(item->"SUPERSEDED".equals(item.get("state")))).isTrue();anonymousDenied(RESTORED,objectKey);
        assertThat(recovered.queryForObject("SELECT count(*) FROM "+SCHEMA+".repair_completion_file f JOIN "+SCHEMA+".case_file c ON c.id=f.file_id AND c.version_no=f.version_no",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->recovered.update("UPDATE "+SCHEMA+".case_file SET state='VOID' WHERE id=?",UUID.fromString(completionId))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Files.writeString(artifacts.resolve("objects-manifest.json"),json.writeValueAsString(objects));
        Files.writeString(artifacts.resolve("result.json"),json.writeValueAsString(Map.of("kind","ISOLATED_POSTGRES_AND_REAL_MINIO","tablesCompared",before.size(),"sequencesCompared",beforeSequences.size(),"objectsRestored",objects.size(),"allDatabaseDigestsEqual",true,"oldLogicalVersionRestored",true,"frozenCompletionReferenceValid",true,"anonymousSourceAndRestoreDenied",true,"elapsedSeconds",(System.nanoTime()-started)/1_000_000_000.0,"realWechatHttpsOrDeviceVerified",false)));
    }
}
