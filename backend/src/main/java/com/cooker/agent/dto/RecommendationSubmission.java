package com.cooker.agent.dto;

import org.springframework.http.HttpStatus;

public record RecommendationSubmission(HttpStatus status, String location, RecommendationTurnView body) { }
