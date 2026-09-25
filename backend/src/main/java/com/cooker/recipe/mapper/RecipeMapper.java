package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeMapper extends BaseMapper<RecipeEntity> {
    @Select("SELECT * FROM recipe WHERE source_id = #{sourceId}")
    RecipeEntity findBySourceId(@Param("sourceId") Long sourceId);
}
