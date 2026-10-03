package com.cooker.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RecommendationSelectionResponse(@NotNull @Size(min = 1, max = 3)
        List<@Valid Selection> selections) {
    public record Selection(@NotBlank String recipeId,
            @NotNull @Size(min = 1) List<@NotBlank String> reasonCodes) { }
}
