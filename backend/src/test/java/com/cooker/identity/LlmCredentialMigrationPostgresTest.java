package com.cooker.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 在独立空 PostgreSQL 中先停在 V6，再升级 V7 并验证原身份数据保留。 */
@Testcontainers(disabledWithoutDocker = true)
class LlmCredentialMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_t8_migration_test").withUsername("root").withPassword("test-only");

    @Test void 空库可迁移且V6升级V7不影响既有用户() {
        Flyway beforeV7 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).target(MigrationVersion.fromVersion("6")).load();
        assertThat(beforeV7.migrate().migrationsExecuted).isEqualTo(6);
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO app_user(username,password_hash) VALUES ('t8_upgrade_user','fake-hash')");
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError("测试库写入用户失败", e);
        }

        Flyway upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM app_user WHERE username='t8_upgrade_user'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='user_llm_credential'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(7);
                }
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError("测试库验证 V7 失败", e);
        }
    }
}
