package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeToolEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeToolMapper extends BaseMapper<RecipeToolEntity> {
    @Delete("DELETE FROM recipe_tool WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);
}
