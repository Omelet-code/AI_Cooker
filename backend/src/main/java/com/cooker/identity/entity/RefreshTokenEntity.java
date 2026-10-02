package com.cooker.identity.entity;

import java.time.OffsetDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("user_refresh_token")
public class RefreshTokenEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public String tokenHash;
    public OffsetDateTime expiresAt;
    public OffsetDateTime absoluteExpiresAt;
    public Boolean rememberMe;
    public OffsetDateTime revokedAt;
    public String getTokenHash() { return tokenHash; }
}
