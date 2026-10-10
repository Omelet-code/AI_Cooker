package com.cooker.conversation;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V10→V11保留原数据，准确验证23503及合法正例，不靠业务Mock证明关系约束。 */
@Testcontainers
class MealMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_u3_migration").withUsername("root").withPassword("test-only");
    @Test void V10升级V11保留旧数据重复零且复合FK及活动唯一真实拒绝() {
        var db=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var before=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("10").load();assertThat(before.migrate().migrationsExecuted).isEqualTo(10);
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES(1,'meal_a','test-only'),(2,'meal_b','test-only')");db.update("INSERT INTO conversation(id,user_id,title) VALUES(1,1,'原会话'),(2,2,'他人会话')");db.update("INSERT INTO user_profile(user_id,default_dining_count) VALUES(1,3)");
        var old=db.queryForList("SELECT row_to_json(t)::text FROM conversation t ORDER BY id");
        var upgraded=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target("11").load();assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);assertThat(upgraded.migrate().migrationsExecuted).isZero();assertThat(db.queryForList("SELECT row_to_json(t)::text FROM conversation t ORDER BY id")).isEqualTo(old);assertThat(db.queryForObject("SELECT default_dining_count FROM user_profile WHERE user_id=1",Integer.class)).isEqualTo(3);
        assertThat(db.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success ORDER BY version::integer",Integer.class)).containsExactly(1,2,3,4,5,6,7,8,9,10,11);
        sqlError(()->db.update("INSERT INTO meal_session(conversation_id,user_id,status,view_json) VALUES(1,2,'PENDING','{}')"),"23503","fk_meal_conversation_owner");
        db.update("INSERT INTO meal_session(id,conversation_id,user_id,status,view_json) VALUES(10,1,1,'PENDING','{}'),(20,2,2,'PENDING','{}')");
        sqlError(()->db.update("INSERT INTO meal_action(meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,deadline_at) VALUES(10,2,2,1,gen_random_uuid(),'CREATE','PENDING','x','{}',repeat('a',64),now()+interval '1 minute')"),"23503","fk_meal_action_owner");
        sqlError(()->db.update("INSERT INTO meal_option(meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(10,2,2,1,1,'{}')"),"23503","fk_meal_option_owner");
        db.update("INSERT INTO meal_option(id,meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(100,10,1,1,1,1,'{}'),(200,20,2,2,1,1,'{}'),(101,10,1,1,2,1,'{}')");
        sqlError(()->db.update("UPDATE meal_session SET selected_option_id=200 WHERE id=10"),"23503","fk_meal_selected_option_owner");sqlError(()->db.update("UPDATE meal_session SET selected_option_id=101 WHERE id=10"),"23503","fk_meal_selected_option_owner");assertThat(db.update("UPDATE meal_session SET selected_option_id=100,status='SELECTED' WHERE id=10")).isEqualTo(1);
        String action="INSERT INTO meal_action(meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,deadline_at) VALUES(10,1,1,1,gen_random_uuid(),'CREATE','PENDING','x','{}',repeat('a',64),now()+interval '1 minute')";assertThat(db.update(action)).isEqualTo(1);sqlError(()->db.update(action),"23505","uq_meal_active_action");
    }
    private void sqlError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String state,String constraint){assertThatThrownBy(action).satisfies(error->{Throwable root=error;while(root.getCause()!=null)root=root.getCause();assertThat(root).isInstanceOf(PSQLException.class);var pg=(PSQLException)root;assertThat(pg.getSQLState()).isEqualTo(state);assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);});}
}
