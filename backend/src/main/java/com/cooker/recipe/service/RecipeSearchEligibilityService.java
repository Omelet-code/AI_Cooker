package com.cooker.recipe.service;

import com.cooker.recipe.dto.RecipeEligibilityFilter;
import com.cooker.recipe.dto.RecipeSearchEligibility;
import java.util.List;

/** 事实层统一判断菜谱资格，检索层不得绕过此接口读取事实 Mapper。 */
public interface RecipeSearchEligibilityService {
    List<RecipeSearchEligibility> eligibleRecipes(RecipeEligibilityFilter filter);
}
