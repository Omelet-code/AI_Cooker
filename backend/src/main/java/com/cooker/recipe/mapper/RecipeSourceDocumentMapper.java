package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeSourceDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeSourceDocumentMapper extends BaseMapper<RecipeSourceDocumentEntity> {
    @Select("SELECT * FROM recipe_source_document WHERE source_id = #{sourceId} AND source_version = #{sourceVersion}")
    RecipeSourceDocumentEntity findBySourceAndVersion(@Param("sourceId") Long sourceId,
            @Param("sourceVersion") String sourceVersion);
}
