package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeStepEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeStepMapper extends BaseMapper<RecipeStepEntity> {
    @Delete("DELETE FROM recipe_step WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);

    /** 按步骤号返回步骤正文，顺序与原文一致，供检索投影切分步骤块。 */
    @Select("SELECT content FROM recipe_step WHERE recipe_id = #{recipeId} ORDER BY step_no")
    java.util.List<String> findIndexStepContents(@Param("recipeId") Long recipeId);
}
