package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.RecipeToolEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeToolMapper extends BaseMapper<RecipeToolEntity> {
    @Delete("DELETE FROM recipe_tool WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);

    /** 返回菜谱所需厨具的规范名称；使用内连接，投影只识别厨具词典中已存在的条目。 */
    @Select("""
            SELECT ct.canonical_name
            FROM recipe_tool rt
            JOIN cooking_tool ct ON ct.id = rt.tool_id
            WHERE rt.recipe_id = #{recipeId}
            ORDER BY ct.canonical_name
            """)
    java.util.List<String> findIndexToolNames(@Param("recipeId") Long recipeId);
}
