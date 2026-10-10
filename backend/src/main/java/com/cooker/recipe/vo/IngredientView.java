package com.cooker.recipe.vo;

public record IngredientView(String name, String rawLine, String amountMin, String amountMax,
        String unitLabel, boolean optional) { }
