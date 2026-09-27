package com.cooker.retrieval.dto;

import java.util.List;

/** 排名与命中原因只反映数据库检索事实。 */
public record RecipeSearchResult(Long recipeId, int rank, Integer lexicalRank, Integer vectorRank,
        double rrfScore, List<String> matchedIngredients, List<String> matchedReasonCodes) {
}
