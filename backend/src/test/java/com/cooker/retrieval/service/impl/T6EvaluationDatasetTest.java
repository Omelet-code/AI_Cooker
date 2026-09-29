package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class T6EvaluationDatasetTest {
    @Test
    void 固定集覆盖要求的查询类别并为每条记录固定版本() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream stream = getClass().getResourceAsStream("/retrieval-evaluation/t6-dataset-v1.json")) {
            var dataset = mapper.readValue(stream, T6RecipeSearchEvaluationTest.Dataset.class);
            assertThat(dataset.queries()).hasSizeGreaterThanOrEqualTo(30);
            assertThat(dataset.queries()).allSatisfy(query -> {
                assertThat(query.datasetVersion()).isEqualTo("howtocook-1.6.0-a0b14bd");
                assertThat(query.indexVersion()).isEqualTo("text-embedding-v4:1024:template-1");
                assertThat(query.relevantRecipes()).isNotEmpty();
            });
            assertThat(dataset.queries()).extracting(T6RecipeSearchEvaluationTest.EvalQuery::group)
                    .contains("精确菜名", "原料及别名", "短中文及模糊菜名", "自然语言偏好", "时间约束", "厨具约束", "排除食材");
            assertThat(dataset.boundaryCases()).hasSizeGreaterThanOrEqualTo(1);
            assertThat(dataset.labelVersion()).isEqualTo("t6-labels-v1-2026-09-29");
            assertThat(dataset.labelSetSha256()).isEqualTo("c38b2c76305eb205fb3c648f486128d175fabf267640c8e6e3aeb05cf83b6bc7");
            assertThat(dataset.labelStatus()).isEqualTo("用户整体审定（非逐条记录）");
            assertThat(dataset.labelApproval().approvedAt()).isEqualTo("2026-09-29");
            assertThat(dataset.labelApproval().scope()).contains("不代表逐条审定记录");
            String frozenLabels = dataset.queries().stream()
                    .map(query -> query.id() + "|" + query.relevantRecipes().stream()
                            .map(recipe -> recipe.id() + ":" + recipe.reason()).collect(Collectors.joining(";")))
                    .collect(Collectors.joining("\n"));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(frozenLabels.getBytes(StandardCharsets.UTF_8)));
            assertThat(digest).isEqualTo(dataset.labelSetSha256());
        }
    }
}
