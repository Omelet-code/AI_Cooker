package com.cooker.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendRecommendationRequest(
        @NotBlank @Size(min = 36, max = 36) String requestId,
        @NotBlank @Size(max = 8000) String content) { }
