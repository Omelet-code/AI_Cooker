package com.cooker.conversation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 明确V11→V12与原数据字节保留；验证实际23503约束和合法指导。 */
@Testcontainers
class MealGuideMigrationPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_u4_migration").withUsername("root").withPassword("test-only");
    @Test void 升级保留选定餐单GUIDE共用执行位复合FK正反例() {
        var db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(11);
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES(1,'guide_a','test-only'),(2,'guide_b','test-only')");db.update("INSERT INTO conversation(id,user_id,title) VALUES(1,1,'原餐'),(2,2,'其他餐')");db.update("INSERT INTO meal_session(id,conversation_id,user_id,status,view_json) VALUES(10,1,1,'SELECTED','{\"旧字段\":true}'),(20,2,2,'SELECTED','{}')");db.update("INSERT INTO meal_option(id,meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(100,10,1,1,1,1,'{}'),(101,10,1,1,2,1,'{}'),(200,20,2,2,1,1,'{}')");db.update("UPDATE meal_session SET selected_option_id=100 WHERE id=10");
        var old=db.queryForList("SELECT row_to_json(m)::text FROM meal_session m ORDER BY id");var upgraded=flyway(null);assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(4);assertThat(upgraded.migrate().migrationsExecuted).isZero();assertThat(db.queryForList("SELECT row_to_json(m)::text FROM meal_session m ORDER BY id")).isEqualTo(old);assertThat(db.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",Integer.class)).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
        db.update("INSERT INTO meal_action(id,meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,deadline_at,started_at,finished_at) VALUES(1000,10,1,1,1,gen_random_uuid(),'GUIDE','SUCCEEDED','','{}',repeat('a',64),now()+interval '1 minute',now(),now()),(2000,20,2,2,1,gen_random_uuid(),'GUIDE','SUCCEEDED','','{}',repeat('b',64),now()+interval '1 minute',now(),now())");
        sqlError(()->insert(db,10,1,1,1,200,1000),"23503","fk_guide_option_owner");sqlError(()->insert(db,10,1,1,1,100,2000),"23503","fk_guide_action_owner");sqlError(()->insert(db,10,1,1,2,100,1000),"23503","fk_guide_option_owner");assertThat(insert(db,10,1,1,1,100,1000)).isEqualTo(1);
        db.update("INSERT INTO meal_action(meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,deadline_at) VALUES(10,1,1,1,gen_random_uuid(),'GUIDE','PENDING','','{}',repeat('a',64),now()+interval '1 minute')");sqlError(()->db.update("INSERT INTO meal_action(meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,deadline_at) VALUES(10,1,1,2,gen_random_uuid(),'REVISE','PENDING','','{}',repeat('a',64),now()+interval '1 minute')"),"23505","uq_meal_active_action");
    }
    private int insert(JdbcTemplate db,long meal,long conversation,long user,int version,long option,long action){return db.update("INSERT INTO meal_guide(meal_id,conversation_id,user_id,version,option_id,action_id,persona_version,prompt_version,view_json) VALUES(?,?,?,?,?,?,'xiaochu-v1','meal-guide-v1','{}')",meal,conversation,user,version,option,action);}
    private Flyway flyway(String target){var config=Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());if(target!=null)config.target(target);return config.load();}
    private void sqlError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,String state,String constraint){assertThatThrownBy(operation).satisfies(e->{Throwable root=e;while(root.getCause()!=null)root=root.getCause();assertThat(root).isInstanceOf(PSQLException.class);var pg=(PSQLException)root;assertThat(pg.getSQLState()).isEqualTo(state);assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);});}
}
