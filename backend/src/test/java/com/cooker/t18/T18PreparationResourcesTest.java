package com.cooker.t18;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** 校验冻结准备资源的诚实边界；不联网、不访问业务库或读取任何Key。 */
class T18PreparationResourcesTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode read(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/t18/" + name)) {
            assertThat(input).isNotNull();
            return json.readTree(input);
        }
    }

    @Test void 二十条问句均待审且不混入旧ID或虚假gold() throws Exception {
        var document = read("retrieval-gold.json");
        assertThat(document.path("queries").size()).isEqualTo(20);
        assertThat(document.path("snapshotEvidenceRef").isNull()).isTrue();
        var identifiers = new HashSet<String>();
        for (var query : document.path("queries")) {
            assertThat(identifiers.add(query.path("id").asText())).isTrue();
            assertThat(query.path("query").asText()).isNotBlank();
            assertThat(query.path("conditions").isObject()).isTrue();
            assertThat(query.path("goldStatus").asText()).isEqualTo("PENDING");
            assertThat(query.path("goldRecipeIds").isNull()).isTrue();
            assertThat(query.path("goldScope").isNull()).isTrue();
            assertThat(query.path("sourceReferences").size()).isZero();
        }
    }

    @Test void 十二类场景明确隔离来源且复用入口确实存在() throws Exception {
        var document = read("meal-scenarios.json");
        assertThat(document.path("scenarios").size()).isEqualTo(12);
        assertThat(document.path("evidenceMode").asText()).isEqualTo("ISOLATED_SUBSTITUTE");
        assertThat(document.path("candidateSeed").asLong()).isEqualTo(42);
        var identifiers = new HashSet<String>();
        for (var scene : document.path("scenarios")) {
            assertThat(identifiers.add(scene.path("id").asText())).isTrue();
            assertThat(scene.path("fixture").asText()).isNotBlank();
            String name = scene.path("reuseClass").asText();
            String module = name.equals("RecipeSearchPostgresTest") ? "retrieval/service/impl" : "agent";
            Path source = Path.of("src/test/java/com/cooker", module, name + ".java");
            assertThat(source).exists();
            if (!scene.path("reuseMethod").isNull()) {
                assertThat(Files.readString(source)).contains(scene.path("reuseMethod").asText());
            }
        }
    }

    @Test void 正式观测为零且所有缺口仍明示待执行() throws Exception {
        assertThat(read("observations-template.json").path("samples").size()).isZero();
        var matrix = read("reuse-and-gaps.json");
        assertThat(matrix.path("reuse").size()).isEqualTo(6);
        assertThat(matrix.path("gaps").size()).isEqualTo(7);
        for (var gap : matrix.path("gaps")) {
            assertThat(gap.path("status").asText()).isNotEqualTo("PASSED");
            assertThat(gap.path("detail").asText()).isNotBlank();
        }
    }
}
