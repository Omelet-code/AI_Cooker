package com.cooker.recipe.vo;

import java.time.OffsetDateTime;

public record CookingRecord(String id, String recipeId, OffsetDateTime cookedAt, Integer rating) { }
