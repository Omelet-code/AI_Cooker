package com.cooker.recipe.service;

import com.cooker.recipe.dto.CreateCookingRecordRequest;
import com.cooker.recipe.vo.CookedRecipeCard;
import com.cooker.recipe.vo.CookingRecordResult;
import com.cooker.recipe.vo.PageView;

public interface RecipeCookingService {
    CookingRecordResult complete(CreateCookingRecordRequest request);
    PageView<CookedRecipeCard> history(int page, int size);
}
