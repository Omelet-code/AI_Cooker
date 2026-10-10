package com.cooker.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cooker.common.ApiException;
import com.cooker.conversation.service.ConversationService;
import com.cooker.conversation.service.ConversationTurnService;
import com.cooker.conversation.vo.TurnStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 以隔离 PostgreSQL 和真实安全过滤器验证会话隔离、幂等和短事务生命周期。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ConversationHttpPostgresTest {
    private static final String TEST_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t13_test").withUsername("root").withPassword("test-only");

    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("cooker.identity.llm-credential.master-key", () -> TEST_MASTER_KEY);
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationService conversations;
    @Autowired ConversationTurnService turns;

    @Test void 真实HTTP身份边界以及幂等轮次并发与过期恢复() throws Exception {
        // 会话HTTP回归使用当前完整V1～V15整合基线，保留所有原业务断言。
        assertThat(jdbc.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        Browser anonymous = new Browser();
        assertThat(anonymous.get("/conversations").statusCode()).isEqualTo(401);
        anonymous.csrf();
        assertThat(anonymous.post("/conversations", "{}").statusCode()).isEqualTo(401);

        Browser alice = new Browser();
        alice.csrf();
        assertThat(alice.post("/auth/register", "{\"username\":\"t13_alice\",\"password\":\"long-password-123\"}").statusCode()).isEqualTo(201);
        alice.login("t13_alice", "long-password-123");
        Browser bob = new Browser();
        bob.csrf();
        assertThat(bob.post("/auth/register", "{\"username\":\"t13_bob\",\"password\":\"long-password-456\"}").statusCode()).isEqualTo(201);
        bob.login("t13_bob", "long-password-456");

        assertThat(alice.postWithoutCsrf("/conversations", "{}").statusCode()).isEqualTo(403);
        HttpResponse<String> created = alice.post("/conversations", "{\"title\":\"  面 🍜  \"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode conversation = data(created);
        String cid = conversation.path("id").asText();
        assertThat(conversation.path("title").asText()).isEqualTo("面 🍜");
        assertThat(data(alice.get("/conversations/" + cid)).path("id").asText()).isEqualTo(cid);
        assertThat(code(bob.get("/conversations/" + cid), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(alice.get("/conversations/" + cid + "/messages").body()).contains("\"items\":[]");
        assertThat(code(alice.get("/conversations?size=51"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(code(alice.get("/conversations/01"), 400)).isEqualTo("VALIDATION_ERROR");
        assertThat(code(alice.get("/conversations/9223372036854775808"), 400)).isEqualTo("VALIDATION_ERROR");

        long aliceId = Long.parseLong(json.readTree(alice.get("/users/me").body()).at("/data/id").asText());
        withUser(aliceId, () -> {
            UUID requestId = UUID.randomUUID();
            String input = "原样保留 🍲  ";
            var first = turns.registerTurn(cid, requestId, input);
            assertThat(first.created()).isTrue();
            assertThat(turns.registerTurn(cid, requestId, input).created()).isFalse();
            assertThatThrownBy(() -> turns.registerTurn(cid, requestId, input + "x"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
            assertThat(turns.startTurn(cid, first.turn().id()).status()).isEqualTo(TurnStatus.RUNNING);
            assertThatThrownBy(() -> turns.startTurn(cid, first.turn().id()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));

            // 助手消息插入后故意触发数据库错误，验证消息和 SUCCEEDED 状态一并回滚。
            jdbc.execute("CREATE FUNCTION t13_reject_assistant() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.role='ASSISTANT' THEN RAISE EXCEPTION 'test rollback'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER t13_reject_assistant BEFORE INSERT ON chat_message FOR EACH ROW EXECUTE FUNCTION t13_reject_assistant()");
            assertThatThrownBy(() -> turns.succeedTurn(cid, first.turn().id(), "完成 🍽️"));
            jdbc.execute("DROP TRIGGER t13_reject_assistant ON chat_message");
            jdbc.execute("DROP FUNCTION t13_reject_assistant()");
            assertThat(turns.getTurn(cid, first.turn().id()).status()).isEqualTo(TurnStatus.RUNNING);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND role='ASSISTANT'", Integer.class,
                    Long.parseLong(first.turn().id()))).isZero();
            // 助手消息插入成功后让终态 UPDATE 影响零行，事务仍须回滚消息和父会话时间。
            var timestampBeforeSkippedUpdate = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, Long.parseLong(cid));
            jdbc.execute("CREATE FUNCTION t13_skip_succeeded() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='SUCCEEDED' THEN RETURN NULL; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER t13_skip_succeeded BEFORE UPDATE ON conversation_turn FOR EACH ROW EXECUTE FUNCTION t13_skip_succeeded()");
            assertThatThrownBy(() -> turns.succeedTurn(cid, first.turn().id(), "更新零行"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));
            jdbc.execute("DROP TRIGGER t13_skip_succeeded ON conversation_turn");
            jdbc.execute("DROP FUNCTION t13_skip_succeeded()");
            assertThat(turns.getTurn(cid, first.turn().id()).status()).isEqualTo(TurnStatus.RUNNING);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND role='ASSISTANT'", Integer.class,
                    Long.parseLong(first.turn().id()))).isZero();
            assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, Long.parseLong(cid)))
                    .isEqualTo(timestampBeforeSkippedUpdate);
            assertThat(turns.succeedTurn(cid, first.turn().id(), "完成 🍽️").status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThatThrownBy(() -> turns.failTurn(cid, first.turn().id(), "EXECUTION_FAILED"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));
            JsonNode history = data(alice.get("/conversations/" + cid + "/messages"));
            assertThat(history.path("items")).hasSize(2);
            assertThat(history.at("/items/0/sequence").asLong()).isEqualTo(1);
            assertThat(history.at("/items/1/sequence").asLong()).isEqualTo(2);
            assertThat(history.toString()).contains(input, "完成 🍽️");

            // 同会话不同 requestId 并发竞争时，只能有一个创建，另一个得到 BUSY。
            var gate = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var one = pool.submit(() -> { gate.await(); return registerAs(aliceId, cid, UUID.randomUUID(), "竞争一"); });
                var two = pool.submit(() -> { gate.await(); return registerAs(aliceId, cid, UUID.randomUUID(), "竞争二"); });
                gate.countDown();
                List<String> outcomes = List.of(one.get(), two.get());
                assertThat(outcomes.stream().filter("created"::equals).count()).isEqualTo(1);
                assertThat(outcomes.stream().filter("busy"::equals).count()).isEqualTo(1);
            }
            var pending = jdbc.queryForMap("SELECT id, request_id FROM conversation_turn WHERE conversation_id=? AND status='PENDING'", Long.parseLong(cid));
            long pendingId = ((Number) pending.get("id")).longValue();
            jdbc.update("UPDATE conversation_turn SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?", pendingId);
            assertThat(turns.recoverExpiredTurns(cid)).isEqualTo(1);
            assertThat(turns.getTurn(cid, String.valueOf(pendingId)).status()).isEqualTo(TurnStatus.FAILED);
            assertThat(turns.getTurn(cid, String.valueOf(pendingId)).error().code()).isEqualTo("RUNTIME_INTERRUPTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND role='ASSISTANT'", Integer.class, pendingId)).isZero();
            String replayKey = UUID.randomUUID().toString();
            long freshConversation = conversationsId(conversations.create("同键并发"));
            var sameKeyGate = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var one = pool.submit(() -> { sameKeyGate.await(); return registerAs(aliceId, String.valueOf(freshConversation), UUID.fromString(replayKey), "同一请求"); });
                var two = pool.submit(() -> { sameKeyGate.await(); return registerAs(aliceId, String.valueOf(freshConversation), UUID.fromString(replayKey), "同一请求"); });
                sameKeyGate.countDown();
                List<String> outcomes = List.of(one.get(), two.get());
                assertThat(outcomes.stream().filter("created"::equals).count()).isEqualTo(1);
                assertThat(outcomes.stream().filter("replay"::equals).count()).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message m JOIN conversation_turn t ON t.id=m.turn_id WHERE t.conversation_id=? AND m.role='USER'", Integer.class, freshConversation)).isEqualTo(1);
            long lateTurnId = jdbc.queryForObject("SELECT id FROM conversation_turn WHERE conversation_id=?", Long.class, freshConversation);
            assertThat(turns.startTurn(String.valueOf(freshConversation), String.valueOf(lateTurnId)).status()).isEqualTo(TurnStatus.RUNNING);
            jdbc.update("UPDATE conversation_turn SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?", lateTurnId);
            assertThatThrownBy(() -> turns.succeedTurn(String.valueOf(freshConversation), String.valueOf(lateTurnId), "迟到回答"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));
            assertThat(turns.recoverExpiredTurns(String.valueOf(freshConversation))).isEqualTo(1);
            assertThat(turns.getTurn(String.valueOf(freshConversation), String.valueOf(lateTurnId)).error().code()).isEqualTo("RUNTIME_INTERRUPTED");
            return null;
        });

        // 未映射的客户端消息写接口不得创建任何轮次。
        long before = jdbc.queryForObject("SELECT count(*) FROM conversation_turn", Long.class);
        HttpResponse<String> unsupportedWrite = alice.post("/conversations/" + cid + "/messages", "{\"content\":\"不要执行\"}");
        assertThat(unsupportedWrite.statusCode()).isIn(403, 405);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_turn", Long.class)).isEqualTo(before);
    }

    @Test void 同键重放时回收过期轮次并且普通重放不触碰会话时间() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Browser user = new Browser();
        user.csrf();
        String username = "t13_r1_" + suffix;
        String password = "long-password-r1";
        assertThat(user.post("/auth/register", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").statusCode()).isEqualTo(201);
        user.login(username, password);
        long userId = Long.parseLong(json.readTree(user.get("/users/me").body()).at("/data/id").asText());

        withUser(userId, () -> {
            long pendingConversation = conversationsId(conversations.create("过期 PENDING"));
            assertExpiredSameKeyReplay(userId, pendingConversation, false);

            long runningConversation = conversationsId(conversations.create("过期 RUNNING"));
            assertExpiredSameKeyReplay(userId, runningConversation, true);

            long liveConversation = conversationsId(conversations.create("未过期重放"));
            UUID requestId = UUID.randomUUID();
            var created = turns.registerTurn(String.valueOf(liveConversation), requestId, "普通重放");
            jdbc.update("UPDATE conversation SET updated_at=clock_timestamp()-interval '10 seconds' WHERE id=?", liveConversation);
            var beforeReplay = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, liveConversation);
            var replay = turns.registerTurn(String.valueOf(liveConversation), requestId, "普通重放");
            assertThat(replay.created()).isFalse();
            assertThat(replay.turn().id()).isEqualTo(created.turn().id());
            assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, liveConversation)).isEqualTo(beforeReplay);
            return null;
        });
    }

    @Test void 用户边界复合外键和只读请求不改变过期状态() throws Exception {
        Browser alice = newUser("t13_iso_a");
        Browser bob = newUser("t13_iso_b");
        long aliceId = userId(alice), bobId = userId(bob);
        long[] ids = withUser(aliceId, () -> {
            long cid = conversationsId(conversations.create("A 的会话"));
            long otherCid = conversationsId(conversations.create("A 的另一个会话"));
            var turn = turns.registerTurn(String.valueOf(cid), UUID.randomUUID(), "只有 A 可读写");
            return new long[] {cid, otherCid, Long.parseLong(turn.turn().id())};
        });
        String cid = String.valueOf(ids[0]), otherCid = String.valueOf(ids[1]);
        String tid = String.valueOf(ids[2]);

        assertThat(data(bob.get("/conversations?page=1&size=10")).path("total").asLong()).isZero();
        assertThat(code(bob.get("/conversations/" + cid), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(code(bob.get("/conversations/" + cid + "/messages"), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(code(bob.get("/conversations/" + cid + "/turns/" + tid), 404)).isEqualTo("CONVERSATION_TURN_NOT_FOUND");

        withUser(bobId, () -> {
            assertThat(conversations.create("B 的会话").title()).isEqualTo("B 的会话");
            assertNotFound(() -> turns.registerTurn(cid, UUID.randomUUID(), "越权注册"));
            assertNotFound(() -> turns.startTurn(cid, tid));
            assertNotFound(() -> turns.succeedTurn(cid, tid, "越权完成"));
            assertNotFound(() -> turns.failTurn(cid, tid, "EXECUTION_FAILED"));
            assertNotFound(() -> turns.recoverExpiredTurns(cid));
            return null;
        });
        withUser(aliceId, () -> {
            assertNotFound(() -> turns.startTurn(otherCid, tid));
            assertNotFound(() -> turns.succeedTurn(otherCid, tid, "错误会话"));
            assertNotFound(() -> turns.failTurn(otherCid, tid, "EXECUTION_FAILED"));
            assertNotFound(() -> turns.getTurn(otherCid, tid));
            return null;
        });

        jdbc.update("UPDATE conversation_turn SET deadline_at=clock_timestamp()-interval '10 seconds' WHERE id=?", ids[2]);
        jdbc.update("UPDATE conversation SET updated_at=clock_timestamp()-interval '10 seconds' WHERE id=?", ids[0]);
        var before = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, ids[0]);
        var pending = withUser(aliceId, () -> turns.getTurn(cid, tid));
        assertThat(pending.status()).isEqualTo(TurnStatus.PENDING);
        assertThat(data(alice.get("/conversations/" + cid)).path("id").asText()).isEqualTo(cid);
        assertThat(data(alice.get("/conversations/" + cid + "/messages?page=1&size=10")).path("total").asLong()).isEqualTo(1);
        assertThat(data(alice.get("/conversations/" + cid + "/turns/" + tid)).path("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM conversation_turn WHERE id=?", String.class, ids[2])).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, ids[0])).isEqualTo(before);
        withUser(aliceId, () -> {
            assertThatThrownBy(() -> turns.startTurn(cid, tid)).isInstanceOf(ApiException.class);
            assertThat(turns.recoverExpiredTurns(cid)).isEqualTo(1);
            assertThat(turns.getTurn(cid, tid).status()).isEqualTo(TurnStatus.FAILED);
            return null;
        });
    }

    @Test void 标题文本错误码消息序号和HTTP分页边界() throws Exception {
        Browser user = newUser("t13_edge");
        long userId = userId(user);
        user.csrf();
        HttpResponse<String> missingTitle = user.post("/conversations", "{}");
        assertThat(data(missingTitle).path("title").asText()).as(missingTitle.body()).isEqualTo("新对话");
        user.csrf();
        HttpResponse<String> nullTitle = user.post("/conversations", "{\"title\":null}");
        assertThat(data(nullTitle).path("title").asText()).as(nullTitle.body()).isEqualTo("新对话");
        user.csrf();
        assertThat(code(user.post("/conversations", "{\"title\":\"   \"}"), 400)).isEqualTo("VALIDATION_ERROR");
        user.csrf();
        assertThat(data(user.post("/conversations", json.writeValueAsString(java.util.Map.of("title", "🍜".repeat(100))))).path("title").asText()).hasSize(200);
        user.csrf();
        assertThat(code(user.post("/conversations", json.writeValueAsString(java.util.Map.of("title", "🍜".repeat(101)))), 400)).isEqualTo("VALIDATION_ERROR");

        long[] ids = withUser(userId, () -> {
            long contentCid = conversationsId(conversations.create("内容边界"));
            assertThatThrownBy(() -> turns.registerTurn(String.valueOf(contentCid), UUID.randomUUID(), " \t\n ")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> turns.registerTurn(String.valueOf(contentCid), UUID.randomUUID(), "🍲".repeat(4001))).isInstanceOf(ApiException.class);
            var maxUser = turns.registerTurn(String.valueOf(contentCid), UUID.randomUUID(), "🍲".repeat(4000));
            assertThat(maxUser.created()).isTrue();
            assertThat(jdbc.queryForObject("SELECT char_length(content) FROM chat_message WHERE turn_id=?", Integer.class,
                    Long.parseLong(maxUser.turn().id()))).isEqualTo(4000);
            assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE turn_id=?", String.class,
                    Long.parseLong(maxUser.turn().id()))).isEqualTo("🍲".repeat(4000));
            turns.failTurn(String.valueOf(contentCid), maxUser.turn().id(), "EXECUTION_FAILED");

            long assistantCid = conversationsId(conversations.create("助手边界"));
            var assistantTurn = turns.registerTurn(String.valueOf(assistantCid), UUID.randomUUID(), "用户消息");
            turns.startTurn(String.valueOf(assistantCid), assistantTurn.turn().id());
            assertThatThrownBy(() -> turns.succeedTurn(String.valueOf(assistantCid), assistantTurn.turn().id(), "🥘".repeat(8001))).isInstanceOf(ApiException.class);
            assertThat(turns.getTurn(String.valueOf(assistantCid), assistantTurn.turn().id()).status()).isEqualTo(TurnStatus.RUNNING);
            turns.succeedTurn(String.valueOf(assistantCid), assistantTurn.turn().id(), "🥘".repeat(8000));

            long gapCid = conversationsId(conversations.create("序号缺口"));
            var failed = turns.registerTurn(String.valueOf(gapCid), UUID.randomUUID(), "失败轮用户");
            var beforeInvalidFailure = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, gapCid);
            assertThatThrownBy(() -> turns.failTurn(String.valueOf(gapCid), failed.turn().id(), "INVALID_ERROR_CODE")).isInstanceOf(ApiException.class);
            assertThat(turns.getTurn(String.valueOf(gapCid), failed.turn().id()).status()).isEqualTo(TurnStatus.PENDING);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, gapCid)).isEqualTo(beforeInvalidFailure);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id=?", Integer.class, gapCid)).isEqualTo(1);
            turns.failTurn(String.valueOf(gapCid), failed.turn().id(), "EXECUTION_FAILED");
            var succeeded = turns.registerTurn(String.valueOf(gapCid), UUID.randomUUID(), "下一轮用户");
            turns.startTurn(String.valueOf(gapCid), succeeded.turn().id());
            turns.succeedTurn(String.valueOf(gapCid), succeeded.turn().id(), "下一轮助手");
            return new long[] {contentCid, assistantCid, gapCid};
        });

        JsonNode firstPage = data(user.get("/conversations/" + ids[2] + "/messages?page=1&size=2"));
        JsonNode secondPage = data(user.get("/conversations/" + ids[2] + "/messages?page=2&size=2"));
        assertThat(firstPage.path("page").asInt()).isEqualTo(1);
        assertThat(firstPage.path("size").asInt()).isEqualTo(2);
        assertThat(firstPage.path("total").asLong()).isEqualTo(3);
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(secondPage.path("page").asInt()).isEqualTo(2);
        assertThat(secondPage.path("total").asLong()).isEqualTo(3);
        assertThat(secondPage.path("items")).hasSize(1);
        assertThat(List.of(firstPage.at("/items/0/sequence").asLong(), firstPage.at("/items/1/sequence").asLong(),
                secondPage.at("/items/0/sequence").asLong())).containsExactly(1L, 3L, 4L);
        assertThat(data(user.get("/conversations/" + ids[0] + "/messages?page=2147483647&size=50")).path("items")).isEmpty();
        assertThat(data(user.get("/conversations?page=2147483647&size=50")).path("items")).isEmpty();

        List<String> conversationIds = withUser(userId, () -> {
            for (int n = 0; n < 3; n++) conversations.create("分页排序 " + n);
            List<Long> latest = jdbc.queryForList("SELECT id FROM conversation WHERE user_id=? ORDER BY id DESC LIMIT 3", Long.class, userId);
            jdbc.update("UPDATE conversation SET updated_at=clock_timestamp()-interval '1 hour' WHERE user_id=?", userId);
            jdbc.update("UPDATE conversation SET updated_at='2030-01-01T00:00:00Z' WHERE id IN (?,?)", latest.get(0), latest.get(1));
            jdbc.update("UPDATE conversation SET updated_at='2029-12-31T23:59:00Z' WHERE id=?", latest.get(2));
            return latest.stream().map(String::valueOf).toList();
        });
        JsonNode conversationPage1 = data(user.get("/conversations?page=1&size=2"));
        JsonNode conversationPage2 = data(user.get("/conversations?page=2&size=2"));
        assertThat(conversationPage1.path("page").asInt()).isEqualTo(1);
        assertThat(conversationPage1.path("size").asInt()).isEqualTo(2);
        assertThat(conversationPage1.path("total").asLong()).isEqualTo(9);
        assertThat(conversationPage1.path("items")).hasSize(2);
        assertThat(conversationPage2.path("items")).hasSize(2);
        assertThat(conversationPage1.at("/items/0/id").asText()).isEqualTo(conversationIds.get(0));
        assertThat(conversationPage1.at("/items/1/id").asText()).isEqualTo(conversationIds.get(1));
        assertThat(conversationPage2.at("/items/0/id").asText()).isEqualTo(conversationIds.get(2));
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(404));
    }

    private Browser newUser(String prefix) throws Exception {
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String password = "long-password-123";
        Browser browser = new Browser();
        browser.csrf();
        assertThat(browser.post("/auth/register", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").statusCode()).isEqualTo(201);
        browser.login(username, password);
        return browser;
    }

    private long userId(Browser browser) throws Exception {
        return Long.parseLong(json.readTree(browser.get("/users/me").body()).at("/data/id").asText());
    }

    private void assertExpiredSameKeyReplay(long userId, long conversationId, boolean startFirst) throws Exception {
        String cid = String.valueOf(conversationId);
        UUID requestId = UUID.randomUUID();
        String body = "原样保留的 🍜 内容  ";
        var created = turns.registerTurn(cid, requestId, body);
        if (startFirst) turns.startTurn(cid, created.turn().id());
        long turnId = Long.parseLong(created.turn().id());
        jdbc.update("UPDATE conversation_turn SET deadline_at=clock_timestamp()-interval '10 seconds' WHERE id=?", turnId);
        jdbc.update("UPDATE conversation SET updated_at=clock_timestamp()-interval '10 seconds' WHERE id=?", conversationId);
        var beforeRecovery = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, conversationId);
        if (startFirst) {
            assertThatThrownBy(() -> turns.succeedTurn(cid, created.turn().id(), "过期后不得完成"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));
        } else {
            assertThatThrownBy(() -> turns.startTurn(cid, created.turn().id()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));
        }
        assertThat(turns.getTurn(cid, created.turn().id()).status()).isEqualTo(startFirst ? TurnStatus.RUNNING : TurnStatus.PENDING);
        assertThat(conversations.get(cid).id()).isEqualTo(cid);
        assertThat(conversations.messages(cid, 1, 50).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, conversationId)).isEqualTo(beforeRecovery);

        var replay = turns.registerTurn(cid, requestId, body);
        assertThat(replay.created()).isFalse();
        assertThat(replay.turn().id()).isEqualTo(created.turn().id());
        assertThat(replay.turn().status()).isEqualTo(TurnStatus.FAILED);
        assertThat(replay.turn().error().code()).isEqualTo("RUNTIME_INTERRUPTED");
        var afterRecovery = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, conversationId);
        assertThat(afterRecovery).isAfter(beforeRecovery);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND role='USER'", Integer.class, turnId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND role='ASSISTANT'", Integer.class, turnId)).isZero();
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE turn_id=? AND role='USER'", String.class, turnId)).isEqualTo(body);

        var secondReplay = turns.registerTurn(cid, requestId, body);
        assertThat(secondReplay.created()).isFalse();
        assertThat(secondReplay.turn().id()).isEqualTo(created.turn().id());
        assertThat(secondReplay.turn().status()).isEqualTo(TurnStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class, conversationId)).isEqualTo(afterRecovery);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=?", Integer.class, turnId)).isEqualTo(1);
        assertThatThrownBy(() -> turns.startTurn(cid, created.turn().id()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_TURN_STATE_CONFLICT"));
    }

    private String registerAs(long userId, String cid, UUID requestId, String content) {
        try {
            return withUser(userId, () -> {
                try { return turns.registerTurn(cid, requestId, content).created() ? "created" : "replay"; }
                catch (ApiException ex) { if ("CONVERSATION_BUSY".equals(ex.code())) return "busy"; throw ex; }
            });
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    private <T> T withUser(long userId, java.util.concurrent.Callable<T> action) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, "test", List.of()));
        var prior = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try { return action.call(); }
        finally { SecurityContextHolder.setContext(prior); }
    }
    private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    private long conversationsId(com.cooker.conversation.vo.ConversationView view) { return Long.parseLong(view.id()); }
    private String code(HttpResponse<String> response, int expected) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expected);
        return json.readTree(response.body()).path("code").asText();
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String csrf;
        URI uri(String path) { return URI.create("http://localhost:" + port + "/api" + path); }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {
            HttpResponse<String> response = get("/auth/csrf");
            csrf = json.readTree(response.body()).at("/data/token").asText();
        }
        HttpResponse<String> post(String path, String body) throws Exception { return post(path, body, true); }
        HttpResponse<String> postWithoutCsrf(String path, String body) throws Exception { return post(path, body, false); }
        private HttpResponse<String> post(String path, String body, boolean withCsrf) throws Exception {
            var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (withCsrf && csrf != null) builder.header("X-XSRF-TOKEN", csrf);
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void login(String username, String password) throws Exception {
            HttpResponse<String> response = post("/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            csrf();
        }
    }
}
