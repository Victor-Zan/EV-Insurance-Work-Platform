package com.evinsurance.platform.integration.ocr;

import java.io.*;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import tools.jackson.databind.*;

public final class BaiduOcrProvider implements OcrProvider {
    private static final int RESPONSE_LIMIT=2*1024*1024;
    private final String key,secret,authUrl,ocrUrl;
    private final ObjectMapper json;
    private final OkHttpClient client;
    private final Clock clock;
    private String token;
    private Instant tokenUntil=Instant.EPOCH;
    private boolean tokenRetryEnabled=true;
    private int callBudget;
    private final java.util.concurrent.atomic.AtomicLong authenticationRequests=new java.util.concurrent.atomic.AtomicLong(),ocrRequests=new java.util.concurrent.atomic.AtomicLong();
    public BaiduOcrProvider(String key,String secret,ObjectMapper json){this(key,secret,json,"https://aip.baidubce.com/oauth/2.0/token","https://aip.baidubce.com/rest/2.0/ocr/v1/handwriting",Clock.systemUTC(),new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build());}
    public BaiduOcrProvider(String key,String secret,ObjectMapper json,boolean tokenRetryEnabled,int callBudget){this(key,secret,json);if(callBudget<0)throw new IllegalArgumentException("Invalid Baidu process call budget");this.tokenRetryEnabled=tokenRetryEnabled;this.callBudget=callBudget;}
    // Package-private injection is only for an offline loopback HTTP test, not arbitrary production URL fetching.
    BaiduOcrProvider(String key,String secret,ObjectMapper json,String authUrl,String ocrUrl,Clock clock,OkHttpClient client){
        if(key==null||key.isBlank()||secret==null||secret.isBlank())throw new IllegalStateException("Baidu OCR credentials are required");
        this.key=key;this.secret=secret;this.json=json;this.authUrl=authUrl;this.ocrUrl=ocrUrl;this.clock=clock;this.client=client;
    }
    BaiduOcrProvider(String key,String secret,ObjectMapper json,String authUrl,String ocrUrl,Clock clock,OkHttpClient client,boolean tokenRetryEnabled,int callBudget){this(key,secret,json,authUrl,ocrUrl,clock,client);if(callBudget<0)throw new IllegalArgumentException("Invalid Baidu process call budget");this.tokenRetryEnabled=tokenRetryEnabled;this.callBudget=callBudget;}
    public String id(){return "BAIDU_HANDWRITING_V1";}
    public String api(){return "ocr/v1/handwriting";}
    public String version(){return "adapter-1";}
    public Map<String,Long> requestCounts(){return Map.of("authentication",authenticationRequests.get(),"ocr",ocrRequests.get());}
    public Map<String,Object> extract(String category,byte[] content,boolean fail){return recognize(category,content,fail).candidate();}
    public Result recognize(String category,byte[] content,boolean fail){
        if(fail)throw new OcrFailure("BAIDU_SIMULATION_FORBIDDEN");
        BaiduImage.validate(content);
        var form=new FormBody.Builder().add("image",Base64.getEncoder().encodeToString(content)).add("probability","true").build();
        if(form.contentLength()>4L*1024*1024)throw new OcrFailure("BAIDU_ENCODED_SIZE");
        String used=accessToken(null);
        for(int attempt=0;attempt<2;attempt++){
            var url=Objects.requireNonNull(HttpUrl.parse(ocrUrl)).newBuilder().addQueryParameter("access_token",used).build();
            byte[] raw=request(new Request.Builder().url(url).post(form).build(),false);
            JsonNode result=parse(raw);int code=result.path("error_code").asInt(0);
            if(code==110||code==111){if(attempt==0&&tokenRetryEnabled){used=accessToken(used);continue;}throw new OcrFailure("BAIDU_AUTH");}
            if(code!=0)throw new OcrFailure(errorCode(code));
            if(!result.path("words_result").isArray()||result.path("words_result").isEmpty())throw new OcrFailure("BAIDU_EMPTY_RESULT");
            boolean hasWords=false;for(var word:result.path("words_result"))if(!word.path("words").asString("").isBlank()){hasWords=true;break;}if(!hasWords)throw new OcrFailure("BAIDU_EMPTY_RESULT");
            String logId=result.path("log_id").asString("");
            return new Result(new HandwritingCandidates().extract(result,category),raw,logId);
        }
        throw new OcrFailure("BAIDU_AUTH");
    }
    private synchronized String accessToken(String invalid){
        if(token!=null&&clock.instant().isBefore(tokenUntil)&&(invalid==null||!invalid.equals(token)))return token;
        var body=new FormBody.Builder().add("grant_type","client_credentials").add("client_id",key).add("client_secret",secret).build();
        var result=parse(request(new Request.Builder().url(authUrl).post(body).build(),true));
        String next=result.path("access_token").asString("");long seconds=result.path("expires_in").asLong(0);
        if(next.isBlank()||seconds<=0)throw new OcrFailure("BAIDU_AUTH");
        token=next;tokenUntil=clock.instant().plusSeconds(Math.max(1,seconds-Math.min(60,Math.max(1,seconds/10))));return token;
    }
    private byte[] request(Request request,boolean auth){
        if(auth)authenticationRequests.incrementAndGet();else{
            long count;do{count=ocrRequests.get();if(callBudget>0&&count>=callBudget)throw new OcrFailure("BAIDU_LOCAL_BUDGET");}while(!ocrRequests.compareAndSet(count,count+1));
        }
        try(var response=client.newCall(request).execute()){
            if(response.code()==429)throw new OcrFailure("BAIDU_RATE_LIMIT");
            if(!response.isSuccessful())throw new OcrFailure(auth?"BAIDU_AUTH":"BAIDU_HTTP");
            if(response.body()==null)throw new OcrFailure("BAIDU_BAD_RESPONSE");
            byte[] bytes=response.body().byteStream().readNBytes(RESPONSE_LIMIT+1);
            if(bytes.length>RESPONSE_LIMIT)throw new OcrFailure("BAIDU_RESPONSE_SIZE");return bytes;
        }catch(OcrFailure e){throw e;}catch(SocketTimeoutException e){throw new OcrFailure(auth?"BAIDU_AUTH_TIMEOUT":"BAIDU_TIMEOUT_POSSIBLY_CHARGED");}
        catch(InterruptedIOException e){throw new OcrFailure(auth?"BAIDU_AUTH_TIMEOUT":"BAIDU_TIMEOUT_POSSIBLY_CHARGED");}
        catch(IOException e){throw new OcrFailure(auth?"BAIDU_AUTH_NETWORK":"BAIDU_NETWORK_POSSIBLY_CHARGED");}
    }
    private JsonNode parse(byte[] raw){try{return json.readTree(raw);}catch(Exception e){throw new OcrFailure("BAIDU_BAD_RESPONSE");}}
    static String errorCode(int code){return switch(code){case 6,13 -> "BAIDU_AUTH";case 4,17,19 -> "BAIDU_QUOTA";case 18 -> "BAIDU_RATE_LIMIT";case 1,2,282000 -> "BAIDU_SYSTEM";default -> code>=216000&&code<217000||code==100?"BAIDU_FORMAT":"BAIDU_REJECTED";};}
}
