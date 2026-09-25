package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeStepEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeStepMapper extends BaseMapper<RecipeStepEntity> {
    @Delete("DELETE FROM recipe_step WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);
}
