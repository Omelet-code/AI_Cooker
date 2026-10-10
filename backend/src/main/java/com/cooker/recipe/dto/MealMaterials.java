package com.cooker.recipe.dto;

import java.math.BigDecimal;
import java.util.List;

public record MealMaterials(int targetDiningCount, List<RecipeMaterials> recipes,
        List<Summary> summary, List<Pending> pending, String summaryStatus) {
    public enum ScaleStatus { REFERENCE_SCALED, ORIGINAL_ONLY, UNKNOWN }
    public record ScaledIngredient(MealRecipeFacts.Ingredient original, BigDecimal referenceAmount,
            ScaleStatus status, String reason, boolean needsConfirmation) {}
    public record RecipeMaterials(MealRecipeFacts facts, List<ScaledIngredient> materials) {}
    public record Source(long recipeId, long recipeIngredientId, BigDecimal referenceAmount) {}
    public record Summary(long ingredientId, String unitCode, String unitLabel, boolean optional,
            BigDecimal referenceAmount, List<Source> sources, ScaleStatus status, String reason) {}
    public record Pending(long recipeId, ScaledIngredient material, String reason) {}
}
