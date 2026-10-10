package com.cooker.identity.entity;

import java.time.OffsetDateTime;

/** 一对一档案，主键直接复用用户主键。 */
public class UserDiningProfileEntity {
    public Long userId;
    public Integer defaultDiningCount;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
