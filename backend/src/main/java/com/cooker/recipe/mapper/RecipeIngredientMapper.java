package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeIngredientEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeIngredientMapper extends BaseMapper<RecipeIngredientEntity> {
    @Delete("DELETE FROM recipe_ingredient WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);
}
