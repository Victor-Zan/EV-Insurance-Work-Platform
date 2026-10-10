package com.evinsurance.platform.foundation.config;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.identity.infrastructure.JwtAuthenticationFilter;
import com.evinsurance.platform.identity.infrastructure.JwtService;
import com.evinsurance.platform.identity.infrastructure.UserMapper;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,ObjectMapper json,JwtService jwt,UserMapper users,
        @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174}") String origins) throws Exception {
        var cors=new CorsConfiguration(); cors.setAllowedOrigins(List.of(origins.split(",")));
        cors.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type","X-Request-Id","Idempotency-Key")); cors.setExposedHeaders(List.of("X-Request-Id"));
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",cors);
        return http.csrf(csrf -> csrf.disable()).cors(c -> c.configurationSource(source))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> writeError(response,json,401,"UNAUTHORIZED","Authentication is required"))
                .accessDeniedHandler((request,response,error) -> writeError(response,json,403,"FORBIDDEN","Access is denied")))
            .addFilterBefore(new JwtAuthenticationFilter(jwt,users,json),UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health","/actuator/health","/v3/api-docs/**","/swagger-ui.html","/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/me").authenticated()
                .requestMatchers("/api/v1/funds/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP")
                .requestMatchers("/api/v1/notifications/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")
                .requestMatchers("/api/v1/materials/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")
                .requestMatchers("/api/v1/complaints/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")
                .requestMatchers("/api/v1/repairs/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")
                .requestMatchers("/api/v1/quotations/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP")
                .requestMatchers("/api/v1/ocr/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE")
                .requestMatchers("/api/v1/maps/**").hasRole("CUSTOMER_SERVICE")
                .requestMatchers("/api/v1/work-orders/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE","REPAIR_SHOP","OWNER")
                .requestMatchers("/api/v1/admin/session").hasAnyRole("ADMIN","CUSTOMER_SERVICE")
                .requestMatchers("/api/v1/h5/session").hasAnyRole("REPAIR_SHOP","OWNER")
                .requestMatchers(HttpMethod.GET,"/api/v1/pricing/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE")
                .requestMatchers("/api/v1/pricing/sources","/api/v1/pricing/sources/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/pricing/**").hasAnyRole("ADMIN","CUSTOMER_SERVICE")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().denyAll()).build();
    }
    private void writeError(HttpServletResponse response,ObjectMapper json,int status,String code,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(),ApiResponse.failure(code,message));
    }
}
