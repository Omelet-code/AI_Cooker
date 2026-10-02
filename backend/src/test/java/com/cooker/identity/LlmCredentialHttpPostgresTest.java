package com.cooker.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cooker.common.ApiException;
import com.cooker.identity.entity.LlmCredentialEntity;
import com.cooker.identity.mapper.LlmCredentialMapper;
import com.cooker.identity.service.LlmCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 独立 PostgreSQL、真实 HTTP、身份 Cookie/CSRF 和凭证密文边界测试。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class LlmCredentialHttpPostgresTest {
    private static final String TEST_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String FAKE_KEY = "fake-T8-platform-key-for-tests-never-valid";

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t8_http_test").withUsername("root").withPassword("test-only");

    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("cooker.identity.llm-credential.master-key", () -> TEST_MASTER_KEY);
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LlmCredentialMapper mapper;
    @Autowired LlmCredentialService credentials;

    @Test void 当前用户凭证接口密文隔离覆盖删除及错误输入均经真实HTTP(CapturedOutput output) throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(7);
        Browser anonymous = new Browser();
        assertThat(anonymous.get("/users/me/llm-credential/status").statusCode()).isEqualTo(401);
        assertThat(anonymous.putWithoutCsrf("/users/me/llm-credential", body(FAKE_KEY)).statusCode()).isEqualTo(403);

        Browser alice = new Browser(); Browser bob = new Browser();
        String aliceId = alice.registerAndLogin("t8_alice", "test-password-alice");
        String bobId = bob.registerAndLogin("t8_bob", "test-password-bob");
        assertThat(data(alice.get("/users/me/llm-credential/status"), 200).path("configured").asBoolean()).isFalse();
        assertThat(data(bob.get("/users/me/llm-credential/status"), 200).path("configured").asBoolean()).isFalse();

        assertInvalid(alice.put("/users/me/llm-credential", "{\"apiKey\":\"\"}"), 400, "");
        assertInvalid(alice.put("/users/me/llm-credential", "{\"apiKey\":\"   \"}"), 400, "   ");
        assertInvalid(alice.put("/users/me/llm-credential", "{\"apiKey\":42}"), 400, "42");
        assertInvalid(alice.put("/users/me/llm-credential", "{\"apiKey\":null}"), 400, "");
        assertInvalid(alice.put("/users/me/llm-credential", "{}"), 400, "");
        assertInvalid(alice.put("/users/me/llm-credential", body("oversize-marker-".repeat(300))), 400,
                "oversize-marker");
        HttpResponse<String> savedResponse = alice.put("/users/me/llm-credential", body(FAKE_KEY));
        JsonNode savedStatus = data(savedResponse, 200);
        assertThat(savedStatus.path("configured").asBoolean()).isTrue();
        assertThat(savedResponse.body()).doesNotContain(FAKE_KEY, "ciphertext", "iv", "algorithmVersion", "keyVersion");
        assertThat(savedResponse.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
        long aliceUserId = Long.parseLong(aliceId);
        long bobUserId = Long.parseLong(bobId);
        LlmCredentialEntity first = mapper.findByUserId(aliceUserId);
        assertThat(first).isNotNull();
        assertThat(first.ciphertext).isNotEqualTo(FAKE_KEY.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(first.ciphertext, StandardCharsets.UTF_8)).doesNotContain(FAKE_KEY);
        assertThat(first.iv).hasSize(12);
        assertThat(first.algorithmVersion).isEqualTo(1);
        assertThat(first.keyVersion).isEqualTo(1);
        assertThat(first.toString()).doesNotContain(FAKE_KEY);
        assertThat(inContext(aliceUserId, credentials::decryptCurrentUserCredential)).isEqualTo(FAKE_KEY);
        assertThatThrownBy(() -> inContext(bobUserId, credentials::decryptCurrentUserCredential))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(data(bob.get("/users/me/llm-credential/status"), 200).path("configured").asBoolean()).isFalse();

        HttpResponse<String> overwrite = alice.put("/users/me/llm-credential", body(FAKE_KEY));
        assertThat(data(overwrite, 200).path("configured").asBoolean()).isTrue();
        LlmCredentialEntity second = mapper.findByUserId(aliceUserId);
        assertThat(second.iv).isNotEqualTo(first.iv);
        assertThat(second.ciphertext).isNotEqualTo(first.ciphertext);
        assertThat(inContext(aliceUserId, credentials::decryptCurrentUserCredential)).isEqualTo(FAKE_KEY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_llm_credential WHERE user_id=?", Integer.class,
                aliceUserId)).isEqualTo(1);

        // 数据库若被篡改，认证标签阻止内部调用取得错误明文；异常不包含原凭证。
        byte[] corrupted = second.ciphertext.clone();
        corrupted[0] ^= 1;
        jdbc.update("UPDATE user_llm_credential SET ciphertext=? WHERE user_id=?", corrupted, aliceUserId);
        assertThatThrownBy(() -> inContext(aliceUserId, credentials::decryptCurrentUserCredential))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException api = (ApiException) error;
                    assertThat(api.code()).isEqualTo("INTERNAL_ERROR");
                    assertThat(api.getMessage()).doesNotContain(FAKE_KEY);
                    assertThat(api.getCause()).isNull();
                });
        jdbc.update("UPDATE user_llm_credential SET ciphertext=?,algorithm_version=99 WHERE user_id=?",
                second.ciphertext, aliceUserId);
        assertThatThrownBy(() -> inContext(aliceUserId, credentials::decryptCurrentUserCredential))
                .isInstanceOf(ApiException.class);
        jdbc.update("UPDATE user_llm_credential SET algorithm_version=1,key_version=99 WHERE user_id=?", aliceUserId);
        assertThatThrownBy(() -> inContext(aliceUserId, credentials::decryptCurrentUserCredential))
                .isInstanceOf(ApiException.class);
        jdbc.update("UPDATE user_llm_credential SET key_version=1 WHERE user_id=?", aliceUserId);

        assertThat(data(alice.delete("/users/me/llm-credential"), 200).path("configured").asBoolean()).isFalse();
        assertThat(data(alice.delete("/users/me/llm-credential"), 200).path("configured").asBoolean()).isFalse();
        assertThat(data(alice.get("/users/me/llm-credential/status"), 200).path("configured").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_llm_credential WHERE user_id=?", Integer.class,
                aliceUserId)).isZero();
        assertThat(output.getOut() + output.getErr()).doesNotContain(FAKE_KEY, "oversize-marker");
        assertThat(Arrays.equals(first.iv, second.iv)).isFalse();
    }

    private void assertInvalid(HttpResponse<String> response, int expectedStatus, String marker) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        if (expectedStatus == 200) return;
        JsonNode payload = json.readTree(response.body());
        assertThat(payload.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(payload.path("message").asText()).isEqualTo("请求参数不合法");
        if (!marker.isEmpty()) assertThat(response.body()).doesNotContain(marker);
        assertThat(response.body()).doesNotContain(FAKE_KEY, "HttpMessageNotReadableException");
    }

    private static String body(String key) throws Exception {
        // 单元集成测试只生成固定虚构字符串，不读取平台或本机凭证。
        return "{\"apiKey\":\"" + key + "\"}";
    }

    private <T> T inContext(long userId, Callable<T> work) throws Exception {
        var old = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);
        try { return work.call(); }
        finally { SecurityContextHolder.setContext(old); }
    }

    private JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode payload = json.readTree(response.body());
        assertThat(payload.path("code").asText()).isEqualTo("OK");
        return payload.path("data");
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String csrf;

        String registerAndLogin(String username, String password) throws Exception {
            String authBody = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
            HttpResponse<String> register = post("/auth/register", authBody);
            assertThat(register.statusCode()).isEqualTo(201);
            String id = json.readTree(register.body()).at("/data/id").asText();
            assertThat(post("/auth/login", authBody).statusCode()).isEqualTo(200);
            refreshCsrf();
            return id;
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> put(String path, String body) throws Exception { return post(path, body, "PUT"); }
        HttpResponse<String> putWithoutCsrf(String path, String body) throws Exception {
            return send(path, body, "PUT", null);
        }
        HttpResponse<String> delete(String path) throws Exception { return post(path, "", "DELETE"); }

        private HttpResponse<String> post(String path, String body) throws Exception { return post(path, body, "POST"); }
        private HttpResponse<String> post(String path, String body, String method) throws Exception {
            refreshCsrf();
            return send(path, body, method, csrf);
        }
        private HttpResponse<String> send(String path, String body, String method, String token) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
            if (token != null) request.header("X-XSRF-TOKEN", token);
            if (!method.equals("DELETE")) request.header("Content-Type", "application/json");
            HttpRequest.BodyPublisher publisher = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
        private void refreshCsrf() throws Exception {
            JsonNode payload = json.readTree(get("/auth/csrf").body());
            csrf = payload.at("/data/token").asText();
        }
        private URI uri(String path) { return URI.create("http://localhost:" + port + "/api" + path); }
    }
}
