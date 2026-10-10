package com.cooker.identity.mapper;

import com.cooker.identity.entity.UserDiningProfileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserDiningProfileMapper {
    UserDiningProfileEntity findByUserId(@Param("userId") long userId);
    // 使用共享行锁保护当前事务内的账号存在性，避免核验后被并发删除。
    Long lockExistingUser(@Param("userId") long userId);
    UserDiningProfileEntity upsert(@Param("userId") long userId,
            @Param("defaultDiningCount") Integer defaultDiningCount);
}
