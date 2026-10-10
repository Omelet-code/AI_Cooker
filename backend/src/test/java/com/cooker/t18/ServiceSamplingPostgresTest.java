package com.cooker.t18;

import static org.assertj.core.api.Assertions.*;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchCriteria;
import com.cooker.retrieval.service.*;
import com.cooker.retrieval.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** 实际生产Service/资格SQL/RRF；模型传输替身。隔离fixture不是372菜新库，不评质量。 */
@SpringBootTest
@Import(ServiceSamplingPostgresTest.Config.class)
@Testcontainers
class ServiceSamplingPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer(
        DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("t18_b0_isolated").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl); r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword);
        r.add("cooker.retrieval.index.api-key",()->"");
        r.add("cooker.agent.execution-enabled",()->false);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Probe queryProbe() { return new Probe(); }
    }
    static final class Probe implements QueryEmbeddingService {
        SamplingLedger ledger; LocalSupplier supplier; String action; String mode;
        void start(SamplingLedger l,LocalSupplier s,String a) {ledger=l;supplier=s;action=a;mode="EMPTY_ELIGIBILITY";l.check();}
        @Override public float[] embed(String query) {
            var ticket=ledger.reserve(action,SamplingLedger.Lane.QUERY);
            mode="QUERY_ATTEMPT";
            var props=new RetrievalIndexProperties(); props.setApiKey("fake-t18-only");props.setEndpoint(supplier.uri("/query").toString());
            try {
                var model=new DashScopeEmbeddingModel(GuardedLocalTransport.client(supplier.uri("/query"),ledger,ticket),new ObjectMapper(),props);
                float[] result=model.embedQuery(query);
                ledger.finish(ticket,SamplingLedger.State.SUCCEEDED);mode="HYBRID";return result;
            } catch(RuntimeException e) {
                if(ticket.state()==SamplingLedger.State.SENT) ledger.finish(ticket,SamplingLedger.State.UNKNOWN);
                ledger.stop();mode="LEXICAL_FALLBACK";
                // 保留生产Service原有降级逻辑，再由采样观察拒绝把它算混合成功。
                throw new EmbeddingProviderException("替身调用失败，采样停止");
            }
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired RecipeSearchService search;
    @Autowired Probe probe;
    @TempDir Path dir;
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE recipe_source CASCADE");
        recipe(1001,"番茄炒蛋",10,"vegetable_dish",false);
        recipe(1002,"厨具未知番茄",null,"vegetable_dish",false);
        recipe(1003,"私有番茄",10,"vegetable_dish",true);
        recipe(66,"戚风蛋糕",120,"dessert",false);
        jdbc.update("INSERT INTO cooking_tool(id,canonical_name) VALUES(1001,'烤箱') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO recipe_tool(recipe_id,tool_id,optional) VALUES(66,1001,false)");
        jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES(1001,'鸡蛋','REVIEWED') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,raw_line,sort_order) VALUES(1001,1001,'鸡蛋','鸡蛋',1)");
    }
    @Test void 真Service两路及权限时间分类排除硬过滤() throws Exception {
        try(var s=new LocalSupplier();var l=ledger("filters")) {
            probe.start(l,s,"Q01");var result=search.search(criteria("番茄",null,null,null,null,List.of()));
            assertThat(result).extracting(r->r.recipeId()).contains(1001L).doesNotContain(1003L);
            assertThat(result).anySatisfy(r->{assertThat(r.lexicalRank()).isNotNull();assertThat(r.vectorRank()).isNotNull();});
            assertThat(probe.mode).isEqualTo("HYBRID");assertThat(s.lastTextType).isEqualTo("query");
            probe.start(l,s,"excluded");var filtered=search.search(criteria("番茄",15,false,null,null,List.of("鸡蛋")));
            assertThat(filtered).isEmpty();assertThat(probe.mode).isEqualTo("EMPTY_ELIGIBILITY");
            assertThat(l.http(SamplingLedger.Lane.QUERY)).isEqualTo(1);
            probe.start(l,s,"category");assertThat(search.search(criteria("番茄",null,null,List.of("soup"),null,List.of()))).isEmpty();
            assertThat(s.received).hasValue(1);
        }
    }
    @Test void Q19空资格零调用且Q20拒绝普通烤箱但未知记录可能放行() throws Exception {
        try(var s=new LocalSupplier();var l=ledger("edges")) {
            probe.start(l,s,"Q19");assertThat(search.search(criteria("牛肉菜一分钟内做好",1,false,null,null,List.of()))).isEmpty();
            assertThat(probe.mode).isEqualTo("EMPTY_ELIGIBILITY");assertThat(l.methods(SamplingLedger.Lane.QUERY)).isZero();
            probe.start(l,s,"Q20-audit");var result=search.search(criteria("星际量子烤箱",null,null,null,List.of("星际量子烤箱"),List.of()));
            assertThat(result).extracting(r->r.recipeId()).doesNotContain(66L).contains(1002L);
            assertThat(s.received).hasValue(1);
            // SQL未记录厨具时会允许通过；只报告已知限制，不把未知偷换为满足指定工具。
        }
    }
    @Test void 实际失败词法降级可识别且后续动作零发送() throws Exception {
        try(var s=new LocalSupplier();var l=ledger("fallback")) {
            s.status=401;probe.start(l,s,"Q01");
            var lexical=search.search(criteria("番茄",null,null,null,null,List.of()));
            assertThat(lexical).isNotEmpty();assertThat(probe.mode).isEqualTo("LEXICAL_FALLBACK");
            assertThat(l.stopped()).isTrue();assertThat(s.received).hasValue(1);
            assertThatThrownBy(()->probe.start(l,s,"Q02")).isInstanceOf(SamplingLedger.Stopped.class);
            assertThatThrownBy(()->l.reserve("guide",SamplingLedger.Lane.LLM)).isInstanceOf(SamplingLedger.Stopped.class);
            assertThat(s.received).hasValue(1);
        }
    }
    @Test void 冻结十八题真实Service接线只跑替身且不追加排除题() throws Exception {
        var json=new ObjectMapper().readTree(getClass().getResourceAsStream("/t18/retrieval-targets-v1.json"));
        int n=0;
        try(var s=new LocalSupplier();var l=ledger("eighteen")) {
            for(var q:json.path("queries")) {
                if(q.path("scoringMode").asText().equals("EXCLUDED"))continue;
                var c=q.path("conditions");probe.start(l,s,q.path("id").asText());
                search.search(criteria(q.path("query").asText(),c.has("maxCookingMinutes")?c.path("maxCookingMinutes").asInt():null,
                    c.has("includeUnknownCookingTime")?c.path("includeUnknownCookingTime").asBoolean():null,
                    strings(c,"categoryCodes"),strings(c,"availableToolNames"),Optional.ofNullable(strings(c,"excludedIngredientNames")).orElse(List.of())));
                n++;
            }
            assertThat(n).isEqualTo(18);assertThat(l.methods(SamplingLedger.Lane.LLM)).isZero();
            assertThat(l.methods(SamplingLedger.Lane.QUERY)).isLessThanOrEqualTo(17).isEqualTo(l.http(SamplingLedger.Lane.QUERY));
            assertThat(s.received.get()).isEqualTo(l.http(SamplingLedger.Lane.QUERY));
        }
    }
    private List<String> strings(com.fasterxml.jackson.databind.JsonNode c,String key) {
        if(!c.has(key))return null;var list=new ArrayList<String>();c.path(key).forEach(v->list.add(v.asText()));return list;
    }
    private SamplingLedger ledger(String name)throws Exception {return new SamplingLedger(name,dir.resolve(name+".jsonl"),18,18,0,0);}
    private RecipeSearchCriteria criteria(String query,Integer minutes,Boolean unknown,List<String> cats,List<String> tools,List<String> excluded) {
        return new RecipeSearchCriteria(query,10,cats,minutes,Boolean.TRUE.equals(unknown),tools,excluded,null);
    }
    private void recipe(long id,String name,Integer minutes,String category,boolean privateRecipe) {
        jdbc.update("INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES(?,?,?,?,?)",id,
            privateRecipe?"USER_CUSTOM":"SYSTEM_HOWTOCOOK","t18-isolated-"+id,privateRecipe?7L:null,privateRecipe?"PRIVATE":"PUBLIC");
        jdbc.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(?,?,?,?,?,?)",id,id,"fixture-only","a".repeat(64),"# 隔离菜谱","SUCCESS");
        jdbc.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,estimated_minutes) VALUES(?,?,?,?,?,?)",id,id,id,name,category,minutes);
        jdbc.update("""
            INSERT INTO recipe_search_document(recipe_id,source_document_id,source_type,owner_user_id,visibility,chunk_type,
              chunk_no,content,content_hash,document_template_version,embedding_model,embedding_dimension,embedding,embedding_status)
            VALUES(?,?,?,?,?,'overview',0,?, ?,1,'text-embedding-v4',1024,CAST(? AS vector),'READY')
            """,id,id,privateRecipe?"USER_CUSTOM":"SYSTEM_HOWTOCOOK",privateRecipe?7L:null,privateRecipe?"PRIVATE":"PUBLIC",
            name,"b".repeat(64),"[1,"+"0,".repeat(1022)+"0]");
    }
}
