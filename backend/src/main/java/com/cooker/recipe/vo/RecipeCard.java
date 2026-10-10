package com.cooker.recipe.vo;

public record RecipeCard(String id, String name, String categoryCode, String summary,
        Integer difficulty, Integer estimatedMinutes, boolean isFavorite) { }
