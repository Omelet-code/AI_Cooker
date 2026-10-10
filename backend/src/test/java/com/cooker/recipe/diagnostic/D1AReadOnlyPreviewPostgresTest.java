package com.cooker.recipe.diagnostic;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 临时隔离PG：不启动应用，真实验证事务只读、公开过滤、hash与逐行提案。 */
@Testcontainers(disabledWithoutDocker = true)
class D1AReadOnlyPreviewPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_d1a_test").withUsername("root").withPassword("test-only");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW = "# 测试菜\n## 计算\n两人食用。\n- 鸡蛋 = 一个\n- 盐 1g\n## 操作\n1. 加热\n";
    @BeforeAll static void setup() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        try (Connection c = connection(); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO recipe_source(id,source_type,source_key,visibility) VALUES(1,'SYSTEM_HOWTOCOOK','home/test.md','PUBLIC')");
            try (var p = c.prepareStatement("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(1,1,'fixture',?,?,'SUCCESS')")) {
                p.setString(1, RecipeParsePreviewTool.sha256(RAW)); p.setString(2, RAW); p.executeUpdate();
            }
            s.executeUpdate("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,servings) VALUES(1,1,1,'测试菜','home',2)");
            s.executeUpdate("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES(1,'鸡蛋 =','UNREVIEWED'),(2,'鸡蛋','UNREVIEWED'),(3,'盐','UNREVIEWED')");
            s.executeUpdate("INSERT INTO recipe_ingredient(id,recipe_id,ingredient_id,raw_name,amount_min,amount_max,unit_code,unit_label,raw_line,sort_order) VALUES(11,1,1,'鸡蛋 = 一个',null,null,null,null,'鸡蛋 = 一个',1),(12,1,3,'盐',1,1,'G','g','盐 1g',2)");
            s.executeUpdate("INSERT INTO recipe_source(id,source_type,source_key,visibility,owner_user_id) VALUES(2,'USER_CUSTOM','private/never-export.md','PRIVATE',7)");
            s.executeUpdate("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(2,2,'fixture',repeat('b',64),'不允许导出的私有原文','SUCCESS')");
            s.executeUpdate("INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES(2,2,2,'私有菜','private')");
        }
    }
    static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    static Connection readOnly() throws SQLException {
        Connection c = connection(); c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false); return c;
    }
    @Test void 真实只读快照不含私有原文且PG拒绝写入() throws Exception {
        try (Connection c = readOnly()) {
            var snapshot = RecipeParsePreviewTool.readSnapshot(c);
            assertThat(snapshot.stream().filter(n -> "recipe".equals(n.path("kind").asText())).toList()).hasSize(1);
            assertThat(snapshot.toString()).doesNotContain("不允许导出的私有原文", "private/never-export.md");
            try (var s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate("UPDATE recipe SET name='意外写入' WHERE id=1"))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("25006"));
            }
            c.rollback();
        }
        try (Connection c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT name FROM recipe WHERE id=1")) { assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("测试菜"); }
    }
    @Test void 可写连接自动提交或不一致隔离立即拒绝() throws Exception {
        try (Connection c = connection()) {
            assertThatThrownBy(() -> RecipeParsePreviewTool.readSnapshot(c)).isInstanceOf(IllegalArgumentException.class);
            c.setAutoCommit(false);
            assertThatThrownBy(() -> RecipeParsePreviewTool.readSnapshot(c)).isInstanceOf(IllegalArgumentException.class);
            c.rollback();
        }
    }
    @Test void 原ID原行未变语义和稳定预览() throws Exception {
        try (Connection c = readOnly()) {
            var snapshot = RecipeParsePreviewTool.readSnapshot(c);
            var tool = new RecipeParsePreviewTool(); var result = tool.preview(snapshot);
            var rows = result.stream().filter(n -> "line".equals(n.path("kind").asText())).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst().path("lineId").asLong()).isEqualTo(11);
            assertThat(rows.getFirst().path("newFields").path("name").asText()).isEqualTo("鸡蛋");
            assertThat(rows.getFirst().path("newFields").path("amountMin").asText()).isEqualTo("1");
            assertThat(rows.getFirst().path("sourceLineNumber").asInt()).isEqualTo(4);
            assertThat(rows.getFirst().path("identityDecision").asText()).isEqualTo("PENDING_REVIEW_NO_MERGE");
            assertThat(rows.get(1).path("status").asText()).isEqualTo("UNCHANGED");
            assertThat(rows.get(1).path("oldFields")).isEqualTo(rows.get(1).path("newFields"));
            assertThat(tool.preview(snapshot)).isEqualTo(result);
            assertThat(result.getLast().path("applied").asBoolean()).isFalse();
            c.rollback();
        }
    }
    @Test void hash漂移和伪造可写来源不生成提案() throws Exception {
        try (Connection c = readOnly()) {
            var snapshot = new ArrayList<>(RecipeParsePreviewTool.readSnapshot(c));
            var recipe = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.stream().filter(n -> "recipe".equals(n.path("kind").asText())).findFirst().orElseThrow();
            recipe.put("rawMarkdown", RAW + "漂移");
            assertThatThrownBy(() -> new RecipeParsePreviewTool().preview(snapshot)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hash");
            ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.getFirst()).put("readOnly", "off");
            assertThatThrownBy(() -> new RecipeParsePreviewTool().preview(snapshot)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("只读");
            c.rollback();
        }
    }
    /** 同一隔离PG公开快照只替换内存原文，锁定依据标签而不写开发库。 */
    @org.junit.jupiter.params.ParameterizedTest(name = "V2预览人数依据：{0}→{2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "两人食用,2,EXPLICIT_PEOPLE",
            "每份（约 2 人份）：,2,APPROXIMATE_PEOPLE_AS_BASELINE",
            "约 3人,3,APPROXIMATE_PEOPLE_AS_BASELINE",
            "3人左右,3,APPROXIMATE_PEOPLE_AS_BASELINE",
            "大概 3人,3,APPROXIMATE_PEOPLE_AS_BASELINE",
            "2到3人,2,RANGE_MIN_AS_BASELINE",
            "2-3人,2,RANGE_MIN_AS_BASELINE",
            "二至三人,2,RANGE_MIN_AS_BASELINE",
            "约2到3人,2,RANGE_MIN_AS_BASELINE",
            "2人；约2人,2,APPROXIMATE_PEOPLE_AS_BASELINE",
            "2人；2-3人,2,RANGE_MIN_AS_BASELINE",
            "2人；约3人,,UNKNOWN_REQUIRES_REVIEW",
            "0人,,UNKNOWN_REQUIRES_REVIEW",
            "3到2人,,UNKNOWN_REQUIRES_REVIEW",
            "2到3.5人,,UNKNOWN_REQUIRES_REVIEW",
            "每2份,,UNKNOWN_REQUIRES_REVIEW",
            "6寸蛋糕,,UNKNOWN_REQUIRES_REVIEW",
            "成品10个,,UNKNOWN_REQUIRES_REVIEW",
            "未写人数,,UNKNOWN_REQUIRES_REVIEW"
    })
    void v2人数预览依据与策略一致(String people, Integer expected, String basis) throws Exception {
        try (Connection c = readOnly()) {
            var snapshot = new ArrayList<>(RecipeParsePreviewTool.readSnapshot(c));
            var original = snapshot.stream().filter(n -> "recipe".equals(n.path("kind").asText())).findFirst().orElseThrow();
            var replacement = original.deepCopy();
            String raw = RAW.replace("两人食用。", people);
            ((com.fasterxml.jackson.databind.node.ObjectNode) replacement).put("rawMarkdown", raw)
                    .put("contentHash", RecipeParsePreviewTool.sha256(raw));
            snapshot.set(snapshot.indexOf(original), replacement);
            var tool = new RecipeParsePreviewTool();
            var result = tool.preview(snapshot);
            var recipe = result.stream().filter(n -> "recipe".equals(n.path("kind").asText())).findFirst().orElseThrow();
            if (expected == null) assertThat(recipe.path("newServings").isNull()).isTrue();
            else assertThat(recipe.path("newServings").asInt()).isEqualTo(expected);
            assertThat(recipe.path("servingsBasis").asText()).isEqualTo(basis);
            assertThat(recipe.path("ruleVersion").asText()).isEqualTo(com.cooker.recipe.parser.HowToCookMarkdownParser.RULE_VERSION);
            assertThat(recipe.path("applied").asBoolean()).isFalse();
            assertThat(tool.preview(snapshot)).isEqualTo(result);
            var rows = result.stream().filter(n -> "line".equals(n.path("kind").asText())).toList();
            assertThat(rows).extracting(n -> n.path("lineId").asLong()).containsExactly(11L, 12L);
            assertThat(rows.get(1).path("oldFields")).isEqualTo(rows.get(1).path("newFields"));
            c.rollback();
        }
    }

}
