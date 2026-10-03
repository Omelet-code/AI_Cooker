package com.cooker.recipe.dto;

import java.math.BigDecimal;
import java.util.List;

/** 内部事实快照；来源原文与结构化值同时保留，不作为旧HTTP响应。 */
public record MealRecipeFacts(long recipeId, String name, String categoryCode,
        RoleEvidence role, long sourceDocumentId, String sourceContentHash,
        Integer servings, Integer estimatedMinutes, List<Ingredient> ingredients,
        List<Step> steps, List<Tool> tools, String additionalContent, String factFingerprint) {
    public record RoleEvidence(MealRole role, String basis, String ruleVersion) {}
    public record Ingredient(long recipeIngredientId, Long ingredientId, String normalizationStatus,
            String rawName, String rawLine, BigDecimal amountMin, BigDecimal amountMax,
            String unitCode, String unitLabel, boolean optional, int sortOrder, long sourceRecipeId) {}
    public record Step(long stepId, int stepNo, String content) {}
    public record Tool(long toolId, String name, boolean optional) {}
}
