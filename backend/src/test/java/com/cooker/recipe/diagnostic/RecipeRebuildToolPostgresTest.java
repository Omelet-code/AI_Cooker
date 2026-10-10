package com.cooker.recipe.diagnostic;

import static org.assertj.core.api.Assertions.*;
import com.cooker.recipe.service.RecipeImportService;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 只使用自有临时PG，验证最小上下文复用真实Service与事务，不启动开发程序。 */
@Testcontainers
class RecipeRebuildToolPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_d1r_test").withUsername("root").withPassword("test-only");
    @TempDir Path dataset;
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    @BeforeAll static void migrate() {
        source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());jdbc=new JdbcTemplate(source);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isEqualTo(15);
    }
    @BeforeEach void reset() { jdbc.execute("TRUNCATE recipe_source,ingredient,cooking_tool,recipe_import_batch RESTART IDENTITY CASCADE"); }
    @Test void 真实导入同源重跑仅新增批次并保持标准量和约人数() throws Exception {
        Files.writeString(dataset.resolve("good.md"),"# 测试菜\n## 必备原料和工具\n- 炒锅\n## 计算\n每份（约 2 人份）\n- 小龙虾 = 1 kg\n- 食用油 1 L\n- 生抽 25 mL\n## 操作\n1. 加热\n");
        Files.createDirectory(dataset.resolve("template"));Files.writeString(dataset.resolve("template/no.md"),"模板不导入");
        try(var context=RecipeRebuildTool.importContext(source)) {
            var service=context.getBean(RecipeImportService.class);var first=service.importHowToCookSnapshot(dataset,RecipeRebuildTool.SOURCE_VERSION);
            assertThat(first.candidateCount()).isEqualTo(1);assertThat(first.succeededCount()).isEqualTo(1);assertThat(first.failedCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT servings FROM recipe",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT amount_min::text FROM recipe_ingredient WHERE raw_name='小龙虾'",String.class)).isEqualTo("1000");
            assertThat(jdbc.queryForObject("SELECT unit_code FROM recipe_ingredient WHERE raw_name='食用油'",String.class)).isEqualTo("ML");
            var before=jdbc.queryForList("SELECT * FROM recipe_ingredient ORDER BY id");var again=service.importHowToCookSnapshot(dataset,RecipeRebuildTool.SOURCE_VERSION);
            assertThat(again.succeededCount()).isZero();assertThat(again.skippedCount()).isEqualTo(1);assertThat(again.failedCount()).isZero();
            assertThat(jdbc.queryForList("SELECT * FROM recipe_ingredient ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_import_batch",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_search_document",Long.class)).isZero();
        }
    }
    @Test void 后置SQL异常单文件事务回滚且批次继续真实提交其他文件() throws Exception {
        Files.writeString(dataset.resolve("a.md"),"# 正例\n## 计算\n- 盐 1g\n## 操作\n1. 加热\n");
        Files.writeString(dataset.resolve("b.md"),"# 失败例\n## 计算\n- 测试未提交食材 1g\n## 操作\n1. 触发失败\n");
        jdbc.execute("CREATE OR REPLACE FUNCTION d1r_fail_step() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.content='触发失败' THEN RAISE EXCEPTION '临时测试事务失败'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER d1r_fail_step BEFORE INSERT ON recipe_step FOR EACH ROW EXECUTE FUNCTION d1r_fail_step()");
        try(var context=RecipeRebuildTool.importContext(source)) {
            var report=context.getBean(RecipeImportService.class).importHowToCookSnapshot(dataset,RecipeRebuildTool.SOURCE_VERSION);
            assertThat(report.succeededCount()).isEqualTo(1);assertThat(report.failedCount()).isEqualTo(1);assertThat(report.failedPaths().getFirst()).startsWith("b.md：");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_source WHERE source_key='b.md'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE canonical_name='测试未提交食材'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe",Long.class)).isEqualTo(1);
        } finally {jdbc.execute("DROP TRIGGER d1r_fail_step ON recipe_step");}
    }
    @ParameterizedTest @ValueSource(strings={"jdbc:postgresql://localhost:5433/cooker","jdbc:postgresql://localhost:5433/cooker_rebuild_20261007","jdbc:postgresql://localhost:5432/cooker_rebuild_d1r2_20261007","jdbc:postgresql://other:5433/cooker_rebuild_d1r2_20261007","jdbc:postgresql://localhost:5433/cooker_rebuild_d1r2_20261007?password=bad","jdbc:postgresql://root:bad@localhost:5433/cooker_rebuild_d1r2_20261007","jdbc:postgresql:///cooker_rebuild_d1r2_20261007","jdbc:postgresql://root:bad @localhost:5433/cooker_rebuild_d1r2_20261007"})
    void 连接前拒绝旧库或超范围目标(String url) {assertThatThrownBy(() -> RecipeRebuildTool.validateTarget(url)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("bad");}
}
