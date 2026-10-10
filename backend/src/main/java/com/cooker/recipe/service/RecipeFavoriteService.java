package com.cooker.recipe.service;

import com.cooker.recipe.vo.FavoriteState;
import com.cooker.recipe.vo.PageView;
import com.cooker.recipe.vo.RecipeCard;

public interface RecipeFavoriteService {
    FavoriteState add(Long recipeId);
    FavoriteState remove(Long recipeId);
    PageView<RecipeCard> list(int page, int size);
}
