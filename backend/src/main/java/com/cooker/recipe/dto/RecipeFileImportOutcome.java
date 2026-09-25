package com.cooker.recipe.dto;

public record RecipeFileImportOutcome(Status status, int warningCount, String message) {
    public enum Status { SUCCEEDED, SKIPPED, FAILED }
}
