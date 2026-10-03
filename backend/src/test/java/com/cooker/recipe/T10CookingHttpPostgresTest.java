package com.cooker.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 独立 PostgreSQL 和真实 HTTP 验证 V6、幂等并发、历史汇总与权限。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class T10CookingHttpPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t10_test").withUsername("root").withPassword("test-only");

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void 完成事件幂等并发与历史汇总均经真实HTTP() throws Exception {
        // 整合T13/T14与U1后完整生产迁移为V1～V10，按数字顺序精确核验。
        assertThat(jdbc.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        Browser a = new Browser(); Browser b = new Browser(); Browser anon = new Browser();
        assertThat(anon.get("/users/me/cooked-recipes").statusCode()).isEqualTo(401);
        assertThat(error(anon.postRaw(body("1001", UUID.randomUUID().toString(), null), false), 403)).isEqualTo("CSRF_INVALID");
        String aId = a.registerAndLogin("t10_alice", "test-password-alice");
        b.registerAndLogin("t10_bob", "test-password-bob");
        seed(1001, null, true); seed(1002, null, true); seed(1003, Long.valueOf(aId), false);
        seed(1004, userId("t10_bob"), false);

        String key = UUID.randomUUID().toString();
        JsonNode first = data(a.post(body("1001", key, null)), 201);
        assertThat(first.path("recipeId").asText()).isEqualTo("1001");
        assertThat(first.path("rating").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(first.path("cookedAt").asText()).getOffset().getTotalSeconds()).isZero();
        assertThat(data(a.get("/users/me/cooked-recipes"), 200)
                .at("/items/0/latestRating").isNull()).isTrue();
        assertThat(data(a.post(body("1001", key, null)), 200)).isEqualTo(first);
        assertThat(error(a.post(body("1002", key, null)), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(error(a.post(body("1001", key, 5)), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
        String ratedKey = UUID.randomUUID().toString();
        JsonNode rated = data(a.post(body("1001", ratedKey, 5)), 201);
        assertThat(error(a.post(body("1001", ratedKey, null)), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(data(a.post(body("1001", ratedKey, 5)), 200)).isEqualTo(rated);
        assertThat(error(a.post(body("1001", UUID.randomUUID().toString(), 0)), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.post(body("1001", UUID.randomUUID().toString(), 6)), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.post("{\"recipeId\":\"1001\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"rating\":1.5}"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.post(body("0", UUID.randomUUID().toString(), 1)), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.post(body("1001", "bad-key", 1)), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.post(body("99999", UUID.randomUUID().toString(), 1)), 404)).isEqualTo("RECIPE_NOT_FOUND");
        assertThat(error(a.post(body("1004", UUID.randomUUID().toString(), 1)), 404)).isEqualTo("RECIPE_NOT_FOUND");
        assertThat(error(b.post(body("1003", UUID.randomUUID().toString(), 1)), 404)).isEqualTo("RECIPE_NOT_FOUND");
        assertThat(data(a.post(body("1003", UUID.randomUUID().toString(), 1)), 201).path("rating").asInt()).isEqualTo(1);
        assertThat(data(b.post(body("1001", key, 4)), 201).path("rating").asInt()).isEqualTo(4);
        assertThat(data(b.get("/users/me/cooked-recipes"), 200).path("total").asLong()).isEqualTo(1);
        assertThat(error(a.get("/users/me/cooked-recipes?page=0"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/users/me/cooked-recipes?size=51"), 400)).isEqualTo("VALIDATION_ERROR");

        // 两个独立登录会话并发提交同一键，数据库只能插入一次。
        Browser a2 = new Browser(); Browser a3 = new Browser();
        a2.loginExisting("t10_alice", "test-password-alice");
        a3.loginExisting("t10_alice", "test-password-alice");
        String concurrentKey = UUID.randomUUID().toString();
        String concurrentBody = body("1002", concurrentKey, 3);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> a2.post(concurrentBody));
            var two = pool.submit(() -> a3.post(concurrentBody));
            var r1 = one.get(); var r2 = two.get();
            assertThat(java.util.List.of(r1.statusCode(), r2.statusCode())).containsExactlyInAnyOrder(201, 200);
            assertThat(json.readTree(r1.body()).path("data")).isEqualTo(json.readTree(r2.body()).path("data"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_cook_record WHERE user_id=? AND idempotency_key=?::uuid",
                Integer.class, Long.valueOf(aId), concurrentKey)).isEqualTo(1);

        // 同一菜谱分别到 2、3、5、6 次；最新一次未评分时保留最近的非空评分。
        assertCount(a, "1001", 2, "FIRST_TRY", "初尝", 5);
        data(a.post(body("1001", UUID.randomUUID().toString(), null)), 201);
        assertCount(a, "1001", 3, "FAMILIAR", "熟悉", 5);
        data(a.post(body("1001", UUID.randomUUID().toString(), 2)), 201);
        data(a.post(body("1001", UUID.randomUUID().toString(), null)), 201);
        assertCount(a, "1001", 5, "FAMILIAR", "熟悉", 2);
        data(a.post(body("1001", UUID.randomUUID().toString(), null)), 201);
        assertCount(a, "1001", 6, "FREQUENT", "常做", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_cook_record WHERE user_id=? AND recipe_id=1001",
                Integer.class, Long.valueOf(aId))).isEqualTo(6);

        // 固定时间构造同一最新时间的稳定次序和旧评分的时间/ID 破平局。
        jdbc.update("UPDATE recipe_cook_record SET cooked_at='2026-01-01T00:00:00Z' WHERE user_id=? AND recipe_id=1001",
                Long.valueOf(aId));
        jdbc.update("UPDATE recipe_cook_record SET cooked_at='2026-01-01T00:00:00Z' WHERE user_id=? AND recipe_id=1002",
                Long.valueOf(aId));
        JsonNode page1 = data(a.get("/users/me/cooked-recipes?page=1&size=2"), 200);
        assertThat(page1.path("total").asLong()).isEqualTo(3);
        assertThat(page1.at("/items/0/recipe/id").asText()).isEqualTo("1003");
        assertThat(page1.at("/items/1/recipe/id").asText()).isEqualTo("1002");
        assertThat(data(a.get("/users/me/cooked-recipes?page=2&size=2"), 200)
                .at("/items/0/recipe/id").asText()).isEqualTo("1001");
        assertThat(page1.at("/items/1/recipe/isFavorite").asBoolean()).isFalse();
        assertThat(data(a.get("/users/me/cooked-recipes?page=99&size=2"), 200).path("items").size()).isZero();
    }

    private void assertCount(Browser browser, String recipeId, int count, String code, String label,
            Integer rating) throws Exception {
        JsonNode items = data(browser.get("/users/me/cooked-recipes"), 200).path("items");
        JsonNode card = null;
        for (JsonNode item : items) if (item.at("/recipe/id").asText().equals(recipeId)) card = item;
        assertThat(card).isNotNull();
        assertThat(card.path("cookCount").asInt()).isEqualTo(count);
        assertThat(card.path("familiarityCode").asText()).isEqualTo(code);
        assertThat(card.path("familiarityLabel").asText()).isEqualTo(label);
        assertThat(card.path("latestRating").asInt()).isEqualTo(rating);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
    private void seed(int id, Long owner, boolean system) {
        jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES (?,?,?,?,?)",
                (long) id, system ? "SYSTEM_HOWTOCOOK" : "USER_CUSTOM", "t10-" + id, owner, system ? "PUBLIC" : "PRIVATE");
        jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES (?,?,?,?,?,?)",
                (long) id, (long) id, "t10", "a".repeat(64), "# 测试菜谱", "SUCCESS");
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES (?,?,?,?,?)",
                (long) id, (long) id, (long) id, "测试菜谱" + id, "vegetable");
    }
    private String body(String recipeId, String key, Integer rating) {
        return "{\"recipeId\":\"" + recipeId + "\",\"idempotencyKey\":\"" + key
                + "\",\"rating\":" + (rating == null ? "null" : rating) + "}";
    }
    private JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        return body.path("data");
    }
    private String error(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body()).path("code").asText();
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String csrf;
        String registerAndLogin(String username, String password) throws Exception {
            csrf();
            String request = json.writeValueAsString(java.util.Map.of("username", username, "password", password));
            String id = data(postRaw(request, true, "/auth/register"), 201).path("id").asText();
            data(postRaw(request, true, "/auth/login"), 200);
            csrf();
            return id;
        }
        void loginExisting(String username, String password) throws Exception {
            csrf();
            data(postRaw(json.writeValueAsString(java.util.Map.of("username", username, "password", password)),
                    true, "/auth/login"), 200);
            csrf();
        }
        void csrf() throws Exception { csrf = data(get("/auth/csrf"), 200).path("token").asText(); }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String body) throws Exception { csrf(); return postRaw(body, true); }
        HttpResponse<String> postRaw(String body, boolean withCsrf) throws Exception {
            return postRaw(body, withCsrf, "/cooking-records");
        }
        HttpResponse<String> postRaw(String body, boolean withCsrf, String path) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (withCsrf && csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        private URI uri(String path) { return URI.create("http://localhost:" + port + "/api" + path); }
    }
}
