package com.cooker.recipe.dto;

import java.util.List;

/** 当前事实及权限均已核对的候选；检索模块只能使用此快照，不读取事实表。 */
public record RecipeSearchEligibility(Long recipeId, String name, Long sourceDocumentId,
        List<String> matchedIngredients) {
}
