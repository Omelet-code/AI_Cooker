package com.cooker.retrieval.dto;

import java.util.List;

/** T5 检索输入；null limit 使用默认值，空厨具集合表示没有可用厨具。 */
public record RecipeSearchCriteria(String query, Integer limit, List<String> categoryCodes,
        Integer maxCookingMinutes, boolean includeUnknownCookingTime,
        List<String> availableToolNames, List<String> excludedIngredientNames, Long viewerUserId) {
}
