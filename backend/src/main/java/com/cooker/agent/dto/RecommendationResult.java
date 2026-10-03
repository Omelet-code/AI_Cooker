package com.cooker.agent.dto;

import java.util.List;

public record RecommendationResult(int schemaVersion, RecommendationOutcome outcome, String answer,
        RecommendationConstraints constraints, List<RecipeRecommendation> recipes,
        List<String> noticeCodes, ExecutionMetrics metrics) { }
