package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.infrastructure.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController @SecurityRequirement(name="bearerAuth")
public class JsMapController {
    private final JsMapService maps;private final JwtService jwt;
    public JsMapController(JsMapService maps,JwtService jwt){this.maps=maps;this.jwt=jwt;}
    @GetMapping("/api/v1/maps/js/configuration") @Operation(summary="JS底图启用状态，不返回任何密钥，不外呼")
    public ApiResponse<JsMapService.Configuration> configuration(){return ApiResponse.success(maps.configuration());}
    public record Bootstrap(String publicKey,String servicePath,Instant expires){}
    @PostMapping("/api/v1/maps/js/bootstrap") @Operation(summary="客服明确加载地图：签发短期HttpOnly资源会话，仅返回公开JS Key")
    public ResponseEntity<ApiResponse<Bootstrap>> bootstrap(HttpServletRequest request){
        String header=request.getHeader("Authorization");if(header==null||!header.startsWith("Bearer "))throw ApiException.denied();
        var grant=maps.issue(jwt.decode(header.substring(7)).getExpiresAt());
        var cookie=ResponseCookie.from(JsMapService.COOKIE,grant.cookie()).httpOnly(true).secure(request.isSecure()).sameSite("Strict").path(JsMapService.PREFIX).maxAge(Math.max(0,Duration.between(Instant.now(),grant.expires()).toSeconds())).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie.toString()).cacheControl(CacheControl.noStore()).body(ApiResponse.success(new Bootstrap(grant.publicKey(),grant.servicePath(),grant.expires())));
    }
    @DeleteMapping("/_AMapService/session") @Operation(summary="销毁当前JS资源会话，不外呼")
    public ResponseEntity<Void> revoke(@CookieValue(name=JsMapService.COOKIE,required=false) String cookie){maps.revoke(cookie);return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,ResponseCookie.from(JsMapService.COOKIE,"").httpOnly(true).sameSite("Strict").path(JsMapService.PREFIX).maxAge(0).build().toString()).cacheControl(CacheControl.noStore()).build();}
    @GetMapping({"/_AMapService/v4/map/styles","/_AMapService/v3/vectormap"}) @Operation(summary="仅固定高德样式/矢量资源代理；短期客服Cookie认证，禁止POI/地理/路线和任意URL")
    public ResponseEntity<byte[]> asset(HttpServletRequest request,@CookieValue(name=JsMapService.COOKIE,required=false) String cookie){
        String path=request.getRequestURI().substring((request.getContextPath()+JsMapService.PREFIX).length());var result=maps.asset(path,request.getParameterMap(),cookie);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(result.contentType())).body(result.bytes());
    }
}
