package com.cooker.recipe.dto.repair;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只有隔离验证或显式应用产生此结果，投影影响只是候选。 */
public record RecipeRepairResult(UUID requestKey, String status, boolean applied, List<Long> recipeIds,
        Map<String, Long> lineIdMappings, List<Long> createdIngredientIds, int approvedCandidates) { }
