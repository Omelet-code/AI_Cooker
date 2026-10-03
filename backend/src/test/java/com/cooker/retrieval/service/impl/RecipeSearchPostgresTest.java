package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cooker.recipe.dto.RecipeSearchEligibility;
import com.cooker.recipe.service.RecipeSearchEligibilityService;
import com.cooker.retrieval.dto.RecipeSearchCriteria;
import com.cooker.retrieval.mapper.RecipeSearchMapper;
import com.cooker.retrieval.service.RecipeSearchService;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用项目含 zhparser 的镜像，独立新库迁移并验证真正的 PostgreSQL 检索 SQL。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RecipeSearchPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t5_test").withUsername("root").withPassword("test-only");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("cooker.retrieval.index.api-key", () -> "");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RecipeSearchService search;
    @Autowired RecipeSearchEligibilityService eligibility;
    @Autowired RecipeSearchMapper mapper;

    @Test
    void 迁移重复执行且结构化条件权限和两路去重均有效() {
        // 当前稳定基线为V1～V7加U1的V10；按数字排序核验版本，不能把版本号当迁移条数。
        assertThat(jdbc.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 10);
        assertThat(Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).load().migrate().migrationsExecuted).isZero();
        seed();
        // 未知用时必须显式允许；即使允许，空厨具集合也不能通过必需厨具约束。
        assertThat(search.search(criteria("西红柿", false, List.of(), List.of(), null))).isEmpty();
        assertThat(search.search(criteria("西红柿", true, List.of(), List.of(), null))).isEmpty();
        assertThat(search.search(criteria("西红柿", true, null, List.of(), null)))
                .extracting(item -> item.recipeId()).containsExactly(1L);
        var alias = search.search(criteria("西红柿", true, List.of("锅"), List.of(), null));
        assertThat(alias).hasSize(1);
        assertThat(alias.getFirst().recipeId()).isEqualTo(1L);
        assertThat(alias.getFirst().matchedIngredients()).containsExactly("番茄");
        assertThat(search.search(criteria("西红柿", true, List.of("锅"), List.of("西红柿"), null))).isEmpty();
        assertThat(search.search(criteria("私房番茄", true, List.of(), List.of(), 8L))).isEmpty();
        assertThat(search.search(criteria("私房番茄", true, List.of(), List.of(), 7L)))
                .extracting(item -> item.recipeId()).containsExactly(3L);
        assertThat(search.search(new RecipeSearchCriteria("番茄炒蛋", 10, List.of("other"),
                null, true, List.of("锅"), List.of(), null))).isEmpty();

        var eligible = eligibility.eligibleRecipes(new com.cooker.recipe.dto.RecipeEligibilityFilter(
                "番茄炒蛋", List.of(), null, true, List.of("锅"), List.of(), null));
        assertThat(mapper.lexical(eligible, "番茄炒蛋", false, "text-embedding-v4", 1024, 1, null))
                .extracting(item -> item.recipeId()).containsExactly(1L);
        assertThat(mapper.vector(eligible, vector(1), "text-embedding-v4", 1024, 1, null))
                .extracting(item -> item.recipeId()).containsExactly(1L, 2L);
        jdbc.update("UPDATE recipe_search_document SET embedding_model = 'old-model' WHERE recipe_id = 1");
        assertThat(search.search(criteria("番茄炒蛋", true, List.of("锅"), List.of(), null)))
                .noneMatch(item -> item.recipeId() == 1L);
        jdbc.update("UPDATE recipe_search_document SET embedding_model = 'text-embedding-v4', source_document_id = 2 WHERE recipe_id = 1");
        assertThat(search.search(criteria("番茄炒蛋", true, List.of("锅"), List.of(), null)))
                .noneMatch(item -> item.recipeId() == 1L);
    }

    private RecipeSearchCriteria criteria(String query, boolean includeUnknown, List<String> tools,
            List<String> excluded, Long viewer) {
        return new RecipeSearchCriteria(query, 10, List.of(), 30, includeUnknown, tools, excluded, viewer);
    }

    private void seed() {
        for (int id = 1; id <= 3; id++) {
            boolean user = id == 3;
            jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES (?,?,?,?,?)",
                    (long) id, user ? "USER_CUSTOM" : "SYSTEM_HOWTOCOOK", "sample-" + id,
                    user ? 7L : null, user ? "PRIVATE" : "PUBLIC");
            jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES (?,?,?,?,?,?)",
                    (long) id, (long) id, "v1", "a".repeat(64), "# 菜谱", "SUCCESS");
        }
        recipe(1, "番茄炒蛋", null);
        recipe(2, "蛋炒饭", 20);
        recipe(3, "私房番茄", 15);
        jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES (1,'番茄','REVIEWED')");
        jdbc.update("INSERT INTO ingredient_alias(ingredient_id,alias_name) VALUES (1,'西红柿')");
        jdbc.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,raw_line,sort_order) VALUES (1,1,'番茄','番茄',1)");
        jdbc.update("INSERT INTO cooking_tool(id,canonical_name) VALUES (1,'锅')");
        jdbc.update("INSERT INTO recipe_tool(recipe_id,tool_id,optional) VALUES (1,1,FALSE)");
        document(1, "overview", 0, "菜名：番茄炒蛋 分类：vegetable", 1);
        document(1, "steps", 0, "菜名：番茄炒蛋 分类：vegetable 步骤：炒熟", 1);
        document(2, "overview", 0, "菜名：蛋炒饭 分类：vegetable", 0);
        document(3, "overview", 0, "菜名：私房番茄 分类：vegetable", 1);
    }

    private void recipe(int id, String name, Integer minutes) {
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,estimated_minutes) VALUES (?,?,?,?,?,?)",
                (long) id, (long) id, (long) id, name, "vegetable", minutes);
    }

    private void document(int id, String type, int number, String content, int firstValue) {
        jdbc.update("""
                INSERT INTO recipe_search_document(recipe_id,source_document_id,source_type,owner_user_id,visibility,
                  chunk_type,chunk_no,content,content_hash,document_template_version,embedding_model,
                  embedding_dimension,embedding,embedding_status)
                VALUES (?,?,?,?,?,?,?,?,?,1,'text-embedding-v4',1024,CAST(? AS vector),'READY')
                """, (long) id, (long) id, id == 3 ? "USER_CUSTOM" : "SYSTEM_HOWTOCOOK",
                id == 3 ? 7L : null, id == 3 ? "PRIVATE" : "PUBLIC", type, number,
                content, "b".repeat(64), vector(firstValue));
    }

    private String vector(int firstValue) {
        return "[" + firstValue + "," + (firstValue == 0 ? 1 : 0) + "," + "0,".repeat(1021) + "0]";
    }
}
