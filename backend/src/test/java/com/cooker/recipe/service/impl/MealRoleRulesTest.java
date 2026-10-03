package com.cooker.recipe.service.impl;

import static org.assertj.core.api.Assertions.*;
import com.cooker.recipe.dto.MealRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class MealRoleRulesTest {
    private final MealRoleRules rules = new MealRoleRules();
    @Test void 五个例外哈希匹配为其他失配未知且不回退汤() throws Exception {
        try (var input = getClass().getResourceAsStream("/recipe/meal-role-v1-exceptions.tsv")) {
            for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                var fields = line.split("\t", 5);
                assertThat(rules.classify("SYSTEM_HOWTOCOOK", fields[0], fields[1], fields[2]).role()).isEqualTo(MealRole.OTHER);
                assertThat(rules.classify("SYSTEM_HOWTOCOOK", fields[0], fields[1], "b".repeat(64)).role()).isEqualTo(MealRole.UNKNOWN);
                assertThat(rules.classify("USER_CUSTOM", fields[0], fields[1], fields[2]).role()).isEqualTo(MealRole.SOUP);
            }
        }
    }
    @Test void 分类规则未知与私有类别依据没有营养承诺() {
        for (String category : List.of("aquatic", "meat_dish", "vegetable_dish"))
            assertThat(rules.classify("SYSTEM_HOWTOCOOK", "普通", category, "a").role()).isEqualTo(MealRole.DISH);
        assertThat(rules.classify("USER_CUSTOM", "普通", "soup", "a").basis()).contains("未逐菜人工审定");
        assertThat(rules.classify("USER_CUSTOM", "普通", "staple", "a").role()).isEqualTo(MealRole.OTHER);
        assertThat(rules.classify("USER_CUSTOM", "普通", null, "a").role()).isEqualTo(MealRole.UNKNOWN);
        assertThat(rules.classify("USER_CUSTOM", "普通", "new-category", "a").role()).isEqualTo(MealRole.UNKNOWN);
        assertThat(rules.classify("SYSTEM_HOWTOCOOK", "普通", "soup", "a").ruleVersion()).isEqualTo("meal-role-v1");
    }
}
