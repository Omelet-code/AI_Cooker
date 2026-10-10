package com.cooker.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cooker.identity")
public record IdentityProperties(String jwtSecret, String issuer, String audience,
        long accessMinutes, long sessionHours, long rememberDays, boolean secureCookie) { }
