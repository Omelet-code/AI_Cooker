package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.CookingToolEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CookingToolMapper extends BaseMapper<CookingToolEntity> {
    @Select("SELECT * FROM cooking_tool WHERE canonical_name = #{name}")
    CookingToolEntity findByCanonicalName(@Param("name") String name);
}
