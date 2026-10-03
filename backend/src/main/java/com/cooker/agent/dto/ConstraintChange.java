package com.cooker.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConstraintChange<T>(@NotBlank String action, T value,
        @Size(max = 200) String evidence) { }
