package com.cooker.agent.service;

import com.cooker.policy.MealPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

/** 模型专属材料引用投影；展示与输出校验仍使用原完整材料。 */
public final class GuideEvidenceProjection {
    public static final String INPUT_VERSION = "guide-evidence-v2";

    private GuideEvidenceProjection() {}

    public static ObjectNode materials(JsonNode facts, JsonNode materials) {
        Map<String, JsonNode> recipes = new HashMap<>();
        Map<String, Map<String, JsonNode>> ingredients = new HashMap<>();
        for (JsonNode fact : facts) {
            String id = fact.path("recipeId").asText();
            if (recipes.put(id, fact) != null) throw MealPolicy.invalid();
            var byId = new HashMap<String, JsonNode>();
            for (JsonNode ingredient : fact.path("ingredients")) {
                if (byId.put(ingredient.path("recipeIngredientId").asText(), ingredient) != null) throw MealPolicy.invalid();
            }
            ingredients.put(id, byId);
        }
        ObjectNode compact = materials.deepCopy();
        for (JsonNode node : compact.path("recipes")) {
            ObjectNode recipe = (ObjectNode) node;
            String id = recipe.path("facts").path("recipeId").asText();
            if (!recipe.path("facts").equals(recipes.get(id))) throw MealPolicy.invalid();
            recipe.remove("facts");
            recipe.put("recipeId", id);
            for (JsonNode material : recipe.path("materials")) reference(id, (ObjectNode) material, ingredients);
        }
        for (JsonNode pending : compact.path("pending")) {
            reference(pending.path("recipeId").asText(), (ObjectNode) pending.path("material"), ingredients);
        }
        for (JsonNode summary : compact.path("summary")) {
            for (JsonNode source : summary.path("sources")) {
                resolve(source.path("recipeId").asText(), source.path("recipeIngredientId").asText(), ingredients);
            }
        }
        return compact;
    }

    private static void reference(String recipe, ObjectNode material, Map<String, Map<String, JsonNode>> ingredients) {
        JsonNode original = material.path("original");
        String id = original.path("recipeIngredientId").asText();
        if (!original.equals(resolve(recipe, id, ingredients))) throw MealPolicy.invalid();
        material.remove("original");
        material.put("recipeIngredientId", id);
    }

    private static JsonNode resolve(String recipe, String ingredient, Map<String, Map<String, JsonNode>> ingredients) {
        var byId = ingredients.get(recipe);
        if (byId == null || !byId.containsKey(ingredient)) throw MealPolicy.invalid();
        return byId.get(ingredient);
    }
}
