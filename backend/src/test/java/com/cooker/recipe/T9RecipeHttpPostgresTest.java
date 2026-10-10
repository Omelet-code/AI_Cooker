package com.cooker.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** 独立库验证 V5、真实 HTTP、跨用户可见性和数据库并发幂等。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class T9RecipeHttpPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t9_test").withUsername("root").withPassword("test-only");

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void 列表详情权限与并发收藏全部经真实HTTP() throws Exception {
        // 整合T13/T14与U1后完整生产迁移为V1～V12，按数字顺序精确核验。
        assertThat(jdbc.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        Browser a = new Browser(); Browser b = new Browser(); Browser anonymous = new Browser();
        assertThat(anonymous.get("/recipes").statusCode()).isEqualTo(401);
        String aId = a.registerAndLogin("t9_alice", "test-password-alice");
        String bId = b.registerAndLogin("t9_bob", "test-password-bob");
        seed(901, "番茄%炒蛋", null, true);
        seed(902, "番茄_汤", null, true);
        seed(903, "空事实菜", null, true);
        seed(904, "A私房菜", Long.valueOf(aId), false);
        seed(905, "B私房菜", Long.valueOf(bId), false);
        seed(906, "番茄\\饭", null, true);
        children();

        JsonNode list = data(a.get("/recipes?page=1&size=2"), 200);
        assertThat(list.path("total").asLong()).isEqualTo(5);
        assertThat(list.path("items").size()).isEqualTo(2);
        assertThat(list.at("/items/0/id").asText()).isEqualTo("901");
        assertThat(list.at("/items/1/id").asText()).isEqualTo("902");
        assertThat(data(a.get("/recipes?page=2&size=2"), 200).at("/items/0/id").asText()).isEqualTo("903");
        assertThat(data(a.get("/recipes?page=3&size=2"), 200).at("/items/0/id").asText()).isEqualTo("906");
        assertThat(data(b.get("/recipes?page=3&size=2"), 200).at("/items/0/id").asText()).isEqualTo("906");
        assertThat(data(b.get("/recipes?page=2&size=2"), 200).at("/items/1/id").asText()).isEqualTo("905");
        assertThat(data(a.get("/recipes?page=99&size=2"), 200).path("items").size()).isZero();
        assertThat(data(a.get("/recipes?keyword=" + enc("%")), 200).path("total").asLong()).isEqualTo(1);
        assertThat(data(a.get("/recipes?keyword=" + enc("_")), 200).at("/items/0/id").asText()).isEqualTo("902");
        assertThat(data(a.get("/recipes?keyword=" + enc("\\")), 200).at("/items/0/id").asText()).isEqualTo("906");
        assertThat(data(a.get("/recipes?keyword=" + enc("番茄")), 200).path("total").asLong()).isEqualTo(3);
        assertThat(error(a.get("/recipes?page=0"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/recipes?size=51"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/recipes?page=bad"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/recipes?keyword=" + enc("x".repeat(101))), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/recipes/not-a-number"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/recipes/0"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(error(a.get("/recipes/9223372036854775808"), 400)).isEqualTo("VALIDATION_ERROR");

        JsonNode detail = data(a.get("/recipes/901"), 200);
        assertThat(detail.at("/recipe/id").asText()).isEqualTo("901");
        assertThat(detail.at("/ingredients/0/name").asText()).isEqualTo("番茄");
        assertThat(detail.at("/ingredients/0/amountMin").asText()).isEqualTo("1.50");
        assertThat(detail.at("/ingredients/1/amountMin").isNull()).isTrue();
        assertThat(detail.at("/steps/0/stepNo").asInt()).isEqualTo(1);
        assertThat(detail.at("/tools/0/name").asText()).isEqualTo("A锅");
        JsonNode unknown = data(a.get("/recipes/903"), 200);
        assertThat(unknown.at("/recipe/summary").isNull()).isTrue();
        assertThat(unknown.path("servings").isNull()).isTrue();
        assertThat(unknown.path("ingredients").size()).isZero();
        assertThat(unknown.path("steps").size()).isZero();

        assertThat(data(a.get("/recipes/904"), 200).at("/recipe/name").asText()).isEqualTo("A私房菜");
        assertThat(error(a.get("/recipes/905"), 404)).isEqualTo("RECIPE_NOT_FOUND");
        assertThat(error(b.get("/recipes/904"), 404)).isEqualTo("RECIPE_NOT_FOUND");
        b.csrf();
        assertThat(error(b.post("/recipes/904/favorites"), 404)).isEqualTo("RECIPE_NOT_FOUND");
        assertThat(error(a.get("/recipes/99999"), 404)).isEqualTo("RECIPE_NOT_FOUND");
        assertThat(error(anonymous.postJson("/recipes/901/favorites", "{}"), 403)).isEqualTo("CSRF_INVALID");

        a.csrf();
        assertThat(data(a.post("/recipes/901/favorites"), 200).path("isFavorite").asBoolean()).isTrue();
        assertThat(data(a.post("/recipes/901/favorites"), 200).path("isFavorite").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorite_recipe WHERE user_id=? AND recipe_id=901", Integer.class, Long.valueOf(aId))).isEqualTo(1);
        Browser a2 = new Browser(); Browser a3 = new Browser();
        a2.loginExisting("t9_alice", "test-password-alice");
        a3.loginExisting("t9_alice", "test-password-alice");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> a2.post("/recipes/902/favorites"));
            var second = pool.submit(() -> a3.post("/recipes/902/favorites"));
            assertThat(first.get().statusCode()).isEqualTo(200);
            assertThat(second.get().statusCode()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorite_recipe WHERE user_id=? AND recipe_id=902", Integer.class, Long.valueOf(aId))).isEqualTo(1);
        data(a.post("/recipes/903/favorites"), 200);
        jdbc.update("UPDATE favorite_recipe SET created_at='2026-01-01T00:00:00Z' WHERE user_id=?", Long.valueOf(aId));
        JsonNode favorites = data(a.get("/favorites/recipes?page=1&size=2"), 200);
        assertThat(favorites.path("total").asLong()).isEqualTo(3);
        assertThat(favorites.at("/items/0/id").asText()).isEqualTo("903");
        assertThat(favorites.at("/items/1/id").asText()).isEqualTo("902");
        assertThat(favorites.at("/items/0/isFavorite").asBoolean()).isTrue();
        assertThat(data(a.get("/favorites/recipes?page=2&size=2"), 200).at("/items/0/id").asText()).isEqualTo("901");
        assertThat(data(b.get("/favorites/recipes"), 200).path("total").asLong()).isZero();
        assertThat(data(a.get("/recipes/901"), 200).at("/recipe/isFavorite").asBoolean()).isTrue();
        assertThat(data(a.delete("/recipes/901/favorites"), 200).path("isFavorite").asBoolean()).isFalse();
        assertThat(data(a.delete("/recipes/901/favorites"), 200).path("isFavorite").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorite_recipe WHERE user_id=? AND recipe_id=901", Integer.class, Long.valueOf(aId))).isZero();
        assertThat(data(a.get("/recipes/901"), 200).at("/recipe/isFavorite").asBoolean()).isFalse();
        b.csrf();
        assertThat(data(b.delete("/recipes/901/favorites"), 200).path("isFavorite").asBoolean()).isFalse();
        assertThat(error(a.get("/favorites/recipes?size=0"), 400)).isEqualTo("VALIDATION_ERROR");
    }

    private void seed(int id, String name, Long owner, boolean system) {
        jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES (?,?,?,?,?)",
                (long) id, system ? "SYSTEM_HOWTOCOOK" : "USER_CUSTOM", "t9-" + id, owner, system ? "PUBLIC" : "PRIVATE");
        jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES (?,?,?,?,?,?)",
                (long) id, (long) id, "t9", "a".repeat(64), "# " + name, "SUCCESS");
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES (?,?,?,?,?)",
                (long) id, (long) id, (long) id, name, "vegetable");
    }

    private void children() {
        jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES (7001,'番茄','REVIEWED')");
        jdbc.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,raw_line,amount_min,amount_max,unit_label,optional,sort_order) VALUES (901,7001,'西红柿','西红柿 1.50 克',1.50,2.00,'克',false,1)");
        jdbc.update("INSERT INTO recipe_ingredient(recipe_id,raw_name,raw_line,optional,sort_order) VALUES (901,'盐','盐 少许',true,2)");
        jdbc.update("INSERT INTO recipe_step(recipe_id,step_no,content) VALUES (901,2,'煮熟')");
        jdbc.update("INSERT INTO recipe_step(recipe_id,step_no,content) VALUES (901,1,'备料')");
        jdbc.update("INSERT INTO cooking_tool(id,canonical_name) VALUES (8001,'B锅'),(8002,'A锅')");
        jdbc.update("INSERT INTO recipe_tool(recipe_id,tool_id,optional) VALUES (901,8001,false),(901,8002,true)");
    }

    private JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        return body.path("data");
    }
    private String error(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        return json.readTree(response.body()).path("code").asText();
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String csrf;
        String registerAndLogin(String username, String password) throws Exception {
            csrf();
            String body = json.writeValueAsString(java.util.Map.of("username", username, "password", password));
            String id = data(postJson("/auth/register", body), 201).path("id").asText();
            data(postJson("/auth/login", body), 200);
            csrf();
            return id;
        }
        void loginExisting(String username, String password) throws Exception {
            csrf();
            data(postJson("/auth/login", json.writeValueAsString(java.util.Map.of("username", username, "password", password))), 200);
            csrf();
        }
        void csrf() throws Exception { csrf = data(get("/auth/csrf"), 200).path("token").asText(); }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path) throws Exception { csrf(); return postJson(path, "{}"); }
        HttpResponse<String> postJson(String path, String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> delete(String path) throws Exception {
            csrf();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return client.send(request.DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }
        private URI uri(String path) { return URI.create("http://localhost:" + port + "/api" + path); }
    }
}
