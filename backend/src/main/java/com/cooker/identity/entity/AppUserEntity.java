package com.cooker.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("app_user")
public class AppUserEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String username;
    public String passwordHash;
    public String getUsername() { return username; }
}
