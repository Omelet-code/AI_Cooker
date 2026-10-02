package com.cooker.recipe.vo;

import java.time.OffsetDateTime;

public record CookedRecipeCard(RecipeCard recipe, long cookCount, OffsetDateTime lastCookedAt,
        String familiarityCode, String familiarityLabel, Integer latestRating) { }
