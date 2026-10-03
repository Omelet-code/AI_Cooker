package com.cooker.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ConstraintChanges(@NotNull @Valid ConstraintChange<Integer> maxCookingMinutes,
        @NotNull @Valid ConstraintChange<List<String>> availableToolNames,
        @NotNull @Valid ConstraintChange<List<String>> excludedIngredientNames,
        @NotNull @Valid ConstraintChange<List<String>> availableIngredientNames,
        @NotNull @Valid ConstraintChange<String> budgetYuan) { }
