package com.cooker.recipe.service.impl;

import static org.assertj.core.api.Assertions.*;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.dto.MealMaterials.ScaleStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MealMaterialCalculatorTest {
    @Test void 单人多人小数及计数分数保持参考值而不改原量() {
        var item = item(1, 1L, "REVIEWED", "1", "1", "COUNT", "只", false);
        var scaled = MealMaterialCalculator.scale(item, 3, 2);
        assertThat(scaled.referenceAmount()).isEqualByComparingTo("0.666667");
        assertThat(scaled.referenceAmount().scale()).isEqualTo(6);
        assertThat(scaled.status()).isEqualTo(ScaleStatus.REFERENCE_SCALED);
        assertThat(scaled.needsConfirmation()).isTrue();
        assertThat(scaled.original().amountMin()).isEqualByComparingTo("1");
        assertThat(MealMaterialCalculator.scale(item, 2, 1).referenceAmount()).isEqualByComparingTo("0.5");
        assertThat(MealMaterialCalculator.scale(item(2, 1L, "REVIEWED", "0.125", "0.125", "G", "g", false),
                2, 4).referenceAmount()).isEqualByComparingTo("0.25");
        assertThat(MealMaterialCalculator.scale(item(3, 1L, "REVIEWED", "0", "0", "G", "g", false),
                1, 2).referenceAmount()).isEqualByComparingTo("0");
    }

    @Test void 区间适量未知份数无单位异常量及既有归一化标签不猜测() {
        assertThat(MealMaterialCalculator.scale(item(1, null, null, "1", "2", "G", "g", false), 2, 3).status())
                .isEqualTo(ScaleStatus.ORIGINAL_ONLY);
        assertThat(MealMaterialCalculator.scale(item(1, null, null, null, null, null, null, false), 2, 3).status())
                .isEqualTo(ScaleStatus.UNKNOWN);
        var normal = item(1, null, null, "1", "1", "G", "g", false);
        for (Integer servings : new Integer[] {null, 0, -1}) {
            assertThat(MealMaterialCalculator.scale(normal, servings, 3).referenceAmount()).isNull();
        }
        for (var item : List.of(item(1, null, null, "-1", "-1", "G", "g", false),
                item(1, null, null, "1", "1", null, "g", false),
                item(1, null, null, "1", "1", "UNKNOWN", "g", false),
                item(1, null, null, "1000", "1000", "G", "kg", false),
                item(1, null, null, "1000", "1000", "ML", "l", false))) {
            var result = MealMaterialCalculator.scale(item, 2, 3);
            assertThat(result.referenceAmount()).isNull();
            assertThat(result.original()).isSameAs(item);
        }
        assertThatThrownBy(() -> MealMaterialCalculator.scale(normal, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 毫升大小写兼容与大单位不会重复乘千() {
        var ml = item(1, 1L, "UNREVIEWED", "5", "5", "ML", "mL", false);
        assertThat(MealMaterialCalculator.scale(ml, 2, 4).referenceAmount()).isEqualByComparingTo("10");
        assertThat(MealMaterialCalculator.scale(ml, 2, 4).original()).isSameAs(ml);
        var grams = item(2, 1L, "UNREVIEWED", "1000", "1000", "G", "g", false);
        assertThat(MealMaterialCalculator.scale(grams, 2, 4).referenceAmount()).isEqualByComparingTo("2000");
        assertThat(MealMaterialCalculator.scale(item(3, 1L, "UNREVIEWED", "1000", "1000", "G", "kg", false), 2, 4).referenceAmount()).isNull();
    }
    @Test void 汇总只合并确认标准同单位同可选标记且保留全部来源() {
        var first = facts(1, List.of(item(11, 7L, "REVIEWED", "1", "1", "G", "g", false),
                item(12, 7L, "REVIEWED", "1", "1", "G", "g", true),
                item(13, 7L, "REVIEWED", "1", "1", "ML", "ml", false),
                item(14, 7L, "REVIEWED", "1", "1", "KG", "kg", false),
                item(15, 7L, "REVIEWED", "1", "1", "G", "克", false),
                item(16, 7L, "UNREVIEWED", "1", "1", "G", "g", false),
                item(17, null, null, "1", "1", "G", "g", false),
                item(18, 7L, "REVIEWED", "1", "2", "G", "g", false)));
        var second = facts(2, List.of(item(21, 7L, "REVIEWED", "2", "2", "G", "g", false)));
        var result = MealMaterialCalculator.calculate(List.of(first, second), 4);
        assertThat(result.summary()).hasSize(5);
        assertThat(result.pending()).hasSize(3);
        var merged = result.summary().getFirst();
        assertThat(merged.referenceAmount()).isEqualByComparingTo("6");
        assertThat(merged.sources()).extracting(MealMaterials.Source::recipeId).containsExactly(1L, 2L);
        assertThat(merged.sources()).extracting(MealMaterials.Source::recipeIngredientId).containsExactly(11L, 21L);
        assertThat(result.summaryStatus()).isEqualTo("PARTIAL_REQUIRES_CONFIRMATION");
        assertThat(result.recipes().getFirst().facts()).isSameAs(first);
    }

    static MealRecipeFacts.Ingredient item(long rowId, Long id, String state, String min, String max,
            String code, String label, boolean optional) {
        return new MealRecipeFacts.Ingredient(rowId, id, state, "盐", "原始盐量", min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), code, label, optional, 1, 1);
    }
    static MealRecipeFacts facts(long id, List<MealRecipeFacts.Ingredient> items) {
        return new MealRecipeFacts(id, "菜" + id, "meat_dish",
                new MealRecipeFacts.RoleEvidence(MealRole.DISH, "分类", "meal-role-v1"), id, "a".repeat(64),
                2, 15, items, List.of(), List.of(), "原文补充", "fingerprint" + id);
    }
}
