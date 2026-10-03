package com.cooker.recipe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.cooker.common.ApiException;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.retrieval.dto.*;
import com.cooker.retrieval.dto.MealCandidateRequest.*;
import com.cooker.retrieval.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真实隔离PG；检索和模型均替身，不访问开发5433、不发送收费请求。 */
@SpringBootTest
@Testcontainers
class MealFactsPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_u2_facts").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("cooker.identity.jwt-secret", () -> "test-only-u2-secret-01234567890123456789");
        r.add("cooker.identity.llm-credential.master-key", () -> "");
        r.add("cooker.retrieval.index.api-key", () -> "");
        r.add("cooker.t6.enabled", () -> "false");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired RecipeMealFactsService facts;
    @Autowired MealCandidateService candidates;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RecipeSearchService search;
    @MockitoBean QueryEmbeddingService queryEmbedding;
    @MockitoBean EmbeddingModel embedding;

    @BeforeEach void isolate() {
        jdbc.execute("TRUNCATE recipe_source, app_user, ingredient, cooking_tool RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES (1,'u2_a','test-only'),(2,'u2_b','test-only')");
    }

    @Test void 系统本人他人边界与五个系统粥例外哈希失配私有同路径() throws Exception {
        var lines = new String(getClass().getResourceAsStream("/recipe/meal-role-v1-exceptions.tsv")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        long id = 10;
        for (String line : lines) {
            var fields = line.split("\t", 5);
            seed(id, "soup", null, fields[0], fields[2], 2, 20);
            assertThat(facts.readFacts(1, List.of(id)).getFirst().role().role()).isEqualTo(MealRole.OTHER);
            jdbc.update("UPDATE recipe_source_document SET content_hash=? WHERE id=?", "b".repeat(64), id);
            assertThat(facts.readFacts(1, List.of(id)).getFirst().role().role()).isEqualTo(MealRole.UNKNOWN);
            id++;
        }
        seed(30, "soup", 1L, "soup/米粥.md", "a".repeat(64), 2, 20);
        seed(31, "meat_dish", 2L, "私有", "a".repeat(64), 2, 20);
        seed(32, "未知", null, "未知", "a".repeat(64), null, null);
        assertThat(facts.readFacts(1, List.of(30L)).getFirst().role().role()).isEqualTo(MealRole.SOUP);
        assertThat(facts.readFacts(1, List.of(32L)).getFirst().role().role()).isEqualTo(MealRole.UNKNOWN);
        assertThatThrownBy(() -> facts.materials(1, List.of(30L, 31L), 2)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("RECIPE_NOT_FOUND"));
        assertThatThrownBy(() -> facts.materials(1, List.of(30L, 999L), 2)).isInstanceOf(ApiException.class);
        verifyNoInteractions(search, queryEmbedding, embedding);
    }

    @Test void 资格复用厨具空与缺省时间未知排除别名及分类交集() {
        seed(10, "meat_dish", null, "系统", "a".repeat(64), 2, 10);
        seed(11, "meat_dish", 1L, "本人", "a".repeat(64), null, null);
        seed(12, "meat_dish", 2L, "他人", "a".repeat(64), 2, 10);
        seed(13, "soup", null, "汤", "a".repeat(64), 2, 40);
        jdbc.update("INSERT INTO cooking_tool(id,canonical_name) VALUES (1,'炒锅')");
        jdbc.update("INSERT INTO recipe_tool(recipe_id,tool_id,optional) VALUES (10,1,false)");
        jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES (1,'番茄','REVIEWED')");
        jdbc.update("INSERT INTO ingredient_alias(ingredient_id,alias_name) VALUES (1,'西红柿')");
        ingredient(11, 1, 1L, "1", "1", "G", "g", false);
        assertThat(ids(facts.eligiblePool(filter(null, null, null, true, null, null), 1000))).containsExactly(10L, 11L, 13L);
        assertThat(ids(facts.eligiblePool(filter(null, null, null, true, List.of(), null), 1000))).containsExactly(11L, 13L);
        assertThat(ids(facts.eligiblePool(filter(null, null, 20, false, null, null), 1000))).containsExactly(10L);
        assertThat(ids(facts.eligiblePool(filter(null, null, 20, true, null, null), 1000))).containsExactly(10L, 11L);
        assertThat(ids(facts.eligiblePool(filter(null, null, null, true, null, List.of("西红柿")), 1000)))
                .containsExactly(10L, 13L);
        var request = request(Mode.RANDOM, filter(null, List.of("soup"), null, true, null, null), Long.MIN_VALUE, 2, 2);
        var result = candidates.candidates(request);
        assertThat(result.groups().getFirst().candidates()).isEmpty();
        assertThat(result.groups().getLast().candidates()).hasSize(1);
        assertThat(result.groups()).allMatch(group -> group.shortage());
        verifyNoInteractions(search, queryEmbedding, embedding);
    }

    @Test void 完整随机池稳定种子无重复如实不足且零模型() {
        for (int id = 10; id < 20; id++) seed(id, "meat_dish", null, "菜" + id, "a".repeat(64), 2, 10);
        seed(30, "soup", null, "汤", "a".repeat(64), null, null);
        var request = request(Mode.RANDOM, filter(null, null, null, true, null, null), Long.MAX_VALUE, 5, 2);
        var first = candidates.candidates(request);
        assertThat(candidates.candidates(request)).isEqualTo(first);
        assertThat(first.seed()).isEqualTo(Long.MAX_VALUE);
        assertThat(first.groups().getFirst().candidates()).hasSize(5).extracting(c -> c.facts().recipeId()).doesNotHaveDuplicates();
        assertThat(first.groups().getLast().shortage()).isTrue();
        assertThat(first.groups().getLast().candidates().getFirst().servingsUnknown()).isTrue();
        assertThat(first.groups().getLast().candidates().getFirst().cookingTimeUnknown()).isTrue();
        verifyNoInteractions(search, queryEmbedding, embedding);
    }

    @Test void 随机资格池一千接受一千零一拒绝而不截断() {
        jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,visibility) SELECT n,'SYSTEM_HOWTOCOOK','pool-'||n,'PUBLIC' FROM generate_series(1,1001) n");
        jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) SELECT n,n,'u2',repeat('a',64),'# 池菜','SUCCESS' FROM generate_series(1,1001) n");
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code) SELECT n,n,n,'池菜'||n,'meat_dish' FROM generate_series(1,1001) n");
        var request = request(Mode.RANDOM, filter(null, null, null, true, null, null), 1L, 20, 1);
        assertThatThrownBy(() -> candidates.candidates(request)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("RESOURCE_LIMIT"));
        jdbc.update("DELETE FROM recipe WHERE id=1001");
        assertThat(candidates.candidates(request).groups().getFirst().candidates()).hasSize(20);
        verifyNoInteractions(search, queryEmbedding, embedding);
    }

    @Test void 批量完整材料步骤厨具原文人数折算未知与标准确认() {
        seed(10, "meat_dish", null, "菜", "a".repeat(64), 2, 10);
        seed(11, "soup", 1L, "汤", "a".repeat(64), null, null);
        jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES (1,'盐','REVIEWED'),(2,'未知盐','UNREVIEWED')");
        ingredient(10, 1, 1L, "1.25", "1.25", "G", "g", false);
        ingredient(10, 2, 1L, "1", "2", "G", "g", false);
        ingredient(10, 3, null, null, null, null, null, true);
        ingredient(10, 4, 2L, "1", "1", "COUNT", "只", false);
        ingredient(10, 5, 1L, "1", "1", "ML", "ml", false);
        ingredient(10, 6, 1L, "1", "1", "G", "g", true);
        ingredient(10, 7, 1L, "1000", "1000", "G", "kg", false);
        ingredient(11, 1, 1L, "1", "1", "G", "g", false);
        for (int order = 8; order <= 25; order++) ingredient(10, order, null, null, null, null, null, false);
        jdbc.update("INSERT INTO recipe_step(recipe_id,step_no,content) VALUES (10,1,'第一步'),(10,2,'第二步'),(11,1,'汤步骤')");
        jdbc.update("INSERT INTO cooking_tool(id,canonical_name) VALUES (1,'炒锅'),(2,'砂锅')");
        jdbc.update("INSERT INTO recipe_tool(recipe_id,tool_id,optional) VALUES (10,1,false),(10,2,true),(11,2,false)");
        var result = facts.materials(1, List.of(11L, 10L), 3);
        assertThat(result.recipes()).extracting(r -> r.facts().recipeId()).containsExactly(11L, 10L);
        var dish = result.recipes().getLast();
        assertThat(dish.materials()).hasSize(25);
        assertThat(dish.facts().steps()).hasSize(2);
        assertThat(dish.facts().tools()).hasSize(2);
        assertThat(dish.facts().additionalContent()).isEqualTo("原始补充");
        assertThat(dish.facts().sourceContentHash()).isEqualTo("a".repeat(64));
        assertThat(dish.materials().getFirst().referenceAmount()).isEqualByComparingTo("1.875");
        assertThat(dish.materials().getFirst().original().amountMin()).isEqualByComparingTo("1.25");
        assertThat(result.summary()).hasSize(3);
        assertThat(result.pending()).hasSize(23);
        assertThat(result.recipes().getFirst().materials().getFirst().referenceAmount()).isNull();
        assertThat(dish.materials().get(3).referenceAmount()).isEqualByComparingTo("1.5");
        assertThat(dish.materials().get(3).needsConfirmation()).isTrue();
        assertThat(dish.materials().getFirst().original().sourceRecipeId()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();
    }

    @Test void 指纹含材料步骤厨具份数而不只原文哈希且复核删除撤权改变() {
        seed(10, "meat_dish", 1L, "私有", "a".repeat(64), 2, 10);
        ingredient(10, 1, null, "1", "1", "G", "g", false);
        jdbc.update("INSERT INTO recipe_step(recipe_id,step_no,content) VALUES (10,1,'原步骤')");
        jdbc.update("INSERT INTO cooking_tool(id,canonical_name) VALUES (1,'炒锅')");
        jdbc.update("INSERT INTO recipe_tool(recipe_id,tool_id,optional) VALUES (10,1,false)");
        var snapshot = facts.readFacts(1, List.of(10L));
        assertThat(facts.readFacts(1, List.of(10L))).isEqualTo(snapshot);
        for (String change : List.of("UPDATE recipe_ingredient SET raw_line='新原料行' WHERE recipe_id=10",
                "UPDATE recipe_step SET content='新步骤' WHERE recipe_id=10",
                "UPDATE recipe_tool SET optional=true WHERE recipe_id=10", "UPDATE recipe SET servings=3 WHERE id=10")) {
            jdbc.execute(change);
            var current = facts.readFacts(1, List.of(10L));
            assertThat(current.getFirst().factFingerprint()).isNotEqualTo(snapshot.getFirst().factFingerprint());
            assertThat(current.getFirst().sourceContentHash()).isEqualTo(snapshot.getFirst().sourceContentHash());
            assertThat(facts.recheck(filter(null, null, null, true, null, null), snapshot)).isEmpty();
            snapshot = current;
        }
        jdbc.update("UPDATE recipe_source SET owner_user_id=2 WHERE id=10");
        assertThat(facts.recheck(filter(null, null, null, true, null, null), snapshot)).isEmpty();
        jdbc.update("UPDATE recipe_source SET owner_user_id=1 WHERE id=10");
        jdbc.update("DELETE FROM recipe WHERE id=10");
        assertThat(facts.recheck(filter(null, null, null, true, null, null), snapshot)).isEmpty();
    }

    @Test void 语义最多两次保持T5顺序例外不足不补齐且外部等待无事务() {
        seed(10, "meat_dish", null, "菜10", "a".repeat(64), 2, 10);
        seed(11, "meat_dish", null, "菜11", "a".repeat(64), 2, 10);
        seed(20, "soup", null, "汤", "a".repeat(64), 2, 10);
        seed(21, "soup", null, "soup/米粥.md", "538a5fb1becb4d6143f01ccfc98a2685b50de71918e53fd2a1cc7f08616849f5", 2, 10);
        when(search.search(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            RecipeSearchCriteria criteria = call.getArgument(0);
            assertThat(criteria.limit()).isEqualTo(2);
            assertThat(criteria.viewerUserId()).isEqualTo(1);
            assertThat(criteria.query()).isEqualTo("番茄");
            return criteria.categoryCodes().contains("soup") ? List.of(rank(21, 1), rank(20, 2))
                    : List.of(rank(11, 1), rank(10, 2));
        });
        var result = new TransactionTemplate(transactions).execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var value = candidates.candidates(request(Mode.SEMANTIC, filter("番茄", null, null, true, null, null), null, 2, 2));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return value;
        });
        assertThat(result.groups().getFirst().candidates()).extracting(c -> c.facts().recipeId()).containsExactly(11L, 10L);
        assertThat(result.groups().getLast().candidates()).extracting(c -> c.facts().recipeId()).containsExactly(20L);
        assertThat(result.groups().getLast().candidates().getFirst().searchRank().rank()).isEqualTo(2);
        assertThat(result.groups().getLast().shortage()).isTrue();
        verify(search, times(2)).search(any());
        verifyNoInteractions(queryEmbedding, embedding);
    }

    @Test void 后一角色检索期间前一角色事实改变最终复核剔除且不追加() {
        seed(10, "meat_dish", null, "菜", "a".repeat(64), 2, 10);
        seed(20, "soup", null, "汤", "a".repeat(64), 2, 10);
        when(search.search(any())).thenAnswer(call -> {
            RecipeSearchCriteria criteria = call.getArgument(0);
            if (criteria.categoryCodes().contains("soup")) {
                jdbc.update("UPDATE recipe SET servings=3 WHERE id=10");
                return List.of(rank(20, 1));
            }
            return List.of(rank(10, 1));
        });
        var result = candidates.candidates(request(Mode.SEMANTIC, filter("偏好", null, null, true, null, null), null, 1, 1));
        assertThat(result.groups().getFirst().candidates()).isEmpty();
        assertThat(result.groups().getFirst().shortage()).isTrue();
        assertThat(result.groups().getLast().candidates()).hasSize(1);
        verify(search, times(2)).search(any());
    }

    @Test void 显式用户参数批量边界与合法最大人数不会从档案推断() {
        seed(10, "meat_dish", null, "菜", "a".repeat(64), 2, 10);
        ingredient(10, 1, null, "1", "1", "G", "g", false);
        for (List<Long> ids : List.of(List.<Long>of(), List.of(10L, 10L), List.of(0L),
                java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList()))
            assertThatThrownBy(() -> facts.materials(1, ids, 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> facts.materials(0, List.of(10L), 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> facts.materials(99, List.of(10L), 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> facts.materials(1, List.of(10L), 0)).isInstanceOf(ApiException.class);
        var result = facts.materials(1, List.of(10L), Integer.MAX_VALUE);
        assertThat(result.recipes().getFirst().materials().getFirst().referenceAmount()).isEqualByComparingTo("1073741823.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();
    }

    private void seed(long id, String category, Long owner, String key, String hash, Integer servings, Integer minutes) {
        jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES (?,?,?,?,?)",
                id, owner == null ? "SYSTEM_HOWTOCOOK" : "USER_CUSTOM", key, owner, owner == null ? "PUBLIC" : "PRIVATE");
        jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES (?,?,'u2',?,'# 测试原文','SUCCESS')", id, id, hash);
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,servings,estimated_minutes,additional_content) VALUES (?,?,?,?,?,?,?,'原始补充')", id, id, id, "菜" + id, category, servings, minutes);
    }
    private void ingredient(long recipe, int order, Long ingredientId, String min, String max, String code, String label, boolean optional) {
        jdbc.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,raw_line,amount_min,amount_max,unit_code,unit_label,optional,sort_order) VALUES (?,?,'原材料','原材料原量',?,?,?,?,?,?)",
                recipe, ingredientId, min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), code, label, optional, order);
    }
    private static RecipeEligibilityFilter filter(String query, List<String> categories, Integer minutes,
            boolean unknown, List<String> tools, List<String> exclusions) {
        return new RecipeEligibilityFilter(query, categories, minutes, unknown, tools, exclusions, 1L);
    }
    private static MealCandidateRequest request(Mode mode, RecipeEligibilityFilter filter, Long seed, int dish, int soup) {
        return new MealCandidateRequest(1, mode, filter, List.of(new RoleRequest(MealRole.DISH, dish), new RoleRequest(MealRole.SOUP, soup)), seed);
    }
    private static List<Long> ids(List<MealRecipeFacts> values) { return values.stream().map(MealRecipeFacts::recipeId).toList(); }
    private static RecipeSearchResult rank(long id, int rank) { return new RecipeSearchResult(id, rank, rank, null, 0.01, List.of(), List.of("LEXICAL")); }
}
