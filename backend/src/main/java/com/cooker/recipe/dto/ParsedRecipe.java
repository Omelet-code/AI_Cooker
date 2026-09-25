package com.cooker.recipe.dto;

import java.util.List;

/**
 * 与持久层无关的解析结果。失败结果也会携带 warning，供导入器审计原文诊断。
 */
public record ParsedRecipe(
        boolean successful,
        String failureMessage,
        String name,
        String categoryCode,
        String summary,
        Integer difficulty,
        Integer estimatedMinutes,
        Integer servings,
        Integer caloriesKcal,
        String additionalContent,
        List<ParsedIngredient> ingredients,
        List<String> tools,
        List<String> steps,
        List<String> warnings) {

    public static ParsedRecipe failed(String categoryCode, String reason, List<String> warnings) {
        return new ParsedRecipe(false, reason, null, categoryCode, null, null, null, null,
                null, null, List.of(), List.of(), List.of(), warnings);
    }
}
