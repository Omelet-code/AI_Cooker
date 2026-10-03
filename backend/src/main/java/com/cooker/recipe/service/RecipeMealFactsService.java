package com.cooker.recipe.service;

import com.cooker.recipe.dto.*;
import java.util.List;

/** 调用方先验证可信用户与任务归属；本接口再次验证账号、资格及事实可见性。 */
public interface RecipeMealFactsService {
    void verifyViewer(long viewerUserId);
    List<String> categoriesFor(MealRole role);
    List<MealRecipeFacts> readFacts(long viewerUserId, List<Long> recipeIds);
    List<MealRecipeFacts> eligiblePool(RecipeEligibilityFilter filter, int maximumPoolSize);
    List<MealRecipeFacts> recheck(RecipeEligibilityFilter filter, List<MealRecipeFacts> previous);
    MealMaterials materials(long viewerUserId, List<Long> recipeIds, int targetDiningCount);
}
