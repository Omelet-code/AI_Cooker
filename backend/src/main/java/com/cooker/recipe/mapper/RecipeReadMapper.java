package com.cooker.recipe.mapper;

import com.cooker.recipe.dto.IngredientRow;
import com.cooker.recipe.dto.RecipeCardRow;
import com.cooker.recipe.dto.StepRow;
import com.cooker.recipe.dto.ToolRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeReadMapper {
    long countVisible(@Param("userId") Long userId, @Param("keyword") String keyword);
    List<RecipeCardRow> listVisible(@Param("userId") Long userId, @Param("keyword") String keyword,
            @Param("size") int size, @Param("offset") long offset);
    RecipeCardRow findVisible(@Param("userId") Long userId, @Param("recipeId") Long recipeId);
    long countFavorites(@Param("userId") Long userId);
    List<RecipeCardRow> listFavorites(@Param("userId") Long userId,
            @Param("size") int size, @Param("offset") long offset);
    List<IngredientRow> ingredients(@Param("recipeId") Long recipeId);
    List<StepRow> steps(@Param("recipeId") Long recipeId);
    List<ToolRow> tools(@Param("recipeId") Long recipeId);
}
