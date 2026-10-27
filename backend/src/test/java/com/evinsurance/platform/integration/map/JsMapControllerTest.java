package com.evinsurance.platform.integration.map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.evinsurance.platform.foundation.config.SecurityConfiguration;
import com.evinsurance.platform.foundation.web.RequestTraceFilter;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
@WebMvcTest(JsMapController.class) @Import({SecurityConfiguration.class,RequestTraceFilter.class})
class JsMapControllerTest {
 @MockitoBean JwtService jwt;@MockitoBean UserMapper users;@MockitoBean JsMapService maps;@Autowired MockMvc mvc;
 private final String key=UUID.randomUUID().toString().replace("-",""),cookie=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
 @BeforeEach void prepare(){int id=0;for(Role role:Role.values()){long actor=++id;String token=role.name();when(jwt.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg","test").subject(Long.toString(actor)).claim("av",0L).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());when(users.accessById(actor)).thenReturn(new AccessRow(actor,"synthetic","合成",null,true,0,token,null,null));}
 when(maps.configuration()).thenReturn(new JsMapService.Configuration(true,true,"synthetic"));when(maps.issue(any())).thenReturn(new JsMapService.Grant(cookie,key,JsMapService.PREFIX,Instant.now().plusSeconds(60)));}
 @Test void bootstrapAndConfigurationRetainFourRoleBoundaryAndReturnOnlyPublicConfiguration()throws Exception{
 for(Role role:Role.values()){int status=role==Role.CUSTOMER_SERVICE?200:403;mvc.perform(get("/api/v1/maps/js/configuration").header("Authorization","Bearer "+role.name())).andExpect(status().is(status));mvc.perform(post("/api/v1/maps/js/bootstrap").header("Authorization","Bearer "+role.name()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is(status));}
 mvc.perform(post("/api/v1/maps/js/bootstrap")).andExpect(status().isUnauthorized());
 mvc.perform(post("/api/v1/maps/js/bootstrap").header("Authorization","Bearer CUSTOMER_SERVICE")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("SameSite=Strict"),org.hamcrest.Matchers.containsString("Path="+JsMapService.PREFIX)))).andExpect(jsonPath("$.data.servicePath").value("/_AMapService")).andExpect(jsonPath("$.data.publicKey").value(key)).andExpect(jsonPath("$.data.securityJsCode").doesNotExist()).andExpect(jsonPath("$.data.cookie").doesNotExist());
 mvc.perform(get("/api/v1/maps/js/bootstrap").header("Authorization","Bearer CUSTOMER_SERVICE")).andExpect(status().isMethodNotAllowed());
 }
 @Test void proxyUsesOnlyItsScopedCookieAndDoesNotOpenOtherMapApis()throws Exception{
 var actor=new CurrentUser(1,"synthetic","合成客服",Set.of(Role.CUSTOMER_SERVICE),null,0);when(maps.authenticate(cookie)).thenReturn(actor);when(maps.asset(anyString(),anyMap(),eq(cookie))).thenReturn(new JsMapService.Asset("{}".getBytes(),"application/json"));
 mvc.perform(get(JsMapService.PREFIX+"/v4/map/styles")).andExpect(status().isUnauthorized());
 mvc.perform(get(JsMapService.PREFIX+"/v4/map/styles").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie)).header("Sec-Fetch-Site","cross-site")).andExpect(status().isForbidden());
 mvc.perform(get("/api/v1/maps/js-proxy/v4/map/styles").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie))).andExpect(status().isUnauthorized());
 mvc.perform(get(JsMapService.PREFIX+"/v4/map/styles").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
 verify(maps).asset(eq("/v4/map/styles"),anyMap(),eq(cookie));
 mvc.perform(post(JsMapService.PREFIX+"/v4/map/styles").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie))).andExpect(status().isForbidden());
 mvc.perform(get("/api/v1/maps/locations/regions").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie))).andExpect(status().isUnauthorized());
 mvc.perform(get(JsMapService.PREFIX+"/v3/geocode/geo").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie))).andExpect(status().isNotFound());
 when(maps.authenticate(cookie)).thenThrow(new com.evinsurance.platform.foundation.api.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"MAP_JS_SESSION_INVALID","invalid"));mvc.perform(get(JsMapService.PREFIX+"/v4/map/styles").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie))).andExpect(status().isUnauthorized());
 }
 @Test void sessionRevocationUsesSameScopedCookieAndRejectsCrossSiteDelete()throws Exception{
 var actor=new CurrentUser(1,"synthetic","合成客服",Set.of(Role.CUSTOMER_SERVICE),null,0);when(maps.authenticate(cookie)).thenReturn(actor);
 mvc.perform(delete(JsMapService.PREFIX+"/session").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie)).header("Sec-Fetch-Site","cross-site")).andExpect(status().isForbidden());
 verify(maps,never()).revoke(anyString());
 mvc.perform(delete(JsMapService.PREFIX+"/session").cookie(new jakarta.servlet.http.Cookie(JsMapService.COOKIE,cookie)).header("Sec-Fetch-Site","same-origin")).andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("Path=/_AMapService"),org.hamcrest.Matchers.containsString("Max-Age=0"),org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("SameSite=Strict"))));
 verify(maps).revoke(cookie);
 }
}
