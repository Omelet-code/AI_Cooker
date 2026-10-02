package com.cooker.recipe.mapper;

import com.cooker.recipe.dto.CookedRecipeRow;
import com.cooker.recipe.dto.CookingRecordRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeCookingMapper {
    int insertIgnore(@Param("userId") Long userId, @Param("recipeId") Long recipeId,
            @Param("key") String key, @Param("rating") Integer rating);
    CookingRecordRow findByKey(@Param("userId") Long userId, @Param("key") String key);
    long countCookedRecipes(@Param("userId") Long userId);
    List<CookedRecipeRow> listCookedRecipes(@Param("userId") Long userId,
            @Param("size") int size, @Param("offset") long offset);
}
