package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cooker.retrieval.dto.RecipeSearchCriteria;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class T6RecipeHardConditionAuditorTest {
    @Test
    void 独立事实审计能分别检出时间厨具排除食材和越权违规() {
        var facts = new T6RecipeHardConditionAuditor.RecipeFacts(17L, "main", 45,
                "USER_CUSTOM", 200L, "PRIVATE", Set.of("蒸锅"), Set.of("鸡蛋", "番茄"));
        var criteria = new RecipeSearchCriteria("测试查询", 10, List.of("soup"), 20, false,
                List.of("电饭煲"), List.of("番茄"), 100L);

        var violations = T6RecipeHardConditionAuditor.auditFacts("synthetic-audit", facts, criteria);

        assertThat(violations).extracting(T6RecipeHardConditionAuditor.Violation::condition)
                .containsExactlyInAnyOrder("分类", "时间", "厨具", "排除食材", "可见性");
        assertThat(violations).allSatisfy(violation -> {
            assertThat(violation.recipeId()).isEqualTo(17L);
            assertThat(violation.reason()).isNotBlank();
        });
    }

    @Test
    void 独立事实审计接受满足硬条件的公开菜谱() {
        var facts = new T6RecipeHardConditionAuditor.RecipeFacts(18L, "main", 15,
                "SYSTEM_HOWTOCOOK", null, "PUBLIC", Set.of("电饭煲"), Set.of("鸡蛋", "番茄"));
        var criteria = new RecipeSearchCriteria("测试查询", 10, List.of("main"), 20, false,
                List.of("电饭煲"), List.of("花生"), null);

        assertThat(T6RecipeHardConditionAuditor.auditFacts("synthetic-valid", facts, criteria)).isEmpty();
    }
}
