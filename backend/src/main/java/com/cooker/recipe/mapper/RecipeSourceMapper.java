package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeSourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeSourceMapper extends BaseMapper<RecipeSourceEntity> {
    @Select("SELECT * FROM recipe_source WHERE source_type = #{sourceType} AND source_key = #{sourceKey} AND owner_user_id IS NULL")
    RecipeSourceEntity findSystemSource(@Param("sourceType") String sourceType, @Param("sourceKey") String sourceKey);
}
