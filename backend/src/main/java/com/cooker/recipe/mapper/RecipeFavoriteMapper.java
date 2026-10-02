package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.FavoriteRecipeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeFavoriteMapper extends BaseMapper<FavoriteRecipeEntity> {
    int insertIgnore(@Param("userId") Long userId, @Param("recipeId") Long recipeId);
    int deleteFavorite(@Param("userId") Long userId, @Param("recipeId") Long recipeId);
}
