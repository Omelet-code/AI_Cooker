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

/** 验证从 V7 升级到当前版本时保留现有用户与凭证数据，并创建会话表及所有者约束。 */
@Testcontainers(disabledWithoutDocker = true)
class ConversationMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t13_v8_migration_test").withUsername("root").withPassword("test-only");

    @Test void 从V7升级V12且保留既有用户和凭证() throws Exception {
        Flyway v7 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("7")).load();
        assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user(username,password_hash) VALUES ('t13_v7_user','test-only-hash')");
            statement.executeUpdate("INSERT INTO user_llm_credential(user_id,ciphertext,iv,algorithm_version,key_version) "
                    + "SELECT id,decode('00112233445566778899aabbccddeeff','hex'),decode('00112233445566778899aabb','hex'),1,1 "
                    + "FROM app_user WHERE username='t13_v7_user'");
        }

        Flyway upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        // 当前完整基线从V7新增V8/V9/V10/V11/V12/V13/V14，不能沿用旧迁移条数的预期。
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(8);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT (SELECT count(*) FROM app_user WHERE username='t13_v7_user'), "
                    + "(SELECT count(*) FROM user_llm_credential c JOIN app_user u ON u.id=c.user_id WHERE u.username='t13_v7_user'), "
                    + "(SELECT count(*) FROM flyway_schema_history WHERE success)")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.getInt(3)).isEqualTo(15);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                    + "AND table_name IN ('conversation','conversation_turn','chat_message')")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema='public' AND constraint_name IN ('fk_turn_conversation_owner','fk_message_turn_owner')")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }
}
