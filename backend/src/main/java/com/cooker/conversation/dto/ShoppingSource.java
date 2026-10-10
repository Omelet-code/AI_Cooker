package com.cooker.conversation.dto;

import com.cooker.recipe.dto.MealMaterials;
import java.math.BigDecimal;
import java.util.List;

/** 仅可信模块调用的选餐采购来源；完整材料来自已保存方案，独立于模型指导。 */
public record ShoppingSource(long conversationId, long mealId, int version, long optionId,
        int diningCount, BigDecimal budgetYuan, List<RecipeReference> recipes, MealMaterials materials) {
    public record RecipeReference(long recipeId, String factFingerprint) {}
}
