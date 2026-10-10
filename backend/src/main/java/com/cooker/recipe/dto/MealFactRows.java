package com.cooker.recipe.dto;

import java.math.BigDecimal;

/** 仅用于本模块批量Mapper映射，不跨模块传递数据库行。 */
public final class MealFactRows {
    private MealFactRows() {}
    public static class RecipeRow {
        public long recipeId;
        public String name, categoryCode, sourceType, sourceKey, sourceContentHash, additionalContent;
        public long sourceDocumentId;
        public Integer servings, estimatedMinutes;
    }
    public static class IngredientRow {
        public long recipeId, recipeIngredientId;
        public Long ingredientId;
        public String normalizationStatus, rawName, rawLine, unitCode, unitLabel;
        public BigDecimal amountMin, amountMax;
        public boolean optional;
        public int sortOrder;
    }
    public static class StepRow {
        public long recipeId, stepId;
        public int stepNo;
        public String content;
    }
    public static class ToolRow {
        public long recipeId, toolId;
        public String name;
        public boolean optional;
    }
}
