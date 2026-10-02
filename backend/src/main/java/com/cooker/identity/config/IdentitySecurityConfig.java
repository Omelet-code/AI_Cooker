package com.cooker.identity.config;

import com.cooker.common.ApiResponse;
import com.cooker.identity.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentitySecurityConfig {
    @Bean ObjectMapper securityJson() { return new ObjectMapper(); }

    @Bean PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 16, 3);
    }

    @Bean CookieCsrfTokenRepository csrfRepository(IdentityProperties props) {
        CookieCsrfTokenRepository repo = new CookieCsrfTokenRepository();
        repo.setCookieCustomizer(cookie -> cookie.path("/api").httpOnly(true).secure(props.secureCookie()).sameSite("Lax"));
        return repo;
    }

    @Bean SecurityFilterChain security(HttpSecurity http, JwtService jwt, ObjectMapper json,
            CookieCsrfTokenRepository csrfRepo) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler))
            .authorizeHttpRequests(a -> a.requestMatchers("/auth/**", "/actuator/health", "/actuator/info").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> write(res, json, 401, "AUTH_REQUIRED", "请先登录"))
                    .accessDeniedHandler((req, res, ex) -> write(res, json, 403,
                            ex instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED",
                            ex instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF 校验失败" : "无权访问")))
            .addFilterBefore(new AccessCookieFilter(jwt, json), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void write(HttpServletResponse res, ObjectMapper json, int status, String code, String message) throws java.io.IOException {
        res.setStatus(status); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        json.writeValue(res.getWriter(), ApiResponse.error(code, message));
    }
}
