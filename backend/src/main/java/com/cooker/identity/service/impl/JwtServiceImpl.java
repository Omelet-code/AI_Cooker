package com.cooker.identity.service.impl;

import com.cooker.identity.config.IdentityProperties;
import com.cooker.identity.service.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.stereotype.Service;

@Service
public class JwtServiceImpl implements JwtService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final IdentityProperties properties;

    public JwtServiceImpl(IdentityProperties properties) {
        this.properties = properties;
        if (properties.accessMinutes() <= 0 || properties.sessionHours() <= 0 || properties.rememberDays() <= 0)
            throw new IllegalStateException("身份凭证有效期必须为正数");
        byte[] key = properties.jwtSecret() == null ? new byte[0] : properties.jwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("必须配置至少 32 字节的 COOKER_JWT_SECRET");
        var secret = new SecretKeySpec(key, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(secret));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "受众不匹配", null));
        // 当前时间验证器检查已有 exp/nbf；不额外要求 exp 必填，也不校验未来的 iat。
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(properties.issuer()), audience));
        this.decoder = nimbus;
    }

    @Override public String issue(Long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(userId.toString())
                .issuer(properties.issuer()).audience(List.of(properties.audience()))
                .issuedAt(now).expiresAt(now.plus(properties.accessMinutes(), ChronoUnit.MINUTES)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Override public Long verify(String raw) {
        Jwt jwt = decoder.decode(raw);
        try { return Long.valueOf(jwt.getSubject()); }
        catch (RuntimeException e) { throw new BadJwtException("身份无效"); }
    }
}
