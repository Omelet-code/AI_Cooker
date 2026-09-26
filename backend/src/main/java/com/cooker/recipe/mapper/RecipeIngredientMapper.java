package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeIngredientEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeIngredientMapper extends BaseMapper<RecipeIngredientEntity> {
    @Delete("DELETE FROM recipe_ingredient WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);

    /**
     * 按原文顺序返回用于检索投影的原料名：优先使用已规范化的名称，
     * 极端情况下回退到原始名称，避免因标准化缺失而丢料。
     */
    @Select("""
            SELECT COALESCE(i.canonical_name, ri.raw_name)
            FROM recipe_ingredient ri
            LEFT JOIN ingredient i ON i.id = ri.ingredient_id
            WHERE ri.recipe_id = #{recipeId}
            ORDER BY ri.sort_order
            """)
    java.util.List<String> findIndexIngredientNames(@Param("recipeId") Long recipeId);
}
