package com.evinsurance.platform.integration.ocr;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class BaiduOcrProviderTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;private ExecutorService executor;
    private final AtomicInteger auth=new AtomicInteger(),calls=new AtomicInteger();
    private final AtomicReference<String> response=new AtomicReference<>("{\"log_id\":18446744073709551615,\"words_result\":[{\"words\":\"客户姓名：合成客户甲\",\"location\":{\"left\":10,\"top\":20,\"width\":100,\"height\":15},\"probability\":{\"average\":0.72}}]}");
    private volatile boolean rejectToken,slow;
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
    private final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/token",e->{auth.incrementAndGet();e.getRequestBody().readAllBytes();byte[] bytes=("{\"access_token\":\""+UUID.randomUUID()+"\",\"expires_in\":120}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/ocr",e->{int n=calls.incrementAndGet();e.getRequestBody().readAllBytes();if(slow)try{Thread.sleep(300);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}String body=rejectToken?(n==1?"{\"error_code\":111}":"{\"error_code\":110}"):response.get();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);try{e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}catch(IOException ignored){}finally{e.close();}});server.start();
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    private BaiduOcrProvider provider(long timeout){String base="http://127.0.0.1:"+server.getAddress().getPort();return new BaiduOcrProvider(UUID.randomUUID().toString(),UUID.randomUUID().toString(),json,base+"/token",base+"/ocr",clock,new OkHttpClient.Builder().callTimeout(timeout,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build());}
    static byte[] image(int width,int height,boolean blank) throws IOException {var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);if(!blank)image.setRGB(width/2,height/2,0xffffff);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    @Test void rawTextCoordinatesActualConfidenceAndBigLogIdStayDistinct() throws Exception {
        var result=provider(2000).recognize("SHOP_QUOTE",image(30,30,false),false);
        assertThat(result.logId()).isEqualTo("18446744073709551615");assertThat(new String(result.raw(),StandardCharsets.UTF_8)).isEqualTo(response.get());
        var candidate=json.valueToTree(result.candidate());assertThat(candidate.path("mock").asBoolean()).isFalse();assertThat(candidate.path("fields").path("ownerName").asString()).isEqualTo("合成客户甲");assertThat(candidate.path("lines").get(0).path("probability").path("average").asDouble()).isEqualTo(0.72);assertThat(candidate.has("confidence")).isFalse();assertThat(candidate.path("items").isEmpty()).isTrue();
    }
    @Test void concurrentRequestsShareTokenAndExpiryRefreshesEarly() throws Exception {
        var p=provider(4000);byte[] image=image(30,30,false);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var results=new ArrayList<Future<?>>();for(int n=0;n<8;n++)results.add(pool.submit(()->p.extract("NOTICE",image,false)));for(var f:results)f.get();}
        assertThat(auth.get()).isEqualTo(1);assertThat(calls.get()).isEqualTo(8);now.set(now.get().plusSeconds(110));p.extract("NOTICE",image,false);assertThat(auth.get()).isEqualTo(2);
    }
    @Test void invalidTokenRefreshesOnlyOnceAndNeverFallsBackToMock() throws Exception {rejectToken=true;assertThatThrownBy(()->provider(2000).extract("NOTICE",image(30,30,false),false)).isInstanceOf(OcrFailure.class).hasMessage("BAIDU_AUTH");assertThat(auth.get()).isEqualTo(2);assertThat(calls.get()).isEqualTo(2);}
    @Test void liveVerificationCanDisableTokenRetryAndEnforceTwoCallBudget() throws Exception {
        String base="http://127.0.0.1:"+server.getAddress().getPort();var p=new BaiduOcrProvider(UUID.randomUUID().toString(),UUID.randomUUID().toString(),json,base+"/token",base+"/ocr",clock,new OkHttpClient.Builder().retryOnConnectionFailure(false).build(),false,2);
        rejectToken=true;assertThatThrownBy(()->p.extract("NOTICE",image(30,30,false),false)).hasMessage("BAIDU_AUTH");assertThat(calls.get()).isEqualTo(1);assertThat(auth.get()).isEqualTo(1);
        rejectToken=false;p.extract("NOTICE",image(30,30,false),false);assertThatThrownBy(()->p.extract("NOTICE",image(30,30,false),false)).hasMessage("BAIDU_LOCAL_BUDGET");assertThat(calls.get()).isEqualTo(2);assertThat(p.requestCounts()).containsEntry("authentication",1L).containsEntry("ocr",2L);
    }
    @Test void quotaRateLimitMalformedAndEmptyHaveSafeDistinctFailures() throws Exception {
        var p=provider(2000);byte[] image=image(30,30,false);
        for(var scenario:Map.of("{\"error_code\":17}","BAIDU_QUOTA","{\"error_code\":18}","BAIDU_RATE_LIMIT","{\"error_code\":216201}","BAIDU_FORMAT","{}","BAIDU_EMPTY_RESULT","not-json","BAIDU_BAD_RESPONSE").entrySet()){
            response.set(scenario.getKey());assertThatThrownBy(()->p.extract("NOTICE",image,false)).hasMessage(scenario.getValue()).hasCause(null);
        }assertThat(calls.get()).isEqualTo(5);assertThat(auth.get()).isEqualTo(1);
    }
    @Test void timeoutDoesNotAutomaticallyRepeatPotentiallyChargedRequest() throws Exception {slow=true;assertThatThrownBy(()->provider(150).extract("NOTICE",image(30,30,false),false)).hasMessage("BAIDU_TIMEOUT_POSSIBLY_CHARGED").hasCause(null);assertThat(calls.get()).isEqualTo(1);}
    @Test void rejectsBlankDamagedUnsupportedAndSmallBeforeCallingVendor() throws Exception {
        var p=provider(2000);
        assertThatThrownBy(()->p.extract("NOTICE",image(30,30,true),false)).hasMessage("BAIDU_IMAGE_BLANK");
        assertThatThrownBy(()->p.extract("NOTICE",image(14,30,false),false)).hasMessage("BAIDU_IMAGE_DIMENSIONS");
        assertThatThrownBy(()->p.extract("NOTICE","%PDF-1.7".getBytes(StandardCharsets.UTF_8),false)).hasMessage("BAIDU_IMAGE_FORMAT");
        byte[] valid=image(30,30,false);assertThatThrownBy(()->p.extract("NOTICE",Arrays.copyOf(valid,valid.length-10),false)).hasMessage("BAIDU_IMAGE_DAMAGED");assertThat(auth.get()).isZero();assertThat(calls.get()).isZero();
    }
    @Test void noCredentialsFailExplicitlyAndNeverUseMock(){assertThatThrownBy(()->new BaiduOcrProvider("",null,json)).hasMessage("Baidu OCR credentials are required");}
    @Test void completeJpegWithApplicationTrailerIsPreservedWhileTruncationIsRejected() throws Exception {
        var bitmap=new java.awt.image.BufferedImage(30,30,java.awt.image.BufferedImage.TYPE_INT_RGB);bitmap.setRGB(15,15,0xffffff);var output=new ByteArrayOutputStream();ImageIO.write(bitmap,"jpeg",output);byte[] original=output.toByteArray();byte[] withTrailer=Arrays.copyOf(original,original.length+24);Arrays.fill(withTrailer,original.length,withTrailer.length,(byte)42);byte[] snapshot=withTrailer.clone();
        assertThatCode(()->BaiduImage.validate(withTrailer)).doesNotThrowAnyException();assertThat(withTrailer).isEqualTo(snapshot);
        assertThatThrownBy(()->BaiduImage.validate(Arrays.copyOf(original,original.length-2))).hasMessage("BAIDU_IMAGE_DAMAGED");
    }
    @Test void missingQuantityLetterLAndAmbiguousRowsAreNotInvented(){
        var response=json.readTree("{\"words_result\":[{\"words\":\"配件名称：合成轮胎 数量：l 单价：10.00 金额：20.00\"},{\"words\":\"零散部件 25\"},{\"words\":\"维修单号：DOC-TEST 单据日期：2026-10-13\"},{\"words\":\"事故时间：2026-10\"}]}");
        var result=json.valueToTree(new HandwritingCandidates().extract(response,"SHOP_QUOTE"));assertThat(result.path("items").get(0).path("quantity").asString()).isEmpty();assertThat(result.path("items").get(0).path("quantityOriginal").asString()).isEqualTo("l");assertThat(result.path("items").size()).isEqualTo(1);assertThat(result.path("fields").has("claimNo")).isFalse();assertThat(result.path("fields").has("accidentAt")).isFalse();assertThat(result.path("fields").path("documentNo").asString()).isEqualTo("DOC-TEST");assertThat(result.path("warnings").size()).isGreaterThan(1);
    }
    @Test void conflictingNamesDatesAndAmountsRequireHumanReview(){
        var response=json.readTree("{\"words_result\":[{\"words\":\"客户姓名：合成甲\"},{\"words\":\"车主姓名：合成乙\"},{\"words\":\"事故时间：2026-02-30 09:00\"},{\"words\":\"配件名称：合成件 数量：2 单价：10.00 金额：25.00\"},{\"words\":\"合计：30.00\"}]}");
        var result=json.valueToTree(new HandwritingCandidates().extract(response,"SHOP_QUOTE"));assertThat(result.path("fields").has("ownerName")).isFalse();assertThat(result.path("fields").has("accidentAt")).isFalse();assertThat(result.path("items").get(0).path("calculatedAmount").asString()).isEqualTo("20.00");assertThat(result.path("warnings").toString()).contains("不符","不同值");
    }
    @Test void coordinateLabelHasToHaveExactlyOneAlignedValue(){
        var unique=json.readTree("{\"words_result\":[{\"words\":\"客户姓名\",\"location\":{\"left\":10,\"top\":10,\"width\":80,\"height\":20}},{\"words\":\"合成甲\",\"location\":{\"left\":100,\"top\":11,\"width\":50,\"height\":20}}]}");
        var candidate=json.valueToTree(new HandwritingCandidates().extract(unique,"SHOP_QUOTE"));assertThat(candidate.path("fields").path("ownerName").asString()).isEqualTo("合成甲");assertThat(candidate.path("evidence").path("ownerName").get(0).path("labelEvidence").path("line").asInt()).isZero();
        var array=(tools.jackson.databind.node.ArrayNode)unique.path("words_result");array.add(json.readTree("{\"words\":\"合成乙\",\"location\":{\"left\":155,\"top\":11,\"width\":50,\"height\":20}}"));
        var ambiguous=json.valueToTree(new HandwritingCandidates().extract(unique,"SHOP_QUOTE"));assertThat(ambiguous.path("fields").has("ownerName")).isFalse();assertThat(ambiguous.path("warnings").toString()).contains("未强行配对");
    }
    @Test void tallFollowingRowIsNotAConflictingOwnerValue(){
        var response=json.createObjectNode();var words=response.putArray("words_result");
        words.add(box("客户名称",20,100,100,24));words.add(box("合成车主甲",140,87,120,50));
        words.add(box("维修名称",20,145,100,24));words.add(box("合成维修项目",140,107,180,90));
        var result=json.valueToTree(new HandwritingCandidates().extract(response,"SHOP_QUOTE"));
        assertThat(result.path("fields").path("ownerName").asString()).isEqualTo("合成车主甲");
        assertThat(result.path("evidence").path("ownerName").size()).isEqualTo(1);
        assertThat(result.path("evidence").path("ownerName").get(0).path("line").asInt()).isEqualTo(1);
        assertThat(result.path("ruleVersion").asString()).isEqualTo("LABELLED_COORDINATES_3");
        assertThat(result.path("lines").get(3).path("text").asString()).isEqualTo("合成维修项目");
    }
    @Test void broadBoxBetweenRowsIsNotAssignedToEitherOwnerLabel(){
        var response=json.createObjectNode();var words=response.putArray("words_result");
        words.add(box("客户名称",20,100,100,24));words.add(box("车主姓名",20,180,100,24));
        words.add(box("合成歧义文字",140,110,160,80));
        var result=json.valueToTree(new HandwritingCandidates().extract(response,"NOTICE"));
        assertThat(result.path("fields").has("ownerName")).isFalse();
    }
    private JsonNode box(String text,int x,int y,int width,int height){return json.valueToTree(Map.of("words",text,"location",Map.of("left",x,"top",y,"width",width,"height",height)));}
    @Test void coordinateRepairRowsKeepMissingQuantityAndLetterLUnchanged(){
        var root=json.createObjectNode();var words=root.putArray("words_result");
        words.add(box("商品名称",20,20,130,20));words.add(box("数量",200,20,60,20));words.add(box("单价",300,20,60,20));words.add(box("金额",400,20,60,20));
        words.add(box("合成部件甲",20,60,130,20));words.add(box("l",200,60,60,20));words.add(box("10.00",300,60,60,20));words.add(box("20.00",400,60,60,20));
        words.add(box("合成部件乙",20,100,130,20));words.add(box("5.00",300,100,60,20));words.add(box("5.00",400,100,60,20));
        words.add(box("维修金额：",20,140,130,20));words.add(box("付款方式：",20,180,130,20));words.add(box("客户签名：",20,220,130,20));words.add(box("共计25.00",300,140,130,20));
        var result=json.valueToTree(new HandwritingCandidates().extract(root,"SHOP_QUOTE"));assertThat(result.path("items").size()).isEqualTo(2);assertThat(result.path("items").get(0).path("quantity").asString()).isEmpty();assertThat(result.path("items").get(0).path("quantityOriginal").asString()).isEqualTo("l");assertThat(result.path("items").get(1).path("quantity").asString()).isEmpty();assertThat(result.path("items").get(0).path("unitPrice").asString()).isEqualTo("10.00");assertThat(result.path("items").get(0).path("origin").asString()).isEqualTo("PROGRAM_COORDINATE_TABLE_V1");
    }
    @Test void crossRowNumericCellsAndUnqualifiedPhoneAreNotForceMatched(){
        var root=json.createObjectNode();var words=root.putArray("words_result");
        words.add(box("商品名称",20,20,130,20));words.add(box("数量",200,20,60,20));words.add(box("单价",300,20,60,20));words.add(box("金额",400,20,60,20));
        words.add(box("合成部件甲",20,60,130,60));words.add(box("合成部件乙",20,100,130,30));words.add(box("2",200,100,60,20));
        var result=json.valueToTree(new HandwritingCandidates().extract(root,"SHOP_QUOTE"));assertThat(result.path("items").size()).isEqualTo(2);for(var item:result.path("items"))assertThat(item.path("quantity").asString()).isEmpty();assertThat(result.path("warnings").toString()).contains("跨行歧义");
        var contact=json.createObjectNode();var contacts=contact.putArray("words_result");contacts.add(box("客户名称",10,10,80,20));contacts.add(box("合成客户",100,10,80,20));contacts.add(box("联系电话",10,60,80,20));contacts.add(box("13800000000",100,60,80,20));var mapped=json.valueToTree(new HandwritingCandidates().extract(contact,"SHOP_QUOTE"));assertThat(mapped.path("fields").path("ownerName").asString()).isEqualTo("合成客户");assertThat(mapped.path("fields").has("ownerPhone")).isFalse();
    }
}
