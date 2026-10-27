package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** JS base-layer access only; no geocode, POI, routing or arbitrary URL proxy. */
@Service
public class JsMapService {
    public static final String PREFIX="/_AMapService",COOKIE="map_js_session";
    private static final int LIMIT=4*1024*1024;
    private final boolean enabled;private final String key,code,webBase,vectorBase;
    private final UserMapper users;private final Clock clock;private final OkHttpClient client;
    private final Map<String,Ticket> tickets=new HashMap<>();
    private static final class Ticket {
        final long actor,version;final Instant expires;int requests,windowRequests;long window;
        Ticket(CurrentUser user,Instant expires,long now){actor=user.id();version=user.authVersion();this.expires=expires;window=now;}
    }
    public record Configuration(boolean enabled,boolean configured,String reason){}
    public record Grant(String cookie,String publicKey,String servicePath,Instant expires){}
    public record Asset(byte[] bytes,String contentType){}
    @Autowired public JsMapService(Environment env,UserMapper users){this(env,users,Clock.systemUTC(),"https://webapi.amap.com","https://fmap01.amap.com",new OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build());}
    // Alternate endpoints exist only for package-local offline tests.
    JsMapService(Environment env,UserMapper users,Clock clock,String webBase,String vectorBase,OkHttpClient client){
        this.users=users;this.clock=clock;this.webBase=webBase;this.vectorBase=vectorBase;this.client=client;
        enabled=env.getProperty("AMAP_JS_ENABLED",Boolean.class,false)&&"AMAP_WEB_V1".equals(env.getProperty("MAP_PROVIDER",env.getProperty("app.maps.provider","MOCK_V1")));
        key=env.getProperty("AMAP_JS_API_KEY","").trim();code=env.getProperty("AMAP_JS_SECURITY_CODE","").trim();
        if(enabled&&!configured())throw new IllegalStateException("Amap JS private configuration is required; values omitted");
    }
    private boolean configured(){return key.matches("[A-Za-z0-9_-]{16,128}")&&code.matches("[A-Za-z0-9_-]{16,128}");}
    public Configuration configuration(){access();return new Configuration(enabled,configured(),enabled?"点击加载后将访问高德在线地图资源；不是地址/路线调用额度":"真实底图尚未启用，不会自动加载");}
    private static CurrentUser access(){var user=CurrentUser.require();if(!user.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();return user;}
    public synchronized Grant issue(Instant jwtExpires){
        var actor=access();if(!enabled)throw unavailable();Instant now=clock.instant();tickets.values().removeIf(t->!t.expires.isAfter(now));
        if(tickets.size()>=500||tickets.values().stream().filter(t->t.actor==actor.id()).count()>=3)throw ApiException.conflict("MAP_JS_SESSION_LIMIT","地图加载会话过多，请稍后再试");
        var expires=now.plusSeconds(600);if(jwtExpires==null||!jwtExpires.isAfter(now))throw invalidSession();if(jwtExpires.isBefore(expires))expires=jwtExpires;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String value=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(digest(value),new Ticket(actor,expires,clock.millis()));return new Grant(value,key,PREFIX,expires);
    }
    public synchronized CurrentUser authenticate(String value){
        if(!enabled||value==null||!value.matches("[A-Za-z0-9_-]{43}"))throw invalidSession();
        var ticket=tickets.get(digest(value));if(ticket==null||!ticket.expires.isAfter(clock.instant()))throw invalidSession();
        var row=users.accessById(ticket.actor);
        if(row==null||!row.enabled()||!row.activated()||!row.bindingValid()||row.authVersion()!=ticket.version||!row.roles().contains(Role.CUSTOMER_SERVICE)||!Portal.ADMIN.allows(row.roles()))throw invalidSession();
        return row.current();
    }
    public synchronized void revoke(String cookie){if(cookie!=null&&cookie.matches("[A-Za-z0-9_-]{43}"))tickets.remove(digest(cookie));}
    private synchronized void reserve(String cookie){
        if(authenticate(cookie).id()!=access().id())throw ApiException.denied();var t=tickets.get(digest(cookie));long now=clock.millis();
        if(now-t.window>=1000){t.window=now;t.windowRequests=0;}
        if(t.windowRequests>=20||t.requests>=120)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"MAP_JS_RESOURCE_LIMIT","本次地图资源请求达到上限，请停止操作并稍后明确加载");
        t.windowRequests++;t.requests++;
    }
    public Asset asset(String path,Map<String,String[]> params,String cookie){
        access();String target=switch(path){case "/v4/map/styles"->webBase;case "/v3/vectormap"->vectorBase;default->throw ApiException.denied();};
        var url=Objects.requireNonNull(HttpUrl.parse(target+path)).newBuilder();int length=0;
        for(var entry:params.entrySet()){
            String name=entry.getKey();String[] values=entry.getValue();
            if(!name.matches("[A-Za-z_][A-Za-z0-9_]{0,39}")||values==null||values.length!=1||values[0]==null||values[0].length()>2000||Set.of("jscode","url","target","redirect").contains(name.toLowerCase(Locale.ROOT)))throw ApiException.invalid("地图资源参数不符合要求");
            if(Set.of("callback","jsonp","cb").contains(name.toLowerCase(Locale.ROOT))&&!values[0].matches("[A-Za-z_$][A-Za-z0-9_$]{0,79}(\\.[A-Za-z_$][A-Za-z0-9_$]{0,79}){0,4}"))throw ApiException.invalid("地图回调参数不符合要求");
            length+=name.length()+values[0].length();if(length>6000)throw ApiException.invalid("地图资源请求过长");
            if("key".equalsIgnoreCase(name)){if(!key.equals(values[0]))throw ApiException.denied();continue;}
            url.addQueryParameter(name,values[0]);
        }
        url.addQueryParameter("key",key).addQueryParameter("jscode",code);reserve(cookie);
        try(var response=client.newCall(new Request.Builder().url(url.build()).get().build()).execute()){
            if(!response.isSuccessful()||response.body()==null||response.body().contentLength()>LIMIT)throw unavailable();
            byte[] bytes;try(var stream=response.body().byteStream()){bytes=stream.readNBytes(LIMIT+1);}
            if(bytes.length>LIMIT||contains(bytes,code.getBytes(StandardCharsets.UTF_8)))throw unavailable();
            String type=response.header("Content-Type","application/octet-stream").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if(!Set.of("application/javascript","text/javascript","application/json","text/plain","image/png","image/jpeg","application/x-protobuf","application/octet-stream").contains(type))throw unavailable();
            return new Asset(bytes,type);
        }catch(IOException error){throw unavailable();}catch(ApiException error){throw error;}catch(RuntimeException error){throw unavailable();}
    }
    private static boolean contains(byte[] bytes,byte[] secret){outer:for(int i=0;i<=bytes.length-secret.length;i++){for(int j=0;j<secret.length;j++)if(bytes[i+j]!=secret[j])continue outer;return true;}return false;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}}
    private static ApiException unavailable(){return new ApiException(HttpStatus.BAD_GATEWAY,"MAP_JS_UNAVAILABLE","高德地图资源加载失败或未启用；未自动重试，地址与表单保留");}
    private static ApiException invalidSession(){return new ApiException(HttpStatus.UNAUTHORIZED,"MAP_JS_SESSION_INVALID","地图访问会话已失效，请重新明确加载地图");}
}
