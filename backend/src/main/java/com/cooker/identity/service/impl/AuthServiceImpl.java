package com.cooker.identity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cooker.common.ApiException;
import com.cooker.identity.config.IdentityProperties;
import com.cooker.identity.entity.AppUserEntity;
import com.cooker.identity.entity.RefreshTokenEntity;
import com.cooker.identity.mapper.AppUserMapper;
import com.cooker.identity.mapper.RefreshTokenMapper;
import com.cooker.identity.service.AuthService;
import com.cooker.identity.service.JwtService;
import com.cooker.identity.vo.UserProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Base64;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private final AppUserMapper users;
    private final RefreshTokenMapper tokens;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final IdentityProperties config;
    private final SecureRandom random = new SecureRandom();

    public AuthServiceImpl(AppUserMapper users, RefreshTokenMapper tokens, PasswordEncoder passwords,
            JwtService jwt, IdentityProperties config) {
        this.users = users; this.tokens = tokens; this.passwords = passwords; this.jwt = jwt; this.config = config;
    }

    @Override @Transactional
    public UserProfile register(String username, String password) {
        String normalized = normalize(username);
        if (password == null || password.length() < 12 || password.length() > 128)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "密码长度须为 12 至 128 字符");
        AppUserEntity user = new AppUserEntity();
        user.username = normalized;
        user.passwordHash = passwords.encode(password);
        try { users.insert(user); }
        catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已使用");
        }
        return profile(user);
    }

    @Override @Transactional
    public Session login(String username, String password, boolean rememberMe) {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        AppUserEntity user = users.selectOne(new LambdaQueryWrapper<AppUserEntity>().eq(AppUserEntity::getUsername, normalized));
        if (user == null || !passwords.matches(password == null ? "" : password, user.passwordHash))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime deadline = rememberMe ? now.plusDays(config.rememberDays()) : now.plusHours(config.sessionHours());
        return issue(user, rememberMe, deadline, deadline);
    }

    @Override @Transactional
    public Session refresh(String raw) {
        if (raw == null || raw.isBlank()) throw rejected();
        String hash = hash(raw);
        RefreshTokenEntity old = tokens.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>().eq(RefreshTokenEntity::getTokenHash, hash));
        if (old == null || old.expiresAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) throw rejected();
        if (old.revokedAt != null) throw conflict();
        // 更新与插入在同一事务：若新令牌写入失败，旧令牌的撤销也回滚。
        if (tokens.consume(hash) != 1) throw conflict();
        AppUserEntity user = users.selectById(old.userId);
        if (user == null) throw rejected();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime rolling = old.rememberMe ? now.plusDays(config.rememberDays()) : now.plusHours(config.sessionHours());
        OffsetDateTime expiry = rolling.isBefore(old.absoluteExpiresAt) ? rolling : old.absoluteExpiresAt;
        if (!expiry.isAfter(now)) throw rejected();
        return issue(user, old.rememberMe, expiry, old.absoluteExpiresAt);
    }

    @Override @Transactional
    public void logout(String raw) {
        if (raw != null && !raw.isBlank()) tokens.revoke(hash(raw));
    }

    private Session issue(AppUserEntity user, boolean rememberMe, OffsetDateTime expiry, OffsetDateTime absolute) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshTokenEntity row = new RefreshTokenEntity();
        row.userId = user.id; row.tokenHash = hash(raw); row.expiresAt = expiry;
        row.absoluteExpiresAt = absolute; row.rememberMe = rememberMe;
        tokens.insert(row);
        long maxAge = java.time.Duration.between(OffsetDateTime.now(ZoneOffset.UTC), expiry).toSeconds();
        return new Session(profile(user), jwt.issue(user.id), raw, rememberMe, Math.max(0, maxAge));
    }

    private String normalize(String username) {
        String value = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_]{3,32}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "用户名须为 3 至 32 位英文字母、数字或下划线");
        return value;
    }
    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用", e); }
    }
    private UserProfile profile(AppUserEntity user) { return new UserProfile(user.id.toString(), user.username); }
    private ApiException rejected() { return new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_REJECTED", "续期凭证无效，请重新登录"); }
    private ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "REFRESH_CONFLICT", "续期凭证已被其他请求使用"); }
}
