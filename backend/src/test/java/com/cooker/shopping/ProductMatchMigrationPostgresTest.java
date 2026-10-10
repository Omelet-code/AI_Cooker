package com.cooker.shopping;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V14真升级与复合FK反例，不以唯一键错误代替归属核验。 */
@Testcontainers
class ProductMatchMigrationPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_t17_migration").withUsername("root").withPassword("test-only");
    @Test void V13升级只执行V14需求保留归属约束及级联() {
        assertThat(Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).target("13").load().migrate().migrationsExecuted).isEqualTo(13);var db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES(1,'t17_a','test-only'),(2,'t17_b','test-only')");db.update("INSERT INTO conversation(id,user_id,title) VALUES(1,1,'采购测试')");db.update("INSERT INTO meal_session(id,conversation_id,user_id,status,view_json) VALUES(10,1,1,'SELECTED','{}')");db.update("INSERT INTO meal_option(id,meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(100,10,1,1,1,1,'{}')");
        db.update("INSERT INTO shopping_list(id,user_id,conversation_id,meal_id,version,option_id,request_id,input_hash,source_fingerprint,status,budget_status,unknown_item_count,evidence_json,view_json) VALUES(500,1,1,10,1,100,gen_random_uuid(),repeat('a',64),repeat('b',64),'DRAFT','UNKNOWN',1,'{\"保留证据\":true}','{\"matchingStatus\":\"NOT_MATCHED\"}')");var before=db.queryForList("SELECT row_to_json(s)::text FROM shopping_list s");
        var full=Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).load();assertThat(full.migrate().migrationsExecuted).isEqualTo(2);assertThat(full.migrate().migrationsExecuted).isZero();assertThat(db.queryForList("SELECT row_to_json(s)::text FROM shopping_list s")).isEqualTo(before);
        header(db,600,1,500);error(()->header(db,601,2,500),"23503","fk_product_match_list_owner");
        error(()->db.update("INSERT INTO shopping_product_match_item(match_id,list_id,user_id,line_order,item_key,status) VALUES(600,500,2,1,'wrong-owner','NO_PRODUCT')"),"23503","fk_product_match_item_owner");
        error(()->db.update("INSERT INTO shopping_product_match_item(match_id,list_id,user_id,line_order,item_key,status) VALUES(600,500,1,1,'bad-matched','MATCHED')"),"23514",null);
        error(()->db.update("UPDATE shopping_product_match SET purchase_outlay='NaN'::numeric WHERE id=600"),"23514",null);
        error(()->group(db,2,"1","1"),"23503","fk_product_purchase_group_owner");error(()->group(db,1,"NaN","1"),"23514",null);error(()->group(db,1,"10","1.5"),"23514",null);assertThat(group(db,1,"10","1")).isEqualTo(1);
        db.update("INSERT INTO shopping_product_match_item(match_id,list_id,user_id,line_order,item_key,status) VALUES(600,500,1,1,'pending','QUANTITY_PENDING')");db.update("DELETE FROM shopping_list WHERE id=500");for(String table:new String[]{"shopping_product_match","shopping_product_match_item","shopping_product_purchase_group"})assertThat(db.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
    }
    private void header(JdbcTemplate db,long id,long user,long list){db.update("INSERT INTO shopping_product_match(id,user_id,list_id,request_id,input_hash,source_fingerprint,city_code,catalog_version,matching_status,known_subtotal,unknown_item_count,estimated_known_subtotal,estimated_unknown_item_count,budget_status,view_json) VALUES(?,?,?,gen_random_uuid(),repeat('a',64),repeat('b',64),'110000','test-v1','NO_MATCH',0,1,0,1,'UNKNOWN','{}')",id,user,list);}
    private int group(JdbcTemplate db,long user,String quantity,String count){return db.update("INSERT INTO shopping_product_purchase_group(match_id,list_id,user_id,group_key,product_id,unit_code,quantity,package_count,purchase_outlay) VALUES(600,500,?,'group','mock-a','G',CAST(? AS numeric),CAST(? AS numeric),10)",user,quantity,count);}
    private void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String state,String constraint){assertThatThrownBy(action).satisfies(e->{Throwable root=e;while(root.getCause()!=null)root=root.getCause();assertThat(root).isInstanceOf(PSQLException.class);var pg=(PSQLException)root;assertThat(pg.getSQLState()).isEqualTo(state);if(constraint!=null)assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);});}
}
