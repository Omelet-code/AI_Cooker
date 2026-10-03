package com.cooker.identity;

import static org.assertj.core.api.Assertions.*;

import com.cooker.CookerApplication;
import com.cooker.identity.dto.SaveDiningProfileRequest;
import com.cooker.identity.service.UserDiningProfileService;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真实PG空库、旧版本升级、约束与隔离应用重建；每个场景使用新的临时数据库。 */
@Testcontainers
class DiningProfileMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_u1_pg").withUsername("root").withPassword("test-only");

    @Test void 空库迁移至V10且没有自动初始化档案() {
        String url = newDatabase();
        assertThat(flyway(url, null).migrate().migrationsExecuted).isEqualTo(10);
        JdbcTemplate db = jdbc(url);
        assertThat(db.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(flyway(url, null).migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("SELECT max(version::integer) FROM flyway_schema_history", Integer.class)).isEqualTo(10);
        assertThat(db.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='user_profile'", Integer.class)).isEqualTo(4);
    }

    @Test void V7升级V10保留用户收藏做菜续期与虚构凭证() {
        upgradePreservesData(false, "7");
    }

    @Test void V9fixture原字节哈希保留且生产原文一致并升级V10保留旧业务快照() throws Exception {
        assertFixtureHash("V8__create_conversation_tables.sql", "6c73a19428aa7c4a7ce3de3b8c6b1d44528cd19892ac3aead7011b91f057c587");
        assertFixtureHash("V9__create_conversation_recommendation.sql", "399445ed6306760187cfe4d5d4b45847e628b1052a4aad057eba3abf9e1b8943");
        upgradePreservesData(true, "9");
    }

    @Test void 主键正数外键级联删除与并发UPSERT由真实PG约束() throws Exception {
        String url = newDatabase();
        flyway(url, null).migrate();
        JdbcTemplate db = jdbc(url);
        long user = db.queryForObject("INSERT INTO app_user(username,password_hash) VALUES('u1_constraints','fake') RETURNING id", Long.class);
        db.update("INSERT INTO user_profile(user_id,default_dining_count) VALUES (?,null)", user);
        assertSqlError(() -> db.update("INSERT INTO user_profile(user_id,default_dining_count) VALUES (?,2)", user), "23505", "user_profile_pkey");
        for (int invalid : new int[] {0, -1})
            assertSqlError(() -> db.update("UPDATE user_profile SET default_dining_count=? WHERE user_id=?", invalid, user), "23514", "ck_user_profile_positive_count");
        // 使用从未插入的用户键和合法正数，确保不会被主键/正数CHECK遮挡外键错误。
        assertSqlError(() -> db.update("INSERT INTO user_profile(user_id,default_dining_count) VALUES (9223372036854775807,2)"), "23503", "fk_user_profile_user");
        try (var context = start(url)) {
            var service = context.getBean(UserDiningProfileService.class);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch start = new CountDownLatch(1);
                var first = executor.submit(() -> { start.await(); return saveAs(service, user, 2); });
                var second = executor.submit(() -> { start.await(); return saveAs(service, user, 7); });
                start.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(2);
                assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(7);
            }
        }
        assertThat(db.queryForObject("SELECT count(*) FROM user_profile WHERE user_id=?", Integer.class, user)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT default_dining_count FROM user_profile WHERE user_id=?", Integer.class, user)).isIn(2, 7);
        db.update("DELETE FROM app_user WHERE id=?", user);
        assertThat(db.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();
    }

    @Test void 同一临时PG关闭并重建完整应用上下文后人数仍保留() {
        String url = newDatabase();
        long user;
        try (var first = start(url)) {
            var db = first.getBean(JdbcTemplate.class);
            user = db.queryForObject("INSERT INTO app_user(username,password_hash) VALUES('u1_restart','fake') RETURNING id", Long.class);
            assertThat(saveAs(first.getBean(UserDiningProfileService.class), user, 6)).isEqualTo(6);
        }
        // 第一个完整上下文及其连接池已关闭，第二个上下文连接同一隔离PG。
        try (var second = start(url)) {
            assertThat(second.getBean(UserDiningProfileService.class).readForUser(user).defaultDiningCount()).isEqualTo(6);
            assertThat(second.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isEqualTo(1);
        }
    }

    private void upgradePreservesData(boolean fixture, String target) {
        String url = newDatabase();
        assertThat(flyway(url, target).migrate().migrationsExecuted).isEqualTo(Integer.parseInt(target));
        JdbcTemplate db = jdbc(url);
        seed(db, fixture);
        Map<String, Object> before = snapshot(db, fixture);
        // V7之后真实执行V8/V9/V10；V9之后只执行V10，不再重复加载同版本fixture。
        assertThat(flyway(url, null).migrate().migrationsExecuted).isEqualTo(10 - Integer.parseInt(target));
        assertThat(snapshot(db, fixture)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();
        assertThat(flyway(url, null).info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(db.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",
                Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(flyway(url, null).migrate().migrationsExecuted).isZero();
    }

    private void seed(JdbcTemplate db, boolean fixture) {
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES(10,'u1_upgrade','fake-hash')");
        db.update("INSERT INTO recipe_source(id,source_type,source_key,visibility) VALUES(10,'SYSTEM_HOWTOCOOK','u1_test','PUBLIC')");
        db.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(10,10,'test',repeat('a',64),'测试原文','SUCCESS')");
        db.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES(10,10,10,'测试菜谱','test')");
        db.update("INSERT INTO favorite_recipe(user_id,recipe_id) VALUES(10,10)");
        db.update("INSERT INTO recipe_cook_record(user_id,recipe_id,idempotency_key,rating) VALUES(10,10,?::uuid,4)", UUID.randomUUID().toString());
        db.update("INSERT INTO user_refresh_token(user_id,token_hash,expires_at,absolute_expires_at,remember_me) VALUES(10,repeat('b',64),now()+interval '1 day',now()+interval '2 days',false)");
        // 仅测试长度约束的虚构字节，不读取、解密或保存真实模型Key。
        db.update("INSERT INTO user_llm_credential(user_id,ciphertext,iv,algorithm_version,key_version) VALUES(10,decode(repeat('ab',16),'hex'),decode(repeat('cd',12),'hex'),1,1)");
        if (fixture) {
            db.update("INSERT INTO conversation(id,user_id,title) VALUES(10,10,'升级测试')");
            db.update("INSERT INTO conversation_turn(id,conversation_id,user_id,request_id,input_hash,turn_no,status,started_at,finished_at,deadline_at) VALUES(10,10,10,?::uuid,repeat('a',64),1,'SUCCEEDED',now(),now(),now()+interval '1 minute')", UUID.randomUUID().toString());
            db.update("INSERT INTO chat_message(conversation_id,user_id,turn_id,role,content,sequence) VALUES(10,10,10,'USER','测试消息',1)");
            db.update("INSERT INTO conversation_recommendation(turn_id,conversation_id,user_id,constraints,result) VALUES(10,10,10,'{}'::jsonb,'{}'::jsonb)");
        }
    }

    private Map<String, Object> snapshot(JdbcTemplate db, boolean fixture) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        var tables = new java.util.ArrayList<>(java.util.List.of("app_user", "recipe_source", "recipe_source_document", "recipe", "favorite_recipe", "recipe_cook_record", "user_refresh_token", "user_llm_credential"));
        if (fixture) tables.addAll(java.util.List.of("conversation", "conversation_turn", "chat_message", "conversation_recommendation"));
        // 表名是测试内部常量；逐行JSON保留所有列，包括时间与虚构密文字节。
        for (String table : tables) snapshot.put(table, db.queryForList("SELECT row_to_json(t)::text AS row FROM " + table + " t ORDER BY 1"));
        return snapshot;
    }

    private Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, "root", "test-only");
        // fixture只作原字节核验；整合基线的升级场景统一使用真实生产迁移。
        config.locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }
    private String newDatabase() {
        String name = "u1_" + UUID.randomUUID().toString().replace("-", "");
        jdbc(POSTGRES.getJdbcUrl()).execute("CREATE DATABASE " + name);
        return POSTGRES.getJdbcUrl().replace("/cooker_u1_pg", "/" + name);
    }
    private JdbcTemplate jdbc(String url) { return new JdbcTemplate(new DriverManagerDataSource(url, "root", "test-only")); }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(CookerApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + url, "--spring.datasource.username=root",
                "--spring.datasource.password=test-only", "--cooker.identity.jwt-secret=test-only-u1-restart-secret-0123456789",
                "--cooker.identity.llm-credential.master-key=", "--cooker.retrieval.index.api-key=",
                "--cooker.t6.enabled=false", "--cooker.recipe-import.enabled=false");
    }
    private Integer saveAs(UserDiningProfileService service, long userId, int count) {
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of()));
            return service.saveCurrentUser(new SaveDiningProfileRequest(count)).defaultDiningCount();
        } finally { SecurityContextHolder.clearContext(); }
    }
    private void assertSqlError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state, String constraint) {
        assertThatThrownBy(action).satisfies(error -> {
            Throwable root = error;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(PSQLException.class);
            var pg = (PSQLException)root;
            assertThat(pg.getSQLState()).isEqualTo(state);
            assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        });
    }
    private void assertFixtureHash(String file, String expected) throws Exception {
        try (var source = getClass().getResourceAsStream("/identity/u1/v9-fixture/" + file);
                var production = getClass().getResourceAsStream("/db/migration/" + file)) {
            assertThat(source).as("fixture存在").isNotNull();
            assertThat(production).as("生产迁移存在").isNotNull();
            // 原始字节哈希，包含原换行；不做CRLF/LF归一化。
            byte[] original = source.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original))).isEqualTo(expected);
            // Git在Windows将生产文件检出为CRLF，fixture仍为LF；只在原文比对时适配换行。
            // fixture哈希仍直接核验原字节，绝不修改fixture或生产迁移文件。
            assertThat(new String(production.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n"))
                    .isEqualTo(new String(original, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
