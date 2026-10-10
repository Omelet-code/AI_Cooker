package com.cooker.agent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.cooker.agent.dto.ModelChatRequest;
import com.cooker.agent.dto.ModelChatResponse;
import com.cooker.agent.dto.ModelMessage;
import com.cooker.agent.dto.ModelOutputMode;
import com.cooker.agent.service.ModelGatewayException;
import com.cooker.agent.service.impl.ModelGatewayServiceImpl;
import com.cooker.common.ApiException;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.identity.service.LlmCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** 通过本机 Mock HTTP 服务核对实际序列化、账户隔离和错误映射，不访问收费服务。 */
class ModelGatewayProtocolTest {
    private static final String KEY_A = "fake-key-user-a-t12";
    private static final String KEY_B = "fake-key-user-b-t12";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, String> keys = new ConcurrentHashMap<>();
    private final AtomicBoolean failDecryption = new AtomicBoolean();
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final LlmCredentialService credentials = mock(LlmCredentialService.class);
    private MockDeepSeekServer server;
    private ModelGatewayServiceImpl gateway;

    @BeforeEach
    void setUp() throws Exception {
        keys.put(11L, KEY_A);
        keys.put(22L, KEY_B);
        server = new MockDeepSeekServer();
        when(currentUser.requireUserId()).thenAnswer(invocation -> currentUserId());
        when(credentials.decryptCurrentUserCredential()).thenAnswer(invocation -> {
            if (failDecryption.get()) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "隐藏的解密错误");
            }
            String key = keys.get(currentUserId());
            if (key == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "LLM_CREDENTIAL_NOT_CONFIGURED", "未配置");
            }
            return key;
        });
        gateway = new ModelGatewayServiceImpl(currentUser, credentials,
                new DefaultDeepSeekChatClientFactory(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (server != null) {
            server.close();
        }
    }

    @Test
    void sendsFixedProtocolAndJsonModeWithCurrentUsersCredential() throws Exception {
        authenticateAs(11L);
        server.respondWith(MockResponse.ok(successBody("{\"accepted\":true}", "stop", true)));

        ModelChatResponse response = gateway.complete(jsonRequest("请只输出 JSON 对象"));

        assertEquals("{\"accepted\":true}", response.content());
        assertEquals("STOP", response.finishReason());
        assertEquals(4, response.usage().promptTokens());
        assertEquals(2, response.usage().completionTokens());
        ReceivedRequest sent = server.requests().getFirst();
        JsonNode body = json.readTree(sent.body());
        assertTrue(sent.path().equals("/chat/completions"));
        assertTrue(sent.authorization().equals("Bearer " + KEY_A));
        assertTrue("deepseek-flash".equals(body.path("model").asText()));
        assertTrue(body.path("max_tokens").asInt() == 2048);
        assertTrue("disabled".equals(body.path("thinking").path("type").asText()));
        assertTrue("json_object".equals(body.path("response_format").path("type").asText()));
        assertTrue(body.path("reasoning_effort").isMissingNode());
        assertTrue(body.path("stream").isBoolean() && !body.path("stream").asBoolean());
        assertEquals(1, server.requests().size());
    }

    @Test
    void isolatesInterleavedAndConcurrentUsersAndUsesRotatedCredentialOnNextCall() throws Exception {
        CountDownLatch bothArrived = new CountDownLatch(2);
        server.setBarrier(bothArrived);
        server.respondWith(MockResponse.ok(successBody("ok", "stop", true)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> {
                authenticateAs(11L);
                return gateway.complete(textRequest("CONCURRENT_USER_A_T12"));
            });
            var b = pool.submit(() -> {
                authenticateAs(22L);
                return gateway.complete(textRequest("CONCURRENT_USER_B_T12"));
            });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            server.setBarrier(null);
        }

        boolean matchedA = false;
        boolean matchedB = false;
        for (ReceivedRequest request : server.requests()) {
            JsonNode body = json.readTree(request.body());
            String content = body.path("messages").get(0).path("content").asText();
            matchedA |= content.contains("CONCURRENT_USER_A_T12") && request.authorization().equals("Bearer " + KEY_A);
            matchedB |= content.contains("CONCURRENT_USER_B_T12") && request.authorization().equals("Bearer " + KEY_B);
        }
        assertTrue(matchedA);
        assertTrue(matchedB);

        keys.put(11L, "rotated-fake-key-user-a-t12");
        authenticateAs(11L);
        gateway.complete(textRequest("ROTATED_CREDENTIAL_T12"));
        ReceivedRequest rotated = server.requests().getLast();
        assertTrue(rotated.authorization().equals("Bearer rotated-fake-key-user-a-t12"));
        assertEquals(3, server.requests().size());
    }

    @Test
    void rejectsUnauthenticatedMissingCredentialAndDecryptionFailureWithoutHttpCalls() {
        SecurityContextHolder.clearContext();
        ModelGatewayException unauthenticated = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(textRequest("AUTH_REQUIRED_MARKER")));
        assertEquals(ModelGatewayException.Reason.AUTHENTICATION_REQUIRED, unauthenticated.reason());

        authenticateAs(33L);
        ModelGatewayException missing = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(textRequest("MISSING_CREDENTIAL_MARKER")));
        assertEquals(ModelGatewayException.Reason.CREDENTIAL_NOT_CONFIGURED, missing.reason());

        authenticateAs(11L);
        failDecryption.set(true);
        ModelGatewayException failedDecrypt = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(textRequest("DECRYPTION_FAILURE_MARKER")));
        assertEquals(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE, failedDecrypt.reason());
        assertEquals(0, server.requests().size());
    }

    @Test
    void rejectsInvalidAndOversizedInputBeforeAuthenticationOrNetwork() {
        ModelGatewayException noMessages = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(new ModelChatRequest(List.of(), ModelOutputMode.TEXT)));
        assertEquals(ModelGatewayException.Reason.INVALID_INPUT, noMessages.reason());

        ModelGatewayException tooManyMessages = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(new ModelChatRequest(
                        java.util.Collections.nCopies(17, new ModelMessage(ModelMessage.Role.USER, "x")),
                        ModelOutputMode.TEXT)));
        assertEquals(ModelGatewayException.Reason.INVALID_INPUT, tooManyMessages.reason());

        String tooLong = "x".repeat(20_001);
        ModelGatewayException oversized = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(new ModelChatRequest(
                        List.of(new ModelMessage(ModelMessage.Role.USER, tooLong)), ModelOutputMode.TEXT)));
        assertEquals(ModelGatewayException.Reason.INVALID_INPUT, oversized.reason());

        ModelGatewayException jsonWithoutInstruction = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(new ModelChatRequest(
                        List.of(new ModelMessage(ModelMessage.Role.USER, "给我一个对象")), ModelOutputMode.JSON)));
        assertEquals(ModelGatewayException.Reason.INVALID_INPUT, jsonWithoutInstruction.reason());
        verifyNoInteractions(currentUser, credentials);
        assertEquals(0, server.requests().size());
    }

    @Test
    void countsSupplementaryUnicodeCharactersAsCodePoints() {
        authenticateAs(11L);
        server.respondWith(MockResponse.ok(successBody("ok", "stop", true)));
        String twentyThousandEmoji = "🍲".repeat(20_000);

        ModelChatResponse response = gateway.complete(textRequest(twentyThousandEmoji));

        assertEquals("ok", response.content());
        assertEquals(1, server.requests().size());
        ModelGatewayException oversized = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(textRequest(twentyThousandEmoji + "🍲")));
        assertEquals(ModelGatewayException.Reason.INVALID_INPUT, oversized.reason());
        assertEquals(1, server.requests().size());
    }

    @Test
    void mapsProviderStatusesWithoutRetryOrLeakingCredential() {
        authenticateAs(11L);
        List<MockResponse> failures = List.of(
                new MockResponse(401, "provider-secret-error", 0),
                new MockResponse(429, "provider-secret-error", 0),
                new MockResponse(503, "provider-secret-error", 0));
        List<ModelGatewayException.Reason> expected = List.of(
                ModelGatewayException.Reason.MODEL_AUTHENTICATION_FAILED,
                ModelGatewayException.Reason.RATE_LIMITED,
                ModelGatewayException.Reason.PROVIDER_UNAVAILABLE);

        for (int i = 0; i < failures.size(); i++) {
            server.clearRequests();
            server.respondWith(failures.get(i));
            ModelGatewayException failure = assertThrows(ModelGatewayException.class,
                    () -> gateway.complete(textRequest("SAFE_ERROR_MARKER")));
            assertEquals(expected.get(i), failure.reason());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains(KEY_A));
            assertFalse(failure.toString().contains("provider-secret-error"));
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void rejectsEmptyChoicesMalformedJsonAndTruncatedJsonSafely() {
        authenticateAs(11L);
        List<String> bodies = List.of(
                "", successBody("", "stop", true),
                "{\"id\":\"x\",\"choices\":null}",
                successBody("{broken", "stop", true),
                successBody("{\"partial\":true}", "length", true));
        for (String body : bodies) {
            server.clearRequests();
            server.respondWith(new MockResponse(200, body, 0));
            ModelGatewayException invalid = assertThrows(ModelGatewayException.class,
                    () -> gateway.complete(jsonRequest("请输出 JSON 结果")));
            assertEquals(ModelGatewayException.Reason.INVALID_RESPONSE, invalid.reason());
            assertEquals(1, server.requests().size());
        }

        server.clearRequests();
        server.respondWith(new MockResponse(200, successBody("{\"ok\":true}", "stop", false), 0));
        ModelChatResponse noUsage = gateway.complete(jsonRequest("请输出 JSON 结果"));
        assertNull(noUsage.usage());
        assertEquals(1, server.requests().size());
    }

    @Test
    void rejectsTextAndJsonTruncationCaseInsensitivelyWithoutRetryOrLeaks() {
        authenticateAs(11L);
        Logger modelLogger = (Logger) LoggerFactory.getLogger("org.springframework.ai.deepseek.DeepSeekChatModel");
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previousLevel = modelLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        modelLogger.setLevel(Level.TRACE);
        rootLogger.addAppender(appender);

        try {
            List<ModelOutputMode> modes = List.of(
                    ModelOutputMode.TEXT, ModelOutputMode.TEXT, ModelOutputMode.JSON, ModelOutputMode.JSON);
            List<String> finishReasons = List.of("length", "LENGTH", "length", "LENGTH");
            for (int i = 0; i < modes.size(); i++) {
                ModelOutputMode mode = modes.get(i);
                String promptMarker = "UNIQUE_PROMPT_R1_" + i;
                String responseMarker = mode == ModelOutputMode.JSON
                        ? "{\"raw_provider_body_r1\":" + i + "}"
                        : "RAW_PROVIDER_BODY_R1_" + i;
                String prompt = promptMarker + (mode == ModelOutputMode.JSON ? " 请输出 JSON" : "");
                server.clearRequests();
                server.respondWith(MockResponse.ok(successBody(responseMarker, finishReasons.get(i), true)));

                ModelGatewayException failure = assertThrows(ModelGatewayException.class,
                        () -> gateway.complete(new ModelChatRequest(
                                List.of(new ModelMessage(ModelMessage.Role.USER, prompt)), mode)));

                assertEquals(ModelGatewayException.Reason.INVALID_RESPONSE, failure.reason());
                assertNull(failure.getCause());
                assertFalse(failure.getMessage().contains(KEY_A));
                assertFalse(failure.getMessage().contains(promptMarker));
                assertFalse(failure.getMessage().contains(responseMarker));
                assertFalse(failure.toString().contains(KEY_A));
                assertFalse(failure.toString().contains(promptMarker));
                assertFalse(failure.toString().contains(responseMarker));
                assertEquals(1, server.requests().size());
                assertTrue(server.requests().getFirst().authorization().equals("Bearer " + KEY_A));
                assertTrue(appender.list.stream().noneMatch(event -> {
                    String logged = event.getFormattedMessage();
                    return logged.contains(KEY_A) || logged.contains(promptMarker) || logged.contains(responseMarker);
                }));
            }
        } finally {
            rootLogger.detachAppender(appender);
            modelLogger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void mapsResponseTimeoutAfterExactlyOneProviderRequest() {
        authenticateAs(11L);
        server.respondWith(new MockResponse(200, successBody("ok", "stop", true), 400));
        ModelGatewayServiceImpl shortTimeoutGateway = new ModelGatewayServiceImpl(currentUser, credentials,
                new DefaultDeepSeekChatClientFactory(server.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(80)));

        ModelGatewayException timeout = assertThrows(ModelGatewayException.class,
                () -> shortTimeoutGateway.complete(textRequest("TIMEOUT_MARKER")));

        assertEquals(ModelGatewayException.Reason.TIMEOUT, timeout.reason());
        assertEquals(1, server.requests().size());
    }

    @Test
    void supplierEmptyResponseLoggerIsConfiguredToSuppressPromptAndMessagesRedactToString() throws Exception {
        String marker = "UNIQUE_PROMPT_MUST_NOT_APPEAR_IN_LOGS_T12";
        assertFalse(new ModelMessage(ModelMessage.Role.USER, marker).toString().contains(marker));
        assertFalse(textRequest(marker).toString().contains(marker));
        assertFalse(textRequest(marker).messages().getFirst().toString().contains(marker));

        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        String configuredLevel = sources.stream().map(source -> source.getProperty(
                "logging.level.org.springframework.ai.deepseek.DeepSeekChatModel"))
                .filter(String.class::isInstance).map(String.class::cast).findFirst().orElseThrow();
        Logger modelLogger = (Logger) LoggerFactory.getLogger("org.springframework.ai.deepseek.DeepSeekChatModel");
        Level previousLevel = modelLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        modelLogger.setLevel(Level.valueOf(configuredLevel));
        modelLogger.addAppender(appender);

        try {
            authenticateAs(11L);
            server.respondWith(new MockResponse(200, "", 0));
            ModelGatewayException empty = assertThrows(ModelGatewayException.class,
                    () -> gateway.complete(textRequest(marker)));
            assertEquals(ModelGatewayException.Reason.INVALID_RESPONSE, empty.reason());
            assertEquals(1, server.requests().size());
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(marker)));
        } finally {
            modelLogger.detachAppender(appender);
            modelLogger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private Long currentUserId() {
        if (SecurityContextHolder.getContext().getAuthentication() == null
                || !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Long id)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "未登录");
        }
        return id;
    }

    private void authenticateAs(Long userId) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, "ignored", List.of()));
        SecurityContextHolder.setContext(context);
    }

    private ModelChatRequest textRequest(String text) {
        return new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.USER, text)), ModelOutputMode.TEXT);
    }

    private ModelChatRequest jsonRequest(String instruction) {
        return new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.USER, instruction)), ModelOutputMode.JSON);
    }

    private String successBody(String content, String finishReason, boolean includeUsage) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"mock-id\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
                + "\"finish_reason\":\"" + finishReason + "\"}]"
                + (includeUsage ? ",\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}" : "")
                + "}";
    }

    private record ReceivedRequest(String path, String authorization, String body) {
    }

    private record MockResponse(int status, String body, long delayMillis) {
        static MockResponse ok(String body) {
            return new MockResponse(200, body, 0);
        }
    }

    private static final class MockDeepSeekServer implements AutoCloseable {
        private final HttpServer httpServer;
        private final ExecutorService executor;
        private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<MockResponse> response = new AtomicReference<>(
                MockResponse.ok("{\"id\":\"mock-id\",\"choices\":[]}"));
        private final AtomicReference<CountDownLatch> barrier = new AtomicReference<>();

        MockDeepSeekServer() throws IOException {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool();
            httpServer.setExecutor(executor);
            httpServer.createContext("/", this::handle);
            httpServer.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + httpServer.getAddress().getPort();
        }

        void respondWith(MockResponse next) {
            response.set(next);
        }

        void setBarrier(CountDownLatch next) {
            barrier.set(next);
        }

        List<ReceivedRequest> requests() {
            return List.copyOf(requests);
        }

        void clearRequests() {
            requests.clear();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new ReceivedRequest(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), requestBody));
            CountDownLatch gate = barrier.get();
            if (gate != null) {
                gate.countDown();
                try {
                    gate.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            MockResponse current = response.get();
            if (current.delayMillis() > 0) {
                try {
                    Thread.sleep(current.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(current.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // 超时用例中客户端会先关闭连接；测试只关注请求次数与安全错误分类。
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            httpServer.stop(0);
            executor.shutdownNow();
        }
    }
}
