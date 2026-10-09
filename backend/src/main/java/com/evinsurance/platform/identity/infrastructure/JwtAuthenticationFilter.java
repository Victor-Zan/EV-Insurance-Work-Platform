package com.evinsurance.platform.identity.infrastructure;

import com.evinsurance.platform.foundation.api.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt; private final UserMapper users; private final ObjectMapper json;
    public JwtAuthenticationFilter(JwtService jwt,UserMapper users,ObjectMapper json) { this.jwt=jwt; this.users=users; this.json=json; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return request.getServletPath().equals("/api/v1/auth/login"); }
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        if (header!=null) {
            try {
                if (!header.startsWith("Bearer ")) throw new JwtException("Invalid scheme");
                var token=jwt.decode(header.substring(7));
                long id=Long.parseLong(token.getSubject());
                Number version=token.getClaim("av");
                var row=users.accessById(id);
                if (row==null || !row.enabled() || row.roles().isEmpty() || version.longValue()!=row.authVersion()
                    || !(com.evinsurance.platform.identity.domain.Portal.ADMIN.allows(row.roles())
                    || com.evinsurance.platform.identity.domain.Portal.H5.allows(row.roles()))) {
                    write(response,401,"SESSION_INVALID","Account disabled or session invalid; sign in again"); return;
                }
                var authorities=row.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_"+role.name())).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(row.current(),null,authorities));
            } catch (JwtException | IllegalArgumentException | ClassCastException exception) {
                write(response,401,"TOKEN_INVALID","Token invalid or expired; sign in again"); return;
            } catch (org.springframework.dao.DataAccessException exception) {
                write(response,503,"AUTH_UNAVAILABLE","Authentication is temporarily unavailable"); return;
            }
        }
        chain.doFilter(request,response);
    }
    private void write(HttpServletResponse response,int status,String code,String message) throws IOException {
        SecurityContextHolder.clearContext(); response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(),ApiResponse.failure(code,message));
    }
}
