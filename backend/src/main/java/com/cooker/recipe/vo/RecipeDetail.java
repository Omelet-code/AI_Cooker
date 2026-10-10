package com.cooker.recipe.vo;

import java.util.List;

public record RecipeDetail(RecipeCard recipe, Integer servings, List<IngredientView> ingredients,
        List<StepView> steps, List<ToolView> tools, String additionalContent) { }
