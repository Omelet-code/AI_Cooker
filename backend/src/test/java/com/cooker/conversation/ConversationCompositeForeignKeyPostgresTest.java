package com.cooker.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 用真实 PostgreSQL 错误码和约束名验证会话归属复合外键。 */
@Testcontainers(disabledWithoutDocker = true)
class ConversationCompositeForeignKeyPostgresTest {
    @Container static final PostgreSQLContainer WITH_FOREIGN_KEYS = postgres("cooker_t13_fk_test");
    @Container static final PostgreSQLContainer CONTROL = postgres("cooker_t13_fk_control_test");

    @Test void 复合外键错误精确失败且移除目标外键后同样数据可写() {
        migrate(WITH_FOREIGN_KEYS);
        migrate(CONTROL);
        JdbcTemplate checked = jdbc(WITH_FOREIGN_KEYS);
        JdbcTemplate control = jdbc(CONTROL);
        Fixture checkedFixture = seed(checked);
        Fixture controlFixture = seed(control);

        assertInvalidTuplesHitOtherConstraintsFirst(checked, checkedFixture);
        Counts before = counts(checked);
        var conversationUpdatedAt = checked.queryForObject("SELECT updated_at FROM conversation WHERE id=?",
                java.time.OffsetDateTime.class, checkedFixture.aliceConversationId);
        assertForeignKeyViolation(() -> insertFailedTurn(checked, checkedFixture.aliceConversationId,
                checkedFixture.bobId, 99), "fk_turn_conversation_owner");
        assertForeignKeyViolation(() -> insertMessage(checked, checkedFixture.aliceOtherConversationId,
                checkedFixture.aliceId, checkedFixture.wrongConversationTurnId, "ASSISTANT", "错会话", 99),
                "fk_message_turn_owner");
        assertForeignKeyViolation(() -> insertMessage(checked, checkedFixture.aliceConversationId,
                checkedFixture.bobId, checkedFixture.wrongUserTurnId, "ASSISTANT", "错用户", 99),
                "fk_message_turn_owner");
        assertUnchanged(checked, checkedFixture, before, conversationUpdatedAt);

        // 合法的同用户/同会话轮次及其消息可写，证明负例不是由其余字段或 CHECK 条件造成。
        long validTurnId = insertSucceededTurn(checked, checkedFixture.aliceConversationId, checkedFixture.aliceId, 99);
        assertThat(checked.queryForObject("SELECT status FROM conversation_turn WHERE id=?", String.class, validTurnId)).isEqualTo("SUCCEEDED");
        assertThat(insertMessage(checked, checkedFixture.aliceConversationId, checkedFixture.aliceId,
                validTurnId, "USER", "合法用户消息", 197)).isEqualTo(1);
        assertThat(insertMessage(checked, checkedFixture.aliceConversationId, checkedFixture.aliceId,
                validTurnId, "ASSISTANT", "合法助手消息", 198)).isEqualTo(1);
        assertThat(counts(checked).messages).isEqualTo(before.messages + 2);

        // 对照库仅删除本任务所测的两个复合 FK；CHECK、普通外键和唯一索引保持启用。
        control.execute("ALTER TABLE conversation_turn DROP CONSTRAINT fk_turn_conversation_owner");
        control.execute("ALTER TABLE chat_message DROP CONSTRAINT fk_message_turn_owner");
        assertThat(constraintCount(control, "fk_turn_conversation_owner")).isZero();
        assertThat(constraintCount(control, "fk_message_turn_owner")).isZero();
        assertThat(constraintCount(control, "ck_turn_status")).isEqualTo(1);
        assertThat(constraintCount(control, "ck_message_role")).isEqualTo(1);
        assertThat(indexExists(control, "uq_turn_one_active_per_conversation")).isTrue();
        assertThat(constraintCount(control, "uq_message_turn_role")).isEqualTo(1);

        long unconstrainedTurnId = insertFailedTurn(control, controlFixture.aliceConversationId,
                controlFixture.bobId, 99);
        int wrongConversation = insertMessage(control, controlFixture.aliceOtherConversationId,
                controlFixture.aliceId, controlFixture.wrongConversationTurnId, "ASSISTANT", "对照错会话", 99);
        int wrongUser = insertMessage(control, controlFixture.aliceConversationId,
                controlFixture.bobId, controlFixture.wrongUserTurnId, "ASSISTANT", "对照错用户", 99);
        assertThat(wrongConversation).isEqualTo(1);
        assertThat(wrongUser).isEqualTo(1);
        assertThat(control.queryForObject("SELECT count(*) FROM conversation_turn WHERE id=? AND user_id=? AND conversation_id=?",
                Integer.class, unconstrainedTurnId, controlFixture.bobId, controlFixture.aliceConversationId)).isEqualTo(1);
        assertThat(control.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND conversation_id=? AND user_id=?",
                Integer.class, controlFixture.wrongConversationTurnId, controlFixture.aliceOtherConversationId, controlFixture.aliceId)).isEqualTo(1);
        assertThat(control.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND conversation_id=? AND user_id=?",
                Integer.class, controlFixture.wrongUserTurnId, controlFixture.aliceConversationId, controlFixture.bobId)).isEqualTo(1);
    }

    private static PostgreSQLContainer postgres(String database) {
        return new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName(database).withUsername("root").withPassword("test-only");
    }

    private static void migrate(PostgreSQLContainer postgres) {
        // 本专项加载当前完整V1～V15整合基线，精确检查迁移数和成功版本。
        assertThat(Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate().migrationsExecuted).isEqualTo(15);
        assertThat(jdbc(postgres).queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres) {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static Fixture seed(JdbcTemplate jdbc) {
        long alice = insertUser(jdbc, "alice"), bob = insertUser(jdbc, "bob");
        long aliceConversation = insertConversation(jdbc, alice, "Alice 主会话");
        long aliceOtherConversation = insertConversation(jdbc, alice, "Alice 另一个会话");
        insertConversation(jdbc, bob, "Bob 会话");
        long activeTurn = insertPendingTurn(jdbc, aliceConversation, alice, 1);
        insertMessage(jdbc, aliceConversation, alice, activeTurn, "USER", "原始用户消息", 1);
        long wrongConversationTurn = insertFailedTurn(jdbc, aliceConversation, alice, 2);
        long wrongUserTurn = insertFailedTurn(jdbc, aliceConversation, alice, 3);
        long positiveTurn = insertSucceededTurn(jdbc, aliceOtherConversation, alice, 1);
        insertMessage(jdbc, aliceOtherConversation, alice, positiveTurn, "USER", "合法用户消息", 1);
        insertMessage(jdbc, aliceOtherConversation, alice, positiveTurn, "ASSISTANT", "合法助手消息", 2);
        return new Fixture(alice, bob, aliceConversation, aliceOtherConversation, activeTurn,
                wrongConversationTurn, wrongUserTurn);
    }

    private static long insertUser(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES(?,?) RETURNING id",
                Long.class, "fk_" + name, "test-only-hash");
    }

    private static long insertConversation(JdbcTemplate jdbc, long userId, String title) {
        return jdbc.queryForObject("INSERT INTO conversation(user_id,title) VALUES(?,?) RETURNING id", Long.class, userId, title);
    }

    private static long insertPendingTurn(JdbcTemplate jdbc, long conversationId, long userId, long turnNo) {
        return jdbc.queryForObject("INSERT INTO conversation_turn(conversation_id,user_id,request_id,input_hash,turn_no,status,deadline_at) "
                + "VALUES(?,?,?,?,?,'PENDING',clock_timestamp()+interval '1 hour') RETURNING id", Long.class,
                conversationId, userId, UUID.randomUUID(), "a".repeat(64), turnNo);
    }

    private static long insertFailedTurn(JdbcTemplate jdbc, long conversationId, long userId, long turnNo) {
        return jdbc.queryForObject("INSERT INTO conversation_turn(conversation_id,user_id,request_id,input_hash,turn_no,status,finished_at,deadline_at,error_code) "
                + "VALUES(?,?,?,?,?,'FAILED',clock_timestamp(),clock_timestamp()+interval '1 hour','EXECUTION_FAILED') RETURNING id",
                Long.class, conversationId, userId, UUID.randomUUID(), "b".repeat(64), turnNo);
    }

    private static long insertSucceededTurn(JdbcTemplate jdbc, long conversationId, long userId, long turnNo) {
        return jdbc.queryForObject("INSERT INTO conversation_turn(conversation_id,user_id,request_id,input_hash,turn_no,status,started_at,finished_at,deadline_at) "
                + "VALUES(?,?,?,?,?,'SUCCEEDED',clock_timestamp(),clock_timestamp(),clock_timestamp()+interval '1 hour') RETURNING id",
                Long.class, conversationId, userId, UUID.randomUUID(), "c".repeat(64), turnNo);
    }

    private static int insertMessage(JdbcTemplate jdbc, long conversationId, long userId, long turnId,
            String role, String content, long sequence) {
        return jdbc.update("INSERT INTO chat_message(conversation_id,user_id,turn_id,role,content,sequence) VALUES(?,?,?,?,?,?)",
                conversationId, userId, turnId, role, content, sequence);
    }

    private static void assertInvalidTuplesHitOtherConstraintsFirst(JdbcTemplate jdbc, Fixture f) {
        // 原 turn 负例也是 PENDING，会先撞活动轮次部分唯一索引 23505，而非目标外键 23503。
        assertUniqueViolation(() -> insertPendingTurn(jdbc, f.aliceConversationId, f.bobId, 99),
                "uq_turn_one_active_per_conversation");
        // 原 message 负例复用已有 turn 的 USER 角色，会先撞 turn/role 唯一约束 23505。
        assertUniqueViolation(() -> insertMessage(jdbc, f.aliceOtherConversationId, f.aliceId,
                f.activeTurnId, "USER", "原形状", 99), "uq_message_turn_role");
    }

    private static void assertForeignKeyViolation(Runnable statement, String expectedConstraint) {
        assertThatThrownBy(statement::run).satisfies(thrown -> {
            PSQLException postgres = findPostgresException(thrown);
            if (postgres == null) throw new AssertionError("Spring 异常包装中应保留 PostgreSQL 原始异常", thrown);
            assertThat(postgres.getSQLState()).isEqualTo("23503");
            assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(expectedConstraint);
        });
    }

    private static void assertUniqueViolation(Runnable statement, String expectedConstraint) {
        assertThatThrownBy(statement::run).satisfies(thrown -> {
            PSQLException postgres = findPostgresException(thrown);
            if (postgres == null) throw new AssertionError("应捕获 PostgreSQL 唯一约束异常", thrown);
            assertThat(postgres.getSQLState()).isEqualTo("23505");
            assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(expectedConstraint);
        });
    }

    private static PSQLException findPostgresException(Throwable root) {
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof PSQLException postgres) return postgres;
            if (current.getCause() != null) pending.addLast(current.getCause());
            if (current instanceof SQLException sql && sql.getNextException() != null)
                pending.addLast(sql.getNextException());
        }
        return null;
    }

    private static Counts counts(JdbcTemplate jdbc) {
        return new Counts(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM conversation", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM conversation_turn", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM chat_message", Integer.class));
    }

    private static void assertUnchanged(JdbcTemplate jdbc, Fixture f, Counts before,
            java.time.OffsetDateTime conversationUpdatedAt) {
        assertThat(counts(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT title FROM conversation WHERE id=?", String.class, f.aliceConversationId))
                .isEqualTo("Alice 主会话");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=?", java.time.OffsetDateTime.class,
                f.aliceConversationId)).isEqualTo(conversationUpdatedAt);
        assertThat(jdbc.queryForObject("SELECT status FROM conversation_turn WHERE id=?", String.class, f.activeTurnId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE turn_id=? AND role='USER'", String.class, f.activeTurnId))
                .isEqualTo("原始用户消息");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id IN (?,?)", Integer.class, f.aliceId, f.bobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_turn WHERE conversation_id=? AND user_id=? AND turn_no=99",
                Integer.class, f.aliceConversationId, f.bobId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND conversation_id=?",
                Integer.class, f.wrongConversationTurnId, f.aliceOtherConversationId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE turn_id=? AND user_id=?",
                Integer.class, f.wrongUserTurnId, f.bobId)).isZero();
    }

    private static int constraintCount(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints WHERE constraint_schema='public' AND constraint_name=?",
                Integer.class, name);
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + name);
    }

    private record Fixture(long aliceId, long bobId, long aliceConversationId, long aliceOtherConversationId,
            long activeTurnId, long wrongConversationTurnId, long wrongUserTurnId) { }
    private record Counts(int users, int conversations, int turns, int messages) { }
}
