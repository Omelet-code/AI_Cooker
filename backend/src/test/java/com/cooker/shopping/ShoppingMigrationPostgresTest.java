package com.cooker.shopping;

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

/** 新V13升级与关系约束真反例；不以唯一键错误代替归属FK。 */
@Testcontainers
class ShoppingMigrationPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_t16_migration").withUsername("root").withPassword("test-only");
    @Test void V12升级执行V13及V14旧快照保留复合归属数量状态与级联() {
        var config=Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());assertThat(config.target("12").load().migrate().migrationsExecuted).isEqualTo(12);
        var db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES(1,'shopping_a','test-only'),(2,'shopping_b','test-only')");db.update("INSERT INTO conversation(id,user_id,title) VALUES(1,1,'原餐'),(2,2,'其他餐')");db.update("INSERT INTO meal_session(id,conversation_id,user_id,status,view_json) VALUES(10,1,1,'SELECTED','{\"旧字段\":true}'),(20,2,2,'SELECTED','{}')");db.update("INSERT INTO meal_option(id,meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(100,10,1,1,1,1,'{}'),(200,20,2,2,1,1,'{}')");
        var before=db.queryForList("SELECT row_to_json(m)::text FROM meal_session m ORDER BY id");var full=Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load();assertThat(full.migrate().migrationsExecuted).isEqualTo(3);assertThat(full.migrate().migrationsExecuted).isZero();assertThat(db.queryForList("SELECT row_to_json(m)::text FROM meal_session m ORDER BY id")).isEqualTo(before);
        assertThat(header(db,500,1,1,10,1,100)).isEqualTo(1);
        error(()->header(db,501,2,2,20,1,100),"23503","fk_shopping_option_owner");error(()->header(db,502,1,1,10,2,100),"23503","fk_shopping_option_owner");
        error(()->item(db,500,2,"1","1","NEED_BUY"),"23503","fk_shopping_item_owner");
        error(()->item(db,500,1,null,null,"NEED_BUY"),"23514",null);error(()->item(db,500,1,"1",null,"HAVE_ENOUGH"),"23514",null);error(()->item(db,500,1,"NaN","NaN","NEED_BUY"),"23514",null);
        assertThat(item(db,500,1,null,null,"QUANTITY_PENDING")).isEqualTo(1);assertThat(db.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_name='shopping_list_item' AND column_name='required_amount'",Integer.class)).isEqualTo(8);
        db.update("DELETE FROM conversation WHERE id=1");assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM shopping_list_item",Integer.class)).isZero();
    }
    private int header(JdbcTemplate db,long id,long user,long conversation,long meal,int version,long option){return db.update("INSERT INTO shopping_list(id,user_id,conversation_id,meal_id,version,option_id,request_id,input_hash,source_fingerprint,status,budget_status,unknown_item_count,evidence_json,view_json) VALUES(?,?,?,?,?,?,gen_random_uuid(),repeat('a',64),repeat('b',64),'DRAFT','NOT_SET',1,'{}','{}')",id,user,conversation,meal,version,option);}
    private int item(JdbcTemplate db,long list,long user,String required,String shortage,String status){return db.update("INSERT INTO shopping_list_item(list_id,user_id,line_order,item_key,unit_code,optional,required_amount,shortage_amount,status) VALUES(?,?,1,'test-line','G',false,CAST(? AS numeric),CAST(? AS numeric),?)",list,user,required,shortage,status);}
    private void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code,String constraint){assertThatThrownBy(action).satisfies(e->{Throwable root=e;while(root.getCause()!=null)root=root.getCause();assertThat(root).isInstanceOf(PSQLException.class);var pg=(PSQLException)root;assertThat(pg.getSQLState()).isEqualTo(code);if(constraint!=null)assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);});}
}
