package com.cooker.identity.controller;

import com.cooker.common.ApiResponse;
import com.cooker.identity.config.AccessCookieFilter;
import com.cooker.identity.config.CookieService;
import com.cooker.identity.dto.LoginRequest;
import com.cooker.identity.dto.RegisterRequest;
import com.cooker.identity.service.AuthService;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.identity.vo.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthService auth;
    private final CurrentUserService current;
    private final CookieService cookies;
    private final CookieCsrfTokenRepository csrf;
    public AuthController(AuthService auth, CurrentUserService current, CookieService cookies, CookieCsrfTokenRepository csrf) {
        this.auth = auth; this.current = current; this.cookies = cookies; this.csrf = csrf;
    }

    @GetMapping("/auth/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of("token", token.getToken(), "headerName", "X-XSRF-TOKEN"));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<UserProfile>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(auth.register(request.username(), request.password())));
    }

    @PostMapping("/auth/login")
    public ApiResponse<UserProfile> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        AuthService.Session result = auth.login(request.username(), request.password(), Boolean.TRUE.equals(request.rememberMe()));
        cookies.write(response, result);
        csrf.saveToken(null, servletRequest, response);
        return ApiResponse.ok(result.profile());
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<UserProfile> refresh(HttpServletRequest request, HttpServletResponse response) {
        AuthService.Session result = auth.refresh(AccessCookieFilter.cookie(request, "COOKER_REFRESH"));
        cookies.write(response, result);
        return ApiResponse.ok(result.profile());
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(AccessCookieFilter.cookie(request, "COOKER_REFRESH"));
        cookies.clear(response);
        csrf.saveToken(null, request, response);
        return ApiResponse.ok(null);
    }

    @GetMapping("/users/me")
    public ApiResponse<UserProfile> me() { return ApiResponse.ok(current.currentProfile()); }
}
