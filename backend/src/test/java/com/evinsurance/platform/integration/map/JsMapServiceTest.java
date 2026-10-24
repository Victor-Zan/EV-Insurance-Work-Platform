package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class JsMapServiceTest {
 private final String key=UUID.randomUUID().toString().replace("-",""),secret=UUID.randomUUID().toString().replace("-","");
 private final UserMapper users=mock(UserMapper.class);private HttpServer server;private String base;private final AtomicInteger calls=new AtomicInteger();private final AtomicReference<String> query=new AtomicReference<>(),body=new AtomicReference<>("{\"data\":\"synthetic\"}");private int status=200;
 private final class TestClock extends Clock {Instant value=Instant.now();public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId z){return this;}public Instant instant(){return value;}}
 private final TestClock clock=new TestClock();
 private MockEnvironment env(){return new MockEnvironment().withProperty("MAP_PROVIDER","AMAP_WEB_V1").withProperty("AMAP_JS_ENABLED","true").withProperty("AMAP_JS_API_KEY",key).withProperty("AMAP_JS_SECURITY_CODE",secret);}
 private JsMapService service(MockEnvironment env){return new JsMapService(env,users,clock,base,base,new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build());}
 private void role(Role role){var u=new CurrentUser(1,"synthetic","合成账号",Set.of(role),null,0);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
 private AccessRow row(boolean enabled,long version,String role){return new AccessRow(1,"synthetic","合成账号",null,enabled,version,role,null,null);}
 @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{calls.incrementAndGet();query.set(e.getRequestURI().getRawQuery());byte[] b=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);e.close();});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();role(Role.CUSTOMER_SERVICE);when(users.accessById(1L)).thenReturn(row(true,0,"CUSTOMER_SERVICE"));}
 @AfterEach void stop(){server.stop(0);SecurityContextHolder.clearContext();}
 @Test void disabledAndMissingConfigurationNeverRequestNetwork(){var s=service(new MockEnvironment());assertThat(s.configuration().enabled()).isFalse();assertThatThrownBy(()->s.issue(clock.instant().plusSeconds(100))).hasMessageNotContaining(secret);assertThatThrownBy(()->service(env().withProperty("AMAP_JS_SECURITY_CODE",""))).hasMessageNotContaining(key).hasMessageNotContaining(secret);assertThat(calls.get()).isZero();}
 @Test void onlyCustomerServiceCanIssueAndInspect(){var s=service(env());for(var role:List.of(Role.ADMIN,Role.REPAIR_SHOP,Role.OWNER)){role(role);assertThatThrownBy(s::configuration).extracting("code").isEqualTo("FORBIDDEN");assertThatThrownBy(()->s.issue(clock.instant().plusSeconds(100))).extracting("code").isEqualTo("FORBIDDEN");}assertThat(calls.get()).isZero();}
 @Test void sessionsAreOpaqueExpireWithJwtAndRejectDisabledChangedOrRevokedAccounts(){var s=service(env());var grant=s.issue(clock.instant().plusSeconds(5));assertThat(grant.publicKey()).isEqualTo(key);assertThat(grant.cookie()).doesNotContain(key,secret);assertThat(s.authenticate(grant.cookie()).id()).isEqualTo(1);
 when(users.accessById(1L)).thenReturn(row(false,0,"CUSTOMER_SERVICE"));assertThatThrownBy(()->s.authenticate(grant.cookie())).extracting("code").isEqualTo("MAP_JS_SESSION_INVALID");
 when(users.accessById(1L)).thenReturn(row(true,1,"CUSTOMER_SERVICE"));assertThatThrownBy(()->s.authenticate(grant.cookie())).extracting("code").isEqualTo("MAP_JS_SESSION_INVALID");
 when(users.accessById(1L)).thenReturn(row(true,0,"OWNER"));assertThatThrownBy(()->s.authenticate(grant.cookie())).extracting("code").isEqualTo("MAP_JS_SESSION_INVALID");
 when(users.accessById(1L)).thenReturn(row(true,0,"CUSTOMER_SERVICE"));clock.value=clock.value.plusSeconds(6);assertThatThrownBy(()->s.authenticate(grant.cookie())).extracting("code").isEqualTo("MAP_JS_SESSION_INVALID");var next=s.issue(clock.instant().plusSeconds(60));s.revoke(next.cookie());assertThatThrownBy(()->s.authenticate(next.cookie())).extracting("code").isEqualTo("MAP_JS_SESSION_INVALID");assertThat(calls.get()).isZero();}
 @Test void proxyForwardsOnlyFixedAssetsAndNeverLeaksSecurityCode(){var s=service(env());var grant=s.issue(clock.instant().plusSeconds(60));assertThat(s.asset("/v4/map/styles",Map.of("key",new String[]{key}),grant.cookie()).contentType()).isEqualTo("application/json");assertThat(query.get()).contains("jscode="+secret);
 for(String path:List.of("/v3/geocode/geo","/v5/direction/driving","//evil.invalid","/../v4/map/styles"))assertThatThrownBy(()->s.asset(path,Map.of(),grant.cookie())).extracting("code").isEqualTo("FORBIDDEN");
 for(String param:List.of("url","jscode","target","redirect"))assertThatThrownBy(()->s.asset("/v4/map/styles",Map.of(param,new String[]{"synthetic"}),grant.cookie())).hasMessageNotContaining(secret);
 assertThatThrownBy(()->s.asset("/v4/map/styles",Map.of("callback",new String[]{"alert(1)"}),grant.cookie())).hasMessageContaining("回调");
 body.set(secret);assertThatThrownBy(()->s.asset("/v3/vectormap",Map.of(),grant.cookie())).hasMessageNotContaining(secret).hasMessageNotContaining(key).hasCause(null);assertThat(calls.get()).isEqualTo(2);}
 @Test void boundedResponseNoRedirectNoRetryAndLimitsFailClosed(){var s=service(env());var grant=s.issue(clock.instant().plusSeconds(60));status=302;assertThatThrownBy(()->s.asset("/v4/map/styles",Map.of(),grant.cookie())).hasCause(null);assertThat(calls.get()).isEqualTo(1);status=200;body.set("x".repeat(4*1024*1024+1));assertThatThrownBy(()->s.asset("/v4/map/styles",Map.of(),grant.cookie())).hasCause(null);assertThat(calls.get()).isEqualTo(2);
 body.set("{}");for(int i=0;i<18;i++)s.asset("/v4/map/styles",Map.of(),grant.cookie());assertThatThrownBy(()->s.asset("/v4/map/styles",Map.of(),grant.cookie())).extracting("code").isEqualTo("MAP_JS_RESOURCE_LIMIT");assertThat(calls.get()).isEqualTo(20);}
 @Test void filterRequiresItsCookieAndNeverAcceptsJwtHeadersAsProxyAuthorization()throws Exception{var s=service(env());var f=new JsMapAuthenticationFilter(s,new ObjectMapper());var req=new MockHttpServletRequest("GET",JsMapService.PREFIX+"/v4/map/styles");var out=new MockHttpServletResponse();var chained=new AtomicInteger();f.doFilter(req,out,(a,b)->chained.incrementAndGet());assertThat(out.getStatus()).isEqualTo(401);assertThat(chained.get()).isZero();role(Role.CUSTOMER_SERVICE);var grant=s.issue(clock.instant().plusSeconds(60));req=new MockHttpServletRequest("GET",JsMapService.PREFIX+"/v4/map/styles");req.setCookies(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,grant.cookie()));out=new MockHttpServletResponse();f.doFilter(req,out,(a,b)->chained.incrementAndGet());assertThat(chained.get()).isEqualTo(1);req=new MockHttpServletRequest("GET",JsMapService.PREFIX+"/v4/map/styles");req.setCookies(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,grant.cookie()));req.addHeader("Authorization","Bearer synthetic");out=new MockHttpServletResponse();f.doFilter(req,out,(a,b)->chained.incrementAndGet());assertThat(out.getStatus()).isEqualTo(403);assertThat(chained.get()).isEqualTo(1);req=new MockHttpServletRequest("GET",JsMapService.PREFIX+"/v4/map/styles");req.setCookies(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,grant.cookie()));req.addHeader("Sec-Fetch-Site","cross-site");out=new MockHttpServletResponse();f.doFilter(req,out,(a,b)->chained.incrementAndGet());assertThat(out.getStatus()).isEqualTo(403);assertThat(chained.get()).isEqualTo(1);assertThat(calls.get()).isZero();}
}
