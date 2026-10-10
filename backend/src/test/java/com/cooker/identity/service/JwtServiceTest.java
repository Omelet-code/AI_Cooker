package com.cooker.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.cooker.identity.config.IdentityProperties;
import com.cooker.identity.service.impl.JwtServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;

class JwtServiceTest {
    @Test void 签发和验签且拒绝不同受众与密钥() {
        var config = new IdentityProperties("test-only-secret-for-identity-tests-0123456789", "cooker", "web", 15, 24, 30, false);
        JwtService service = new JwtServiceImpl(config);
        String token = service.issue(42L);
        assertThat(service.verify(token)).isEqualTo(42L);
        assertThatThrownBy(() -> new JwtServiceImpl(new IdentityProperties(config.jwtSecret(), "cooker", "other", 15, 24, 30, false)).verify(token))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new JwtServiceImpl(new IdentityProperties("another-test-secret-for-identity-tests-12345", "cooker", "web", 15, 24, 30, false)).verify(token))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new JwtServiceImpl(new IdentityProperties(config.jwtSecret(), "other-issuer", "web", 15, 24, 30, false)).verify(token))
                .isInstanceOf(JwtException.class);
        var encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                new SecretKeySpec(config.jwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256")));
        var past = Instant.now().minus(2, ChronoUnit.HOURS);
        var expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("42").issuer("cooker").audience(List.of("web"))
                        .issuedAt(past).expiresAt(past.plus(1, ChronoUnit.HOURS)).build())).getTokenValue();
        assertThatThrownBy(() -> service.verify(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test void 当前验证器对缺失exp与未来iat没有附加拒绝规则() {
        // 仅用虚构测试密钥记录当前边界；不把签发器总会生成 exp 误写成验证器要求 exp。
        var config = new IdentityProperties("test-only-secret-for-identity-tests-0123456789", "cooker", "web", 15, 24, 30, false);
        JwtService service = new JwtServiceImpl(config);
        var encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                new SecretKeySpec(config.jwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256")));
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        String withoutExp = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("42").issuer("cooker").audience(List.of("web"))
                        .issuedAt(Instant.now()).build())).getTokenValue();
        String futureIat = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("42").issuer("cooker").audience(List.of("web"))
                        .issuedAt(future).expiresAt(future.plus(1, ChronoUnit.HOURS)).build())).getTokenValue();
        assertThat(service.verify(withoutExp)).isEqualTo(42L);
        assertThat(service.verify(futureIat)).isEqualTo(42L);
    }
}
