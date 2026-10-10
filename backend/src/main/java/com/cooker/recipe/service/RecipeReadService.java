package com.cooker.recipe.service;

import com.cooker.recipe.vo.PageView;
import com.cooker.recipe.vo.RecipeCard;
import com.cooker.recipe.vo.RecipeDetail;

public interface RecipeReadService {
    PageView<RecipeCard> list(int page, int size, String keyword);
    RecipeDetail detail(Long recipeId);
    void requireVisible(Long recipeId);
}
