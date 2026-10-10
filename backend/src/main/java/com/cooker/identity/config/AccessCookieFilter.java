package com.cooker.identity.config;

import com.cooker.common.ApiResponse;
import com.cooker.identity.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccessCookieFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final ObjectMapper json;
    public AccessCookieFilter(JwtService jwt, ObjectMapper json) { this.jwt = jwt; this.json = json; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // auth 接口只依赖各自凭证；过期访问 Cookie 不能阻止登录、续期和退出。
        if (request.getServletPath().startsWith("/auth/")) { chain.doFilter(request, response); return; }
        String raw = cookie(request, "COOKER_ACCESS");
        if (raw != null) {
            try {
                Long id = jwt.verify(raw);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(id, null, List.of()));
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                json.writeValue(response.getWriter(), ApiResponse.error("ACCESS_TOKEN_EXPIRED", "登录已过期，请续期"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
