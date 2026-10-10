package com.cooker.recipe.dto;

import tools.jackson.databind.JsonNode;

public record CreateCookingRecordRequest(String recipeId, String idempotencyKey, JsonNode rating) { }
