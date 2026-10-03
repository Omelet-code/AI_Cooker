package com.cooker.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UnderstandingResponse(@NotBlank String intent, @NotBlank String query,
        @NotNull @Valid ConstraintChanges changes,
        @NotNull @Size(max = 4) List<@NotBlank String> clarificationCodes) { }
