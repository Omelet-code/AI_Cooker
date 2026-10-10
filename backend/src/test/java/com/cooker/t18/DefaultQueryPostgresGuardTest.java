package com.cooker.t18;

import static org.assertj.core.api.Assertions.*;
import com.cooker.evaluation.transport.*;
import com.cooker.evaluation.transport.SyncModelTransport.Lane;
import com.cooker.retrieval.service.*;
import com.cooker.retrieval.service.impl.QueryEmbeddingServiceImpl;
import com.cooker.retrieval.dto.RecipeSearchCriteria;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 临时PG中实际默认query调用，不替换QueryEmbeddingService，不接宿主库。 */
@SpringBootTest(properties={"cooker.evaluation.http.enabled=true","cooker.agent.execution-enabled=false"})
@Import(DefaultQueryPostgresGuardTest.Config.class)
@Testcontainers
class DefaultQueryPostgresGuardTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("t18_b1_isolated").withUsername("root").withPassword("test-only");
    static final LocalSupplier supplier;
    static final Path temp;
    static final EvaluationBatch batch;
    static {try{supplier=new LocalSupplier();temp=Files.createTempDirectory("t18-b1-pg-");batch=new EvaluationBatch("fake-pg","fake-approval",temp.resolve("batch.jsonl"),4,4,6,6);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
        r.add("cooker.retrieval.index.api-key",()->"fake-b1-key");r.add("cooker.retrieval.index.endpoint",()->supplier.uri("/query").toString());
    }
    @TestConfiguration static class Config{
        @Bean @Primary SyncModelTransport testBoundTransport(){return DefaultSyncModelTransport.controlled(batch);}
    }
    @Autowired QueryEmbeddingService query;
    @Autowired RecipeSearchService search;
    @Autowired JdbcTemplate jdbc;
    @Test void 生产默认query融合空资格零调用和降级后停止()throws Exception{
        assertThat(query).isInstanceOf(QueryEmbeddingServiceImpl.class);
        jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,visibility) VALUES(1001,'SYSTEM_HOWTOCOOK','b1-fixture','PUBLIC')");
        jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(1001,1001,'fixture',?,'# 番茄炒蛋','SUCCESS')","a".repeat(64));
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,estimated_minutes) VALUES(1001,1001,1001,'番茄炒蛋','vegetable_dish',10)");
        jdbc.update("""
            INSERT INTO recipe_search_document(recipe_id,source_document_id,source_type,visibility,chunk_type,chunk_no,content,content_hash,
              document_template_version,embedding_model,embedding_dimension,embedding,embedding_status)
            VALUES(1001,1001,'SYSTEM_HOWTOCOOK','PUBLIC','overview',0,'番茄炒蛋',?,1,'text-embedding-v4',1024,CAST(? AS vector),'READY')
            ""","b".repeat(64),"[1,"+"0,".repeat(1022)+"0]");
        assertThat(search.search(criteria(1))).isEmpty();assertThat(batch.methods(Lane.QUERY)).isZero();assertThat(supplier.received).hasValue(0);
        var results=search.search(criteria(null));assertThat(results).hasSize(1);assertThat(results.getFirst().vectorRank()).isNotNull();assertThat(results.getFirst().lexicalRank()).isNotNull();
        assertThat(batch.http(Lane.QUERY)).isEqualTo(1);assertThat(supplier.received).hasValue(1);
        supplier.status=401;var fallback=search.search(criteria(null));assertThat(fallback).hasSize(1);assertThat(fallback.getFirst().vectorRank()).isNull();
        assertThat(batch.stopped()).isTrue();assertThat(batch.http(Lane.QUERY)).isEqualTo(2);
        assertThatThrownBy(()->search.search(criteria(null))).isInstanceOf(EvaluationBatch.Stopped.class);assertThat(supplier.received).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(15);
    }
    RecipeSearchCriteria criteria(Integer minutes){return new RecipeSearchCriteria("番茄",10,List.of(),minutes,false,null,List.of(),null);}
    @AfterAll static void close()throws Exception{supplier.close();batch.close();Files.deleteIfExists(temp.resolve("batch.jsonl"));Files.deleteIfExists(temp);}
}
