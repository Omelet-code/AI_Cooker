package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.*;
import com.cooker.recipe.dto.MealMaterials.*;
import java.math.*;
import java.util.*;

/** 纯计算；不做单位换算，不猜测原文中未解析的量。 */
public final class MealMaterialCalculator {
    private MealMaterialCalculator() {}

    public static MealMaterials calculate(List<MealRecipeFacts> facts, int target) {
        if (target <= 0) throw new IllegalArgumentException("目标人数必须为正整数");
        var recipes = new ArrayList<RecipeMaterials>();
        var pending = new ArrayList<Pending>();
        var grouped = new LinkedHashMap<Key, List<Source>>();
        for (var recipe : facts) {
            var materials = new ArrayList<ScaledIngredient>();
            for (var ingredient : recipe.ingredients()) {
                var scaled = scale(ingredient, recipe.servings(), target);
                materials.add(scaled);
                if (scaled.status() == ScaleStatus.REFERENCE_SCALED && ingredient.ingredientId() != null
                        && "REVIEWED".equals(ingredient.normalizationStatus())) {
                    var key = new Key(ingredient.ingredientId(), ingredient.unitCode(), ingredient.unitLabel(), ingredient.optional());
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Source(
                            recipe.recipeId(), ingredient.recipeIngredientId(), scaled.referenceAmount()));
                } else {
                    pending.add(new Pending(recipe.recipeId(), scaled, scaled.status() == ScaleStatus.REFERENCE_SCALED
                            ? "标准食材尚未确认，按来源保留，不合并" : scaled.reason()));
                }
            }
            recipes.add(new RecipeMaterials(recipe, List.copyOf(materials)));
        }
        var summary = new ArrayList<Summary>();
        grouped.forEach((key, sources) -> summary.add(new Summary(key.ingredientId(), key.unitCode(), key.unitLabel(),
                key.optional(), sources.stream().map(Source::referenceAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                List.copyOf(sources), ScaleStatus.REFERENCE_SCALED, "仅供备料参考，用量及计数分数均需人工确认")));
        return new MealMaterials(target, List.copyOf(recipes), List.copyOf(summary), List.copyOf(pending),
                pending.isEmpty() ? "REFERENCE_ONLY" : "PARTIAL_REQUIRES_CONFIRMATION");
    }

    public static ScaledIngredient scale(MealRecipeFacts.Ingredient item, Integer servings, int target) {
        if (target <= 0) throw new IllegalArgumentException("目标人数必须为正整数");
        if (servings == null || servings <= 0) return unchanged(item, ScaleStatus.UNKNOWN, "原份数未知或无效");
        if (item.amountMin() == null || item.amountMax() == null)
            return unchanged(item, ScaleStatus.UNKNOWN, quantityReason(item.rawLine()));
        if (item.amountMin().signum() < 0 || item.amountMax().signum() < 0)
            return unchanged(item, ScaleStatus.UNKNOWN, "原数量无效");
        if (item.amountMin().compareTo(item.amountMax()) != 0)
            return unchanged(item, ScaleStatus.ORIGINAL_ONLY, "原量为区间，不取中点或与确定数量混加");
        if (!clearUnit(item.unitCode(), item.unitLabel()))
            return unchanged(item, ScaleStatus.ORIGINAL_ONLY, "单位未知或编码与原标签不一致，不做换算");
        BigDecimal reference = item.amountMin().multiply(BigDecimal.valueOf(target))
                .divide(BigDecimal.valueOf(servings), 6, RoundingMode.HALF_UP);
        return new ScaledIngredient(item, reference, ScaleStatus.REFERENCE_SCALED,
                "按人数比例得到参考量；调料及个/只等分数需要人工确认"
                        + (item.ingredientId() == null ? "；缺少明确标准食材ID" : !"REVIEWED".equals(item.normalizationStatus()) ? "；标准食材尚未确认" : ""), true);
    }

    // 只解释既有null事实，不求公式、不从说明补量，也不改变状态和计算顺序。
    private static String quantityReason(String rawLine) {
        if (rawLine != null && rawLine.matches(".*(?:[=＝]|每个|每只|每人|每份).*"))
            return "原文为公式或比例依赖，数量未确定，保留原始说明";
        if (rawLine != null && rawLine.matches(".*(?:食指|指节|水位|没过).*"))
            return "原文为水位说明，无法转换确定数量，保留原始说明";
        return "原数量或单位未知/冲突，保留适量或原始说明";
    }

    private static ScaledIngredient unchanged(MealRecipeFacts.Ingredient item, ScaleStatus status, String reason) {
        return new ScaledIngredient(item, null, status, reason, true);
    }

    private static boolean clearUnit(String code, String label) {
        if (code == null || label == null || label.isBlank()) return false;
        return switch (code) {
            case "G" -> Set.of("g", "克").contains(label.toLowerCase(Locale.ROOT));
            case "KG" -> Set.of("kg", "公斤").contains(label.toLowerCase(Locale.ROOT));
            case "ML" -> Set.of("ml", "毫升").contains(label.toLowerCase(Locale.ROOT));
            case "L" -> Set.of("l", "升").contains(label.toLowerCase(Locale.ROOT));
            case "COUNT" -> Set.of("个", "個", "只", "条", "條", "瓣", "根", "株").contains(label.toLowerCase(Locale.ROOT));
            case "CUSTOM" -> Set.of("盒", "片", "块", "塊", "勺", "匙", "杯", "斤").contains(label.toLowerCase(Locale.ROOT));
            default -> false;
        };
    }
    private record Key(long ingredientId, String unitCode, String unitLabel, boolean optional) {}
}
