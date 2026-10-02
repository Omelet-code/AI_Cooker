package com.cooker.identity.config;

import com.cooker.identity.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieService {
    private final IdentityProperties config;
    public CookieService(IdentityProperties config) { this.config = config; }

    public void write(HttpServletResponse response, AuthService.Session session) {
        add(response, "COOKER_ACCESS", session.accessToken(), "/api",
                Duration.ofMinutes(config.accessMinutes()));
        add(response, "COOKER_REFRESH", session.refreshToken(), "/api/auth",
                session.rememberMe() ? Duration.ofSeconds(session.refreshMaxAgeSeconds()) : null);
    }
    public void clear(HttpServletResponse response) {
        add(response, "COOKER_ACCESS", "", "/api", Duration.ZERO);
        add(response, "COOKER_REFRESH", "", "/api/auth", Duration.ZERO);
    }
    private void add(HttpServletResponse response, String name, String value, String path, Duration age) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(name, value)
                .httpOnly(true).secure(config.secureCookie()).sameSite("Lax").path(path);
        if (age != null) cookie.maxAge(age);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }
}
