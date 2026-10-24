package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class AmapMapProviderTest {
    private ch.qos.logback.classic.Logger diagnosticLogger;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> diagnosticLogs;
    @BeforeEach void captureDiagnostics(){
        diagnosticLogger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(AmapMapProvider.class);
        diagnosticLogs=new ch.qos.logback.core.read.ListAppender<>();diagnosticLogs.start();diagnosticLogger.addAppender(diagnosticLogs);
    }
    @AfterEach void releaseDiagnostics(){diagnosticLogger.detachAppender(diagnosticLogs);diagnosticLogs.stop();org.slf4j.MDC.clear();}

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<String> body=new AtomicReference<>();
    private final AtomicReference<String> query=new AtomicReference<>();
    private volatile int status=200;
    private volatile boolean slow;
    private final String secret=UUID.randomUUID().toString();
    private final MapProvider.Point vehicle=new MapProvider.Point(23.1,113.1),shop=new MapProvider.Point(23.2,113.2);
    private final MapProvider.Address address=new MapProvider.Address("广州市","440100","天河区","440106","合成测试街道10号");

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",e->{calls.incrementAndGet();query.set(e.getRequestURI().getRawQuery());
            if(slow)try{Thread.sleep(400);}catch(InterruptedException error){Thread.currentThread().interrupt();}
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
            try{e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}catch(java.io.IOException ignored){}finally{e.close();}});server.start();
        body.set(geo("广东省","广州市","天河区","440106","门牌号"));
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    private AmapMapProvider provider(int budget,int rate,long timeout){return new AmapMapProvider(secret,new ObjectMapper(),budget,rate,"http://127.0.0.1:"+server.getAddress().getPort(),new OkHttpClient.Builder().callTimeout(timeout,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).followRedirects(false).build());}
    private AmapMapProvider provider(){return provider(100,100,2000);}
    private static String geo(String province,String city,String district,String adcode,String level){return "{\"status\":\"1\",\"infocode\":\"10000\",\"geocodes\":[{\"formatted_address\":\"合成测试地点\",\"province\":\""+province+"\",\"city\":\""+city+"\",\"district\":\""+district+"\",\"adcode\":\""+adcode+"\",\"level\":\""+level+"\",\"location\":\"113.1,23.1\"}]}";}
    private static String route(String from,String to,String distance){return "{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{\"origin\":\""+from+"\",\"destination\":\""+to+"\",\"paths\":[{\"distance\":\""+distance+"\",\"cost\":{\"duration\":\"600\"}},{\"distance\":\"20\",\"cost\":{\"duration\":\"1\"}}]}}";}

    @Test void structuredCityAddressAndExplicitConfirmationAreRequired(){
        var p=provider();var result=p.resolve(address);assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo("REQUIRES_CONFIRMATION");assertThat(result.getFirst().mock()).isFalse();
        assertThat(result.getFirst().point()).isEqualTo(vehicle);assertThat(p.providerId()).isEqualTo("AMAP_WEB_V1");
        String decoded=java.net.URLDecoder.decode(query.get(),StandardCharsets.UTF_8);
        assertThat(decoded).contains("city=440100","address=广东省广州市天河区合成测试街道10号");
        assertThatThrownBy(()->p.search("广州")).hasMessageContaining("广东城市");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void sameNamedForeignProvinceAndCityDistrictConflictsCannotBeAccepted(){
        var p=provider();
        for(var input:List.of(geo("湖南省","广州市","天河区","430106","门牌号"),geo("广东省","深圳市","天河区","440106","门牌号"),geo("广东省","广州市","越秀区","440104","门牌号"))){body.set(input);assertThat(p.resolve(address).getFirst().status()).isEqualTo("REGION_MISMATCH");}
        body.set(geo("广东省","广州市","天河区","440106","区县"));assertThat(p.resolve(address).getFirst().status()).isEqualTo("NEEDS_DETAIL");
        body.set(geo("广东省","广州市","天河区","440106","道路"));assertThat(p.resolve(address).getFirst().status()).isEqualTo("NEEDS_DETAIL");
    }
    @Test void multipleResultsArePreservedAndNoUnconditionalFirstSelection(){
        var node=geo("广东省","广州市","天河区","440106","兴趣点");body.set(node.replace("]}",","+node.substring(node.indexOf("[{" )+1,node.length()-2)+"]}"));
        var results=provider().resolve(address);assertThat(results).hasSize(2);assertThat(results).allMatch(r->r.status().equals("REQUIRES_CONFIRMATION"));
    }
    @Test void missingDetailForeignInputAndInvalidCoordinatesFailBeforeNetwork(){
        var p=provider();
        assertThatThrownBy(()->p.resolve(new MapProvider.Address("广州市","440100","天河区","440106","上海市合成街道10号"))).hasMessageContaining("省外");
        assertThatThrownBy(()->p.resolve(new MapProvider.Address("广州市","440100","天河区","440106","天河"))).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThatThrownBy(()->p.resolve(new MapProvider.Address("广州市","440100","天河区","440305","合成地址10号"))).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThatThrownBy(()->p.locate(new MapProvider.Point(Double.NaN,113))).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThat(calls.get()).isZero();
    }
    @Test void reverseRejectsForeignCoordinatesAndArrayTypedMissingFields(){
        body.set("{\"status\":\"1\",\"infocode\":\"10000\",\"regeocode\":{\"formatted_address\":\"合成地点\",\"addressComponent\":{\"province\":\"湖南省\",\"city\":[],\"adcode\":\"430100\"}}}");
        assertThatThrownBy(()->provider().locate(vehicle)).extracting("code").isEqualTo("MAP_REGION_MISMATCH");
        body.set("{\"status\":\"1\",\"infocode\":\"10000\",\"regeocode\":{\"formatted_address\":\"合成地点\",\"addressComponent\":{\"province\":\"广东省\",\"city\":\"广州市\",\"district\":\"天河区\",\"adcode\":\"440106\"}}}");
        assertThat(provider().locate(vehicle).adcode()).isEqualTo("440106");assertThat(query.get()).contains("location=113.100000%2C23.100000");
    }
    @Test void drivingPreservesDirectionAndPrimaryStrategyWithoutSelectingFastestAlternative(){
        body.set(route("113.1,23.1","113.2,23.2","12000"));var p=provider();var result=p.driving(vehicle,shop,32);
        assertThat(result.metres()).isEqualTo(12000);assertThat(result.seconds()).isEqualTo(600);assertThat(result.alternative()).isZero();assertThat(result.mock()).isFalse();
        assertThat(query.get()).contains("origin=113.100000%2C23.100000","destination=113.200000%2C23.200000","strategy=32","show_fields=cost");
        body.set(route("113.2,23.2","113.1,23.1","17000"));assertThat(p.driving(shop,vehicle,32).metres()).isEqualTo(17000);
        assertThatThrownBy(()->p.driving(vehicle,shop,32)).extracting("code").isEqualTo("MAP_BAD_RESPONSE");
    }
    @Test void noRouteAndMissingOrNegativeDistanceNeverBecomeZero(){
        body.set("{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{\"origin\":\"113.1,23.1\",\"destination\":\"113.2,23.2\",\"paths\":[]}}");
        assertThatThrownBy(()->provider().driving(vehicle,shop,32)).extracting("code").isEqualTo("MAP_NO_ROUTE");
        for(String amount:List.of("","-1","1.2","9223372036854775808")){body.set(route("113.1,23.1","113.2,23.2",amount));assertThatThrownBy(()->provider().driving(vehicle,shop,32)).extracting("code").isEqualTo("MAP_BAD_RESPONSE");}
    }
    @Test void errorsAreSafeDistinctAndNeverRetriedOrChangedToMock(){
        var p=provider();
        for(var item:Map.of("10001","CREDENTIALS","10002","PERMISSION","10003","QUOTA","10004","RATE_LIMIT","10007","PERMISSION","10020","RATE_LIMIT").entrySet()){
            body.set("{\"status\":\"0\",\"infocode\":\""+item.getKey()+"\",\"info\":\""+secret+"\"}");
            var error=catchThrowable(()->p.resolve(address));assertThat(error).isInstanceOf(MapFailure.class).hasCause(null).hasMessageNotContaining(secret);assertThat(((MapFailure)error).code()).isEqualTo("MAP_"+item.getValue());
        }assertThat(calls.get()).isEqualTo(6);
        status=429;assertThatThrownBy(()->p.resolve(address)).extracting("code").isEqualTo("MAP_RATE_LIMIT");
        status=503;assertThatThrownBy(()->p.resolve(address)).extracting("code").isEqualTo("MAP_UPSTREAM");
    }
    @Test void boundedBodyMalformedEmptyAndRedirectFailSafely(){
        var p=provider();for(String input:List.of("not json","null","[]","{\"status\":\"1\",\"infocode\":\"10000\",\"geocodes\":[]}","x".repeat(1024*1024+1))){body.set(input);assertThatThrownBy(()->p.resolve(address)).isInstanceOf(MapFailure.class).hasCause(null).hasMessageNotContaining(secret);}
        status=302;assertThatThrownBy(()->p.resolve(address)).extracting("code").isEqualTo("MAP_UPSTREAM");assertThat(calls.get()).isEqualTo(6);
    }
    @Test void timeoutDoesNotRetryPotentiallyChargedRequest(){slow=true;assertThatThrownBy(()->provider(10,100,150).resolve(address)).extracting("code").isEqualTo("MAP_TIMEOUT");assertThat(calls.get()).isEqualTo(1);}
    @Test void processBudgetIsAtomicAndZeroMeansNoRealCalls() throws Exception {
        var disabled=provider(0,100,2000);assertThatThrownBy(()->disabled.resolve(address)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");assertThat(calls.get()).isZero();
        var p=provider(2,100,2000);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var jobs=new ArrayList<Future<?>>();for(int n=0;n<8;n++)jobs.add(pool.submit(()->{try{p.resolve(address);}catch(MapFailure error){assertThat(error.code()).isEqualTo("MAP_LOCAL_BUDGET");}}));for(var job:jobs)job.get();}
        assertThat(p.requestCount()).isEqualTo(2);assertThat(calls.get()).isEqualTo(2);
    }
    @Test void localRateLimitRejectsWithoutNetworkOrSleep(){var p=provider(10,1,2000);p.resolve(address);assertThatThrownBy(()->p.resolve(address)).extracting("code").isEqualTo("MAP_RATE_LIMIT");assertThat(calls.get()).isEqualTo(1);}
    @Test void mockRetainsStraightLineLabelAndCannotPretendToBeAmap(){var mock=new MockMapProvider();assertThat(mock.distance(vehicle,shop).method()).isEqualTo("MOCK_STRAIGHT_LINE_NOT_ROUTE");assertThat(mock.distance(vehicle,shop).mock()).isTrue();assertThatThrownBy(()->mock.driving(vehicle,shop,32)).extracting("code").isEqualTo("MAP_UNSUPPORTED_OPERATION");assertThatThrownBy(()->new AmapMapProvider("",new ObjectMapper(),0,1)).hasMessageNotContaining(secret);}
    @Test void officialQpsBalanceRouteAndUnknownErrorCodesHaveDistinctMeaning(){
        var p=provider();for(var scenario:Map.of("10014","RATE_LIMIT","10029","RATE_LIMIT","40000","QUOTA","40002","PERMISSION","20801","NO_ROUTE","20800","REGION_MISMATCH","20000","INVALID_REQUEST","20003","UPSTREAM").entrySet()){
            body.set("{\"status\":\"0\",\"infocode\":\""+scenario.getKey()+"\"}");assertThatThrownBy(()->p.resolve(address)).extracting("code").isEqualTo("MAP_"+scenario.getValue());
        }assertThat(calls.get()).isEqualTo(8);
    }

    @Test void failureDiagnosticsContainOnlyControlledMetadataAndNeverSensitiveInput(){
        org.slf4j.MDC.put("traceId","synthetic-map-trace");
        body.set("{\"status\":\"0\",\"infocode\":\"10001\",\"info\":\""+secret+" 原始响应及私密地址\"}");
        assertThatThrownBy(()->provider().resolve(address)).extracting("code").isEqualTo("MAP_CREDENTIALS");
        assertThat(diagnosticLogs.list).hasSize(1);
        var event=diagnosticLogs.list.getFirst();
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).contains("operation=GEOCODE","code=MAP_CREDENTIALS","httpStatus=200","infocode=10001","traceId=synthetic-map-trace")
            .containsPattern("elapsedMs=[0-9]+")
            .doesNotContain(secret,address.detail(),"原始响应","私密地址","http://","key=","113.1");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void parsingAndLocalRejectionAreDiagnosedOnceWithoutLoggingRawContent(){
        org.slf4j.MDC.put("traceId","untrusted\nprivate");
        body.set("{\"status\":\"1\",\"infocode\":\"10000\",\"geocodes\":[]}");
        assertThatThrownBy(()->provider().resolve(address)).extracting("code").isEqualTo("MAP_NO_RESULT");
        body.set("{\"status\":\"0\",\"infocode\":\""+secret+"\",\"info\":\"private response\"}");
        assertThatThrownBy(()->provider().resolve(address)).extracting("code").isEqualTo("MAP_UPSTREAM");
        assertThatThrownBy(()->provider(0,100,2000).resolve(address)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        assertThat(diagnosticLogs.list).hasSize(3);
        assertThat(diagnosticLogs.list.get(0).getFormattedMessage()).contains("code=MAP_NO_RESULT","infocode=10000");
        assertThat(diagnosticLogs.list.get(1).getFormattedMessage()).contains("infocode=-");
        assertThat(diagnosticLogs.list.get(2).getFormattedMessage()).contains("httpStatus=0","code=MAP_LOCAL_BUDGET");
        assertThat(diagnosticLogs.list).allSatisfy(e->assertThat(e.getFormattedMessage()).contains("traceId=-").doesNotContain(secret,"private",address.detail()));
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void timeoutAndNoRouteDiagnosticsPreserveOperationAndDoNotRetry(){
        slow=true;
        assertThatThrownBy(()->provider(100,100,50).resolve(address)).extracting("code").isEqualTo("MAP_TIMEOUT");
        slow=false;body.set("{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{\"origin\":\"113.1,23.1\",\"destination\":\"113.2,23.2\",\"paths\":[]}}");
        assertThatThrownBy(()->provider().driving(vehicle,shop,32)).extracting("code").isEqualTo("MAP_NO_ROUTE");
        assertThat(diagnosticLogs.list).hasSize(2);
        assertThat(diagnosticLogs.list.get(0).getFormattedMessage()).contains("operation=GEOCODE","code=MAP_TIMEOUT");
        assertThat(diagnosticLogs.list.get(1).getFormattedMessage()).contains("operation=DRIVING","code=MAP_NO_ROUTE");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void networkDiagnosticsNeverIncludeTransportExceptionMessage(){
        var client=new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{throw new java.io.IOException(secret+" private-address");}).build();
        var p=new AmapMapProvider(secret,new ObjectMapper(),1,1,"http://127.0.0.1:"+server.getAddress().getPort(),client);
        assertThatThrownBy(()->p.resolve(address)).extracting("code").isEqualTo("MAP_NETWORK");
        assertThat(diagnosticLogs.list).hasSize(1);
        assertThat(diagnosticLogs.list.getFirst().getFormattedMessage()).contains("code=MAP_NETWORK","operation=GEOCODE").doesNotContain(secret,"private-address");
        assertThat(diagnosticLogs.list.getFirst().getThrowableProxy()).isNull();
        assertThat(p.requestCount()).isEqualTo(1);assertThat(calls.get()).isZero();
    }

    @Test void diagnosticsPreserveLedgerRejectionAndStopRulesBeforeNetwork(){
        var ledger=org.mockito.Mockito.mock(MapCallBudget.class);
        org.mockito.Mockito.doThrow(MapFailure.of("LOCAL_BUDGET")).when(ledger).reserve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(()->provider().withLedger(ledger).resolve(address)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        org.mockito.Mockito.verify(ledger,org.mockito.Mockito.never()).stopFor(org.mockito.ArgumentMatchers.anyString());
        assertThat(calls.get()).isZero();
        assertThat(diagnosticLogs.list).hasSize(1);
        org.mockito.Mockito.reset(ledger);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-ledger-failure")).when(ledger).reserve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(()->provider().withLedger(ledger).resolve(address)).isInstanceOf(IllegalStateException.class).hasMessage("synthetic-ledger-failure");
        assertThat(calls.get()).isZero();assertThat(diagnosticLogs.list).hasSize(1);
    }
}
