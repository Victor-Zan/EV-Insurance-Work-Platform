package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public final class JsMapAuthenticationFilter extends OncePerRequestFilter {
    private final JsMapService service;private final ObjectMapper json;
    public JsMapAuthenticationFilter(JsMapService service,ObjectMapper json){this.service=service;this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith(request.getContextPath()+JsMapService.PREFIX+"/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        try{
            if(service==null||!("GET".equals(request.getMethod())||"DELETE".equals(request.getMethod())&&request.getRequestURI().equals(request.getContextPath()+JsMapService.PREFIX+"/session"))||request.getHeader("Authorization")!=null)throw ApiException.denied();
            String site=request.getHeader("Sec-Fetch-Site");if(site!=null&&!java.util.Set.of("same-origin","none").contains(site))throw ApiException.denied();
            String token=null;int count=0;if(request.getCookies()!=null)for(var cookie:request.getCookies())if(JsMapService.COOKIE.equals(cookie.getName())){token=cookie.getValue();count++;}
            if(count!=1)throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"MAP_JS_SESSION_INVALID","请先登录客服端并明确加载地图");
            var user=service.authenticate(token);var roles=user.roles().stream().map(r->new SimpleGrantedAuthority("ROLE_"+r.name())).toList();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,roles));
        }catch(ApiException error){SecurityContextHolder.clearContext();response.setStatus(error.status().value());response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");json.writeValue(response.getOutputStream(),ApiResponse.failure(error.code(),error.getMessage()));return;}
        catch(org.springframework.dao.DataAccessException error){SecurityContextHolder.clearContext();response.setStatus(503);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),ApiResponse.failure("AUTH_UNAVAILABLE","认证服务暂不可用"));return;}
        chain.doFilter(request,response);
    }
}
