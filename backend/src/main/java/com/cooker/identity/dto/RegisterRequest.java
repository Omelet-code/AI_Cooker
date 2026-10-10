package com.cooker.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank String username, @NotBlank @Size(min=12, max=128) String password) { }
