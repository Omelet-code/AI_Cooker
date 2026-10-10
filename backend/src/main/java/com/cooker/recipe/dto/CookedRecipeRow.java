package com.cooker.recipe.dto;

import java.time.OffsetDateTime;

/** 一行历史汇总包含菜谱卡片和计数，避免逐菜查询。 */
public class CookedRecipeRow extends RecipeCardRow {
    public Long cookCount;
    public OffsetDateTime lastCookedAt;
    public Integer latestRating;
}
