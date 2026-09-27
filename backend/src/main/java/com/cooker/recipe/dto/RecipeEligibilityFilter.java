package com.cooker.recipe.dto;

import java.util.List;

/** 检索前由菜谱事实层执行的硬条件与权限条件。 */
public record RecipeEligibilityFilter(String query, List<String> categoryCodes, Integer maxCookingMinutes,
        boolean includeUnknownCookingTime, List<String> availableToolNames,
        List<String> excludedIngredientNames, Long viewerUserId) {
}
