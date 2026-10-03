package com.cooker.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V8既存会话数据升级到V9时只新增结果快照结构，不改写旧业务行。 */
@Testcontainers(disabledWithoutDocker = true)
class ConversationRecommendationMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t14_v9_migration_test").withUsername("root").withPassword("test-only");

    @Test
    void v8升级v9保留原会话消息并建立版本化归属快照表() throws Exception {
        Flyway v8 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("8")).load();
        assertThat(v8.migrate().migrationsExecuted).isEqualTo(8);
        long userId;
        long conversationId;
        long turnId;
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "INSERT INTO app_user(username,password_hash) VALUES ('t14_v8_user','fake-hash') RETURNING id")) {
                rows.next(); userId = rows.getLong(1);
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "INSERT INTO conversation(user_id,title) VALUES (" + userId + ",'保留会话') RETURNING id")) {
                rows.next(); conversationId = rows.getLong(1);
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "INSERT INTO conversation_turn(conversation_id,user_id,request_id,input_hash,turn_no,status,deadline_at) "
                            + "VALUES(" + conversationId + "," + userId + ",gen_random_uuid(),repeat('a',64),1,'PENDING',clock_timestamp()+interval '1 minute') RETURNING id")) {
                rows.next(); turnId = rows.getLong(1);
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO chat_message(conversation_id,user_id,turn_id,role,content,sequence) VALUES("
                        + conversationId + "," + userId + "," + turnId + ",'USER','原始消息',1)");
            }
        }

        Flyway upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT (SELECT count(*) FROM app_user WHERE id=" + userId + "),"
                    + "(SELECT count(*) FROM conversation WHERE id=" + conversationId + "),"
                    + "(SELECT count(*) FROM conversation_turn WHERE id=" + turnId + "),"
                    + "(SELECT count(*) FROM chat_message WHERE turn_id=" + turnId + " AND role='USER'),"
                    + "(SELECT count(*) FROM flyway_schema_history WHERE success)")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.getInt(3)).isEqualTo(1);
                assertThat(rows.getInt(4)).isEqualTo(1);
                assertThat(rows.getInt(5)).isEqualTo(9);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema='public' AND constraint_name IN ('fk_recommendation_turn_owner',"
                    + "'ck_recommendation_schema_version','ck_recommendation_constraints_object','ck_recommendation_result_object')")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
                    + "AND table_name='conversation_recommendation' AND column_name IN ('turn_id','conversation_id','user_id','schema_version','constraints','result','created_at')")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(7);
            }
        }
    }
}
