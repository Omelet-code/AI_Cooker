package com.cooker.agent.dto;

import java.util.List;

public record RecipeRecommendation(String recipeId, String name, String summary,
        Integer estimatedMinutes, List<ReasonCode> reasonCodes) { }
