package com.cooker.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cooker.common.ApiException;
import com.cooker.identity.service.AuthService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真 HTTP 与独立 PostgreSQL：Cookie、CSRF、持久化和用户隔离一起验证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class IdentityHttpPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t7_test").withUsername("root").withPassword("test-only");

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;

    @Test void 非法JSON错误字段类型与缺少请求体返回400且不回显输入() throws Exception {
        Browser browser = new Browser();
        browser.csrf();
        assertBadInput(browser.post("/auth/login", "{"));
        assertBadInput(browser.post("/auth/login", "{\"username\":\"alice_1\",\"password\":\"example-password\",\"rememberMe\":\"bad-type\"}"));
        assertBadInput(browser.postWithoutBody("/auth/login"));
        // 参数错误不能变成服务器内部故障，也不能把原始请求体、密码或解析器信息送回客户端。
    }

    private void assertBadInput(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        var payload = json.readTree(response.body());
        assertThat(payload.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(payload.path("message").asText()).isEqualTo("请求参数不合法");
        assertThat(payload.path("data").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("example-password", "bad-type", "HttpMessageNotReadableException", "INTERNAL_ERROR");
    }

    @Test void 注册登录续期与跨用户边界() throws Exception {
        Browser a = new Browser();
        Browser b = new Browser();
        assertThat(a.get("/users/me").statusCode()).isEqualTo(401);
        HttpResponse<String> noCsrf = a.post("/auth/register", "{\"username\":\"  Alice_1  \",\"password\":\"long-password-123\"}");
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(json.readTree(noCsrf.body()).path("code").asText()).isEqualTo("CSRF_INVALID");
        a.csrf();
        HttpResponse<String> registration = a.post("/auth/register", "{\"username\":\"  Alice_1  \",\"password\":\"long-password-123\"}");
        assertThat(registration.statusCode()).isEqualTo(201);
        assertThat(json.readTree(registration.body()).at("/data/username").asText()).isEqualTo("alice_1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username='alice_1'", Integer.class)).isEqualTo(1);
        assertThat(a.post("/auth/register", "{\"username\":\"ALICE_1\",\"password\":\"long-password-123\"}").statusCode()).isEqualTo(409);
        assertThat(a.post("/auth/login", "{\"username\":\"alice_1\",\"password\":\"wrong\"}").statusCode()).isEqualTo(401);
        HttpResponse<String> login = a.post("/auth/login", "{\"username\":\"alice_1\",\"password\":\"long-password-123\",\"rememberMe\":true}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().allValues("set-cookie")).anySatisfy(v -> assertThat(v).contains("COOKER_ACCESS", "HttpOnly", "Path=/api"));
        assertThat(login.headers().allValues("set-cookie")).anySatisfy(v -> assertThat(v).contains("COOKER_REFRESH", "HttpOnly", "Path=/api/auth", "Max-Age"));
        String oldRefresh = a.cookie("COOKER_REFRESH");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM user_refresh_token LIMIT 1", String.class)).doesNotContain(oldRefresh);
        assertThat(a.get("/users/me").statusCode()).isEqualTo(200);
        assertThat(a.post("/auth/refresh", "{}").statusCode()).isEqualTo(403);
        a.csrf();
        assertThat(a.post("/auth/refresh", "{}").statusCode()).isEqualTo(200);
        assertThat(a.replayOld(oldRefresh).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_refresh_token WHERE revoked_at IS NOT NULL", Integer.class)).isEqualTo(1);
        b.csrf();
        assertThat(b.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(b.post("/auth/register", "{\"username\":\"bob_2\",\"password\":\"long-password-456\"}").statusCode()).isEqualTo(201);
        HttpResponse<String> bLogin = b.post("/auth/login", "{\"username\":\"bob_2\",\"password\":\"long-password-456\"}");
        assertThat(bLogin.statusCode()).isEqualTo(200);
        assertThat(bLogin.headers().allValues("set-cookie").stream()
                .filter(v -> v.startsWith("COOKER_REFRESH="))).allSatisfy(v -> assertThat(v).doesNotContain("Max-Age", "Expires"));
        assertThat(b.cookie("COOKER_REFRESH")).isNotBlank();
        String bobRefresh = b.cookie("COOKER_REFRESH");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return auth.refresh(bobRefresh); });
            var second = executor.submit(() -> { start.await(); return auth.refresh(bobRefresh); });
            start.countDown();
            var outcomes = java.util.List.of(first, second);
            int success = 0, conflict = 0;
            AuthService.Session winner = null;
            for (var future : outcomes) {
                try { winner = future.get(); success++; }
                catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof ApiException api && "REFRESH_CONFLICT".equals(api.code())) conflict++;
                    else throw e;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
            assertThat(auth.refresh(winner.refreshToken()).profile().username()).isEqualTo("bob_2");
        }
        assertThat(b.get("/users/me").body()).contains("bob_2").doesNotContain("alice_1");
        assertThat(a.get("/users/me").body()).contains("alice_1").doesNotContain("bob_2");
        a.csrf();
        assertThat(a.post("/auth/logout", "{}").statusCode()).isEqualTo(200);
        a.csrf();
        assertThat(a.post("/auth/refresh", "{}").statusCode()).isEqualTo(401);
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        private String csrf;
        void csrf() throws Exception {
            HttpResponse<String> result = get("/auth/csrf");
            assertThat(result.statusCode()).isEqualTo(200);
            csrf = json.readTree(result.body()).at("/data/token").asText();
        }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> postWithoutBody(String path) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return client.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
        String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream().filter(c -> name.equals(c.getName()))
                    .findFirst().orElseThrow().getValue();
        }
        HttpResponse<String> replayOld(String raw) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/auth/refresh"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", "COOKER_REFRESH=" + raw + "; XSRF-TOKEN=" + cookie("XSRF-TOKEN"))
                    .header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        }
        private URI uri(String path) { return URI.create("http://localhost:" + port + "/api" + path); }
    }
}
