package com.cooker.retrieval.dto;

/** Mapper 每路最多返回三十道菜；每道菜已在 SQL 中选出最佳块。 */
public record RecipeSearchCandidate(Long recipeId, Integer matchTier, Double lexicalScore, Double distance) {
}
