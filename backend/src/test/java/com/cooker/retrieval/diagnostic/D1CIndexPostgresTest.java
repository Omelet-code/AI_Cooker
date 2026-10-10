package com.cooker.retrieval.diagnostic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.cooker.recipe.diagnostic.RecipeProjectionContext;
import com.cooker.recipe.service.RecipeIndexProjectionService;
import com.cooker.retrieval.mapper.RecipeSearchDocumentMapper;
import com.cooker.retrieval.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真实临时PG＋替身百炼：不连接宿主5433，不注入真实Key或生成生产假向量。 */
@Testcontainers
class D1CIndexPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("d1c_isolated_test").withUsername("root").withPassword("test-only");
    @TempDir Path directory;
    JdbcTemplate jdbc; RecipeIndexProjectionService projection; RecipeSearchDocumentMapper mapper;
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();jdbc.update("DELETE FROM recipe_search_document");
        if(jdbc.queryForObject("SELECT count(*) FROM recipe",Long.class)==0) for(long id=1;id<=2;id++) {
            jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,visibility) VALUES (?,'SYSTEM_HOWTOCOOK',?,'PUBLIC')",id,"sample-"+id);
            jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES (?,?,'1.6.0-a0b14bd',?,'原文保留','SUCCESS')",id,id,"a".repeat(64));
            jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES (?,?,?,?,'meat_dish')",id,id,id,"鸡蛋菜"+id);
            jdbc.update("INSERT INTO recipe_step(recipe_id,step_no,content) VALUES (?,1,'煮熟鸡蛋')",id);
        }
        var session=DocumentIndexTool.session(source);projection=RecipeProjectionContext.create(session);session.getConfiguration().addMapper(RecipeSearchDocumentMapper.class);mapper=session.getMapper(RecipeSearchDocumentMapper.class);
    }
    @ParameterizedTest @ValueSource(ints={401,403,400,429})
    void 全局错误第一批即停止后续菜谱仍缺失且事实不变(int status) throws Exception {
        var props=BoundedIndexLedgerTest.properties();props.setApiKey("test-only");var plan=IndexPlan.build(DocumentIndexTool.DATABASE,props,projection.listCurrentRecipes());
        assertThat(plan.firstHttpBound()).isEqualTo(2);assertThat(plan.chunks()).isEqualTo(4);
        var client=RestClient.builder();var server=MockRestServiceServer.bindTo(client).build();server.expect(requestTo(props.getEndpoint())).andRespond(withStatus(HttpStatus.valueOf(status))
                .body(status==429 ? "{\"code\":\"Throttling.AllocationQuota\",\"message\":\"secret-not-persisted\"}" : "secret-not-persisted"));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) {ledger.begin("execute");var model=new DashScopeEmbeddingModel(client.build(),new ObjectMapper(),props,ledger);
            assertThatThrownBy(() -> new RecipeIndexServiceImpl(projection,new RecipeSearchDocumentBuilder(props),mapper,model,props,ledger).rebuildAll()).isInstanceOf(IndexRunStoppedException.class);
            assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("stopped",true);
            var audit=DocumentIndexTool.audit(plan,mapper,jdbc);assertThat(audit).containsEntry("failed",2).containsEntry("missing",2).containsEntry("ready",0);
        }server.verify();assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_source_document WHERE raw_markdown='原文保留'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT failure_message FROM recipe_search_document",String.class)).allMatch(message -> !message.contains("secret"));
    }
    @Test void 一次恢复仅失败块成功块零调用且PG向量全文索引有效() throws Exception {
        var props=BoundedIndexLedgerTest.properties();props.setApiKey("test-only");var plan=IndexPlan.build(DocumentIndexTool.DATABASE,props,projection.listCurrentRecipes());
        var client=RestClient.builder();var server=MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo(props.getEndpoint())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess(D1CDocumentHttpTest.response(2),MediaType.APPLICATION_JSON));
        var modelClient=client.build();
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) {ledger.begin("execute");var report=new RecipeIndexServiceImpl(projection,new RecipeSearchDocumentBuilder(props),mapper,new DashScopeEmbeddingModel(modelClient,new ObjectMapper(),props,ledger),props,ledger).rebuildAll();ledger.finish();
            assertThat(report.indexedChunks()).isEqualTo(2);assertThat(report.failedChunks()).isEqualTo(2);
        }server.verify();
        var recoveryClient=RestClient.builder();var recoveryServer=MockRestServiceServer.bindTo(recoveryClient).build();recoveryServer.expect(requestTo(props.getEndpoint())).andRespond(withSuccess(D1CDocumentHttpTest.response(2),MediaType.APPLICATION_JSON));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) {ledger.begin("recover");var report=new RecipeIndexServiceImpl(projection,new RecipeSearchDocumentBuilder(props),mapper,new DashScopeEmbeddingModel(recoveryClient.build(),new ObjectMapper(),props,ledger),props,ledger).rebuildAll();ledger.finish();
            assertThat(report.indexedChunks()).isEqualTo(2);assertThat(report.skippedChunks()).isEqualTo(2);
            assertThat(ledger.summary()).containsEntry("httpAttempts",3).containsEntry("maxSendsPerChunk",2);
            assertThat(DocumentIndexTool.audit(plan,mapper,jdbc)).containsEntry("ready",4).containsEntry("missing",0).containsEntry("badReadyVectors",0L).containsEntry("coveredRecipes",2L).containsEntry("nonemptyTsv",4L);
        }recoveryServer.verify();
        EmbeddingModel forbidden=mock(EmbeddingModel.class);when(forbidden.embed(anyList())).thenThrow(new AssertionError("禁止任何出站"));
        var reused=new RecipeIndexServiceImpl(projection,new RecipeSearchDocumentBuilder(props),mapper,forbidden,props,new IndexRunControl(){@Override public boolean allowCleanup(){return false;}}).rebuildAll();
        assertThat(reused.skippedChunks()).isEqualTo(4);verify(forbidden,never()).embed(anyList());
    }
    @Test void 第二行SQL失败保留第一行READY且禁止重新生成() throws Exception {
        var props=BoundedIndexLedgerTest.properties();props.setApiKey("test-only");var plan=IndexPlan.build(DocumentIndexTool.DATABASE,props,projection.listCurrentRecipes());
        jdbc.execute("CREATE FUNCTION d1c_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.chunk_type='steps' THEN RAISE EXCEPTION '测试后置写失败'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER d1c_test_fail BEFORE INSERT ON recipe_search_document FOR EACH ROW EXECUTE FUNCTION d1c_test_fail()");
        var client=RestClient.builder();var server=MockRestServiceServer.bindTo(client).build();server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess(D1CDocumentHttpTest.response(2),MediaType.APPLICATION_JSON));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) {ledger.begin("execute");
            assertThatThrownBy(() -> new RecipeIndexServiceImpl(projection,new RecipeSearchDocumentBuilder(props),mapper,new DashScopeEmbeddingModel(client.build(),new ObjectMapper(),props,ledger),props,ledger).rebuildAll()).hasMessageContaining("投影写入失败");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_search_document WHERE embedding_status='READY'",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_search_document WHERE embedding_status='FAILED'",Long.class)).isZero();
            assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("stopped",true);
        } finally {jdbc.execute("DROP TRIGGER d1c_test_fail ON recipe_search_document");jdbc.execute("DROP FUNCTION d1c_test_fail()");}
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) {assertThatThrownBy(() -> ledger.begin("recover")).hasMessageContaining("全局停止");}server.verify();
    }
}
