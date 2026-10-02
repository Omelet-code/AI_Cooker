package com.cooker.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.identity.entity.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {
    int consume(@Param("hash") String hash);
    int revoke(@Param("hash") String hash);
}
