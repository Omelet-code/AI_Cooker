package com.cooker.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password, Boolean rememberMe) { }
