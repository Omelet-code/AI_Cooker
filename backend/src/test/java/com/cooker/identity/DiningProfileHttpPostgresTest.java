package com.cooker.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
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

/** 真HTTP、Cookie/CSRF与隔离PG；不连接开发数据库，不调用外部模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DiningProfileHttpPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_u1_http").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("cooker.identity.jwt-secret", () -> "test-only-secret-for-u1-tests-0123456789");
        r.add("cooker.identity.llm-credential.master-key", () -> "");
        r.add("cooker.retrieval.index.api-key", () -> "");
        r.add("cooker.t6.enabled", () -> "false");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test void 保存修改清空重复写与再登录回读且两账号隔离() throws Exception {
        Browser a = account();
        Browser b = account();
        long rows = count();
        value(a.get("/users/me/profile"), null);
        value(a.get("/users/me/profile"), null);
        value(b.get("/users/me/profile"), null);
        assertThat(count()).isEqualTo(rows);
        value(a.put("{\"defaultDiningCount\":2}"), 2);
        value(a.put("{\"defaultDiningCount\":2}"), 2);
        assertThat(count()).isEqualTo(rows + 1);
        long id = a.userId;
        var created = jdbc.queryForObject("SELECT created_at FROM user_profile WHERE user_id=?", java.time.OffsetDateTime.class, id);
        value(a.put("{\"defaultDiningCount\":3}"), 3);
        assertThat(jdbc.queryForObject("SELECT created_at FROM user_profile WHERE user_id=?", java.time.OffsetDateTime.class, id)).isEqualTo(created);
        value(b.get("/users/me/profile"), null);
        value(b.put("{\"defaultDiningCount\":4}"), 4);
        a.csrf();
        assertThat(a.send("POST", "/auth/logout", "{}").statusCode()).isEqualTo(200);
        a.login();
        value(a.get("/users/me/profile"), 3);
        value(a.put("{\"defaultDiningCount\":null}"), null);
        value(a.put("{\"defaultDiningCount\":null}"), null);
        value(b.get("/users/me/profile"), 4);
        assertThat(count()).isEqualTo(rows + 2);
        // 原me响应仍只有id与username，档案没有挤进旧登录契约。
        var oldMe = json.readTree(a.get("/users/me").body()).path("data");
        assertThat(oldMe.size()).isEqualTo(2);
        assertThat(oldMe.path("id").asText()).isEqualTo(Long.toString(id));
        assertThat(oldMe.path("username").asText()).isEqualTo(a.username);
    }

    @Test void 严格JSON与整数边界非法输入不改变数据() throws Exception {
        Browser a = account();
        value(a.put("{\"defaultDiningCount\":1}"), 1);
        for (String body : new String[] {"{}", "", "null", "[]", "2", "{", "{\"defaultDiningCount\":\"2\"}",
                "{\"defaultDiningCount\":2.0}", "{\"defaultDiningCount\":2.5}", "{\"defaultDiningCount\":true}",
                "{\"defaultDiningCount\":{}}", "{\"defaultDiningCount\":[]}", "{\"defaultDiningCount\":0}",
                "{\"defaultDiningCount\":-1}", "{\"defaultDiningCount\":2147483648}",
                "{\"defaultDiningCount\":-2147483649}", "{\"defaultDiningCount\":999999999999999999999999}",
                "{\"defaultDiningCount\":2,\"userId\":999}", "{\"defaultDiningCount\":2,\"unknown\":null}"}) {
            var before = jdbc.queryForMap("SELECT * FROM user_profile WHERE user_id=?", a.userId);
            error(a.put(body), 400, "VALIDATION_ERROR");
            assertThat(jdbc.queryForMap("SELECT * FROM user_profile WHERE user_id=?", a.userId)).isEqualTo(before);
        }
        value(a.put("{\"defaultDiningCount\":2147483647}"), Integer.MAX_VALUE);
        value(a.get("/users/me/profile"), Integer.MAX_VALUE);
    }

    @Test void 未登录无效身份失效账号与CSRF过滤顺序() throws Exception {
        Browser anonymous = new Browser();
        error(anonymous.get("/users/me/profile"), 401, "AUTH_REQUIRED");
        error(anonymous.send("PUT", "/users/me/profile", "{\"defaultDiningCount\":2}"), 403, "CSRF_INVALID");
        anonymous.csrf();
        error(anonymous.put("{\"defaultDiningCount\":2}"), 401, "AUTH_REQUIRED");
        Browser a = account();
        String validCsrf = a.csrf;
        a.csrf = "invalid";
        error(a.send("PUT", "/users/me/profile", "{\"defaultDiningCount\":2}"), 403, "CSRF_INVALID");
        a.csrf = null;
        error(a.send("PUT", "/users/me/profile", "{\"defaultDiningCount\":2}"), 403, "CSRF_INVALID");
        a.csrf = validCsrf;
        value(a.put("{\"defaultDiningCount\":2}"), 2);
        var invalid = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/users/me/profile"))
                .header("Cookie", "COOKER_ACCESS=invalid-jwt").GET().build(), HttpResponse.BodyHandlers.ofString());
        error(invalid, 401, "ACCESS_TOKEN_EXPIRED");
        jdbc.update("DELETE FROM app_user WHERE id=?", a.userId);
        error(a.get("/users/me/profile"), 401, "AUTH_REQUIRED");
        error(a.put("{\"defaultDiningCount\":3}"), 401, "AUTH_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile WHERE user_id=?", Integer.class, a.userId)).isZero();
        // 无此HTTP入口用实际路由注册证明；全局未知路径错误映射不是U1契约。
        var routes = context.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var profilePaths = routes.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(com.cooker.identity.controller.DiningProfileController.class))
                .flatMap(entry -> entry.getKey().getPatternValues().stream()).toList();
        assertThat(profilePaths).containsOnly("/users/me/profile");
    }

    private Browser account() throws Exception {
        Browser b = new Browser();
        b.username = "u1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        b.csrf();
        assertThat(b.send("POST", "/auth/register", b.credentials()).statusCode()).isEqualTo(201);
        b.login();
        return b;
    }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM user_profile", Long.class); }
    private void value(HttpResponse<String> response, Integer expected) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("message").asText()).isEqualTo("成功");
        assertThat(body.path("data").size()).isEqualTo(1);
        var node = body.at("/data/defaultDiningCount");
        if (expected == null) assertThat(node.isNull()).isTrue();
        else { assertThat(node.isIntegralNumber()).isTrue(); assertThat(node.intValue()).isEqualTo(expected); }
    }
    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("data").isNull()).isTrue();
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + "/api" + path); }
    private class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf, username;
        long userId;
        String credentials() { return "{\"username\":\"" + username + "\",\"password\":\"u1-test-password-123\"}"; }
        void csrf() throws Exception { csrf = json.readTree(get("/auth/csrf").body()).at("/data/token").asText(); }
        void login() throws Exception {
            csrf();
            var response = send("POST", "/auth/login", credentials());
            assertThat(response.statusCode()).isEqualTo(200);
            var data = json.readTree(response.body()).path("data");
            assertThat(data.size()).isEqualTo(2);
            assertThat(data.path("username").asText()).isEqualTo(username);
            userId = Long.parseLong(data.path("id").asText());
            csrf();
        }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> put(String body) throws Exception {
            // 沿用既有真实HTTP测试策略，写请求前取得与当前Cookie一致的CSRF。
            csrf();
            return send("PUT", "/users/me/profile", body);
        }
        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
