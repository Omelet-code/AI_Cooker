package com.cooker.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.identity.entity.AppUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUserEntity> { }
