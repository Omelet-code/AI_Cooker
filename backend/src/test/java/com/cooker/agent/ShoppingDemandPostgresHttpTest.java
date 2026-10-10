package com.cooker.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.cooker.agent.dto.*;
import com.cooker.agent.service.*;
import com.cooker.conversation.service.MealPersistenceService;
import com.cooker.identity.service.*;
import com.cooker.policy.*;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.retrieval.service.QueryEmbeddingService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 复用U3固定数据/浏览器助手，不继承旧测试；真U3、事实Service、认证HTTP及PG。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShoppingDemandPostgresHttpTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_t16_http").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("cooker.identity.jwt-secret",()->"test-only-u4-secret-01234567890123456789");r.add("cooker.identity.llm-credential.master-key",()->"");r.add("cooker.retrieval.index.api-key",()->"");r.add("cooker.agent.execution-enabled",()->true);r.add("cooker.t6.enabled",()->false);}
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MealPersistenceService store;
    @Autowired CurrentUserService current;@Autowired UserDiningProfileService profiles;@Autowired MealPolicy safety;@Autowired RecipeMealFactsService facts;
    @Autowired com.cooker.shopping.service.ShoppingService shopping;
    @Autowired com.cooker.conversation.mapper.ShoppingSourceMapper sourceMapper;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.cooker.shopping.mapper.ShoppingMapper shoppingMapper;
    @MockitoBean ModelGateway gateway;@MockitoBean QueryEmbeddingService embedding;@MockitoBean RecommendationClock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.cooker.retrieval.service.RecipeSearchService retrieval;
    final MealPlanningPostgresHttpTest f=new MealPlanningPostgresHttpTest();
    JsonNode selected,preview;String meal,option;
    @BeforeEach void setUp()throws Exception {
        f.port=port;f.json=json;f.db=db;f.store=store;f.current=current;f.profiles=profiles;f.policy=safety;f.gateway=gateway;f.embedding=embedding;f.clock=clock;f.initialize();
        db.update("UPDATE recipe_ingredient SET amount_min=100,amount_max=100 WHERE recipe_id=10");
        db.update("UPDATE recipe_ingredient SET amount_min=20,amount_max=30 WHERE recipe_id=11");
        db.update("UPDATE recipe_ingredient SET amount_min=NULL,amount_max=NULL,unit_code=NULL,unit_label=NULL WHERE recipe_id=12");
        db.update("UPDATE recipe_ingredient SET ingredient_id=NULL,raw_name='未归一材料' WHERE recipe_id=13");
        db.update("UPDATE recipe_ingredient SET optional=true WHERE recipe_id=20");
        f.choiceOverride.set("{\"options\":[{\"recipeIds\":[\"10\",\"11\",\"12\",\"13\",\"20\"],\"explanation\":\"口味搭配\"},{\"recipeIds\":[\"11\",\"12\",\"13\",\"14\",\"21\"],\"explanation\":\"方便选择\"}]}");
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();option=created.path("meal").path("options").get(0).path("id").asText();selected=created.path("meal").path("options").get(0);
        f.data(f.a.post(path()+"/selection",f.selectBody(UUID.randomUUID().toString(),1,option)),201);
        clearInvocations(gateway,embedding,retrieval);
        // 真实Service入口的错误直接由测试抛出，HTTP统一错误不泄漏内部异常。
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L,null,List.of()));
        try{shopping.preview(f.conversation,meal,1,option);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        preview=f.data(f.a.get(previewPath(1,option)),200);
    }
    @Test void 预览无写与档案变化不影响已选人数材料及语义摘要()throws Exception {
        assertThat(preview.path("schemaVersion").asInt()).isEqualTo(1);assertThat(preview.path("lines")).hasSize(5);assertThat(preview.path("source").path("diningCount").asInt()).isEqualTo(3);
        var before=db.queryForList("SELECT updated_at::text AS stamp,view_json::text FROM meal_session");int actions=db.queryForObject("SELECT count(*) FROM meal_action",Integer.class);
        f.data(f.a.put("/users/me/profile","{\"defaultDiningCount\":7}"),200);assertThat(f.data(f.a.get(previewPath(1,option)),200)).isEqualTo(preview);
        db.update("UPDATE meal_session SET view_json=jsonb_set(view_json,'{options,0,materials,recipes,0,materials,0,referenceAmount}',to_jsonb('150.0'::text)) WHERE id=?",Long.parseLong(meal));
        assertThat(f.data(f.a.get(previewPath(1,option)),200).path("source").path("sourceFingerprint")).isEqualTo(preview.path("source").path("sourceFingerprint"));
        assertThat(db.queryForObject("SELECT count(*) FROM meal_action",Integer.class)).isEqualTo(actions);assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT updated_at::text FROM meal_session WHERE id=?",String.class,Long.parseLong(meal))).isEqualTo(before.getFirst().get("stamp"));
    }
    @Test void 未知仍可采购草稿且足量已有不伪造原消耗零()throws Exception {
        var pending=create(body(UUID.randomUUID().toString(),"NEED_BUY",false),201);assertThat(pending.path("status").asText()).isEqualTo("DRAFT");assertThat(pending.path("items")).hasSize(5);
        for(var item:pending.path("items")){assertThat(item.path("shortageAmount").isNull()).isTrue();assertThat(item.path("status").asText()).isEqualTo("QUANTITY_PENDING");}
        assertThat(pending.path("costs").path("consumptionCost").isNull()).isTrue();assertThat(pending.path("costs").path("knownSubtotal").asText()).isEqualTo("0.00");assertThat(pending.path("costs").path("unknownItemCount").asInt()).isEqualTo(5);
        var enough=create(body(UUID.randomUUID().toString(),"HAVE_ENOUGH",false),201);assertThat(enough.path("status").asText()).isEqualTo("READY_FOR_MATCHING");for(var item:enough.path("items")){assertThat(item.path("shortageAmount").asText()).isEqualTo("0");assertThat(item.path("requiredAmount").isNull()).isTrue();}
        assertThat(enough.path("costs").path("purchaseOutlay").asText()).isEqualTo("0.00");assertThat(new java.math.BigDecimal(enough.path("lines").get(0).path("referenceAmount").asText())).isEqualByComparingTo("150");assertThat(get(enough.path("id").asText())).isEqualTo(enough);
    }
    @Test void 明确用量同维合并保留未归一与可选及原证据()throws Exception {
        var request=body(UUID.randomUUID().toString(),"NEED_BUY",true);((ObjectNode)request.path("confirmations").get(0)).put("confirmedAmount","0.01").put("confirmedUnitCode","KG");var view=create(request,201);
        assertThat(view.path("items")).hasSize(3);assertThat(view.path("status").asText()).isEqualTo("READY_FOR_MATCHING");assertThat(view.path("matchingStatus").asText()).isEqualTo("NOT_MATCHED");assertThat(view.path("costs").path("unknownItemCount").asInt()).isEqualTo(3);
        assertThat(new java.math.BigDecimal(view.path("items").get(0).path("requiredAmount").asText())).isEqualByComparingTo("30");assertThat(view.path("items").get(0).path("lineKeys")).hasSize(3);
        var evidence=db.queryForObject("SELECT evidence_json::text FROM shopping_list",String.class);assertThat(json.readTree(evidence).path("materials").path("recipes")).hasSize(5);
        assertThat(new java.math.BigDecimal(view.path("lines").get(0).path("originalAmountMin").asText())).isEqualByComparingTo("100");assertThat(view.path("lines").get(0).path("confirmedAmount").asText()).isEqualTo("0.01");
        assertThat(db.queryForObject("SELECT count(*) FROM shopping_list_item",Integer.class)).isEqualTo(3);
    }
    @Test void 同键顺序与十进制格式无关异内容冲突并发仅一份()throws Exception {
        var body=body(UUID.randomUUID().toString(),"NEED_BUY",true);var first=create(body,201);var reversed=body.deepCopy();var array=(ArrayNode)reversed.path("confirmations");var values=new ArrayList<JsonNode>();array.forEach(values::add);Collections.reverse(values);array.removeAll();values.forEach(v->{((ObjectNode)v).put("confirmedAmount","10");array.add(v);});assertThat(create(reversed,200)).isEqualTo(first);
        var wrong=body.deepCopy();((ObjectNode)wrong.path("confirmations").get(0)).put("availability","HAVE_ENOUGH");assertThat(f.a.post("/shopping-lists",wrong.toString()).statusCode()).isEqualTo(409);
        var concurrent=body(UUID.randomUUID().toString(),"HAVE_ENOUGH",false);refreshCsrf();var pool=Executors.newFixedThreadPool(4);try{var futures=new ArrayList<Future<java.net.http.HttpResponse<String>>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->f.a.prepared("/shopping-lists",concurrent.toString())));var statuses=new ArrayList<Integer>();var ids=new HashSet<String>();for(var future:futures){var response=future.get(20,TimeUnit.SECONDS);statuses.add(response.statusCode());ids.add(json.readTree(response.body()).path("data").path("id").asText());}assertThat(statuses).containsExactlyInAnyOrder(201,200,200,200);assertThat(ids).hasSize(1);}finally{pool.shutdown();}
        assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isEqualTo(2);
    }
    @Test void 严格JSON数量覆盖来源和维度拒绝均无写()throws Exception {
        var base=body(UUID.randomUUID().toString(),"NEED_BUY",true);var variants=new ArrayList<String>();variants.add(base+"{}");variants.add(base.toString().replace("\"requestId\":","\"requestId\":\"重复\",\"requestId\":"));variants.add(base.toString().replace("\"expectedVersion\":1","\"expectedVersion\":1.5"));var extra=base.deepCopy();extra.put("price","10");variants.add(extra.toString());var missing=base.deepCopy();((ArrayNode)missing.path("confirmations")).remove(0);variants.add(missing.toString());var duplicate=base.deepCopy();((ArrayNode)duplicate.path("confirmations")).add(duplicate.path("confirmations").get(0).deepCopy());variants.add(duplicate.toString());
        for(String invalid:List.of("-1","0","NaN","1e1","1.000000001","1000000001")){var bad=base.deepCopy();((ObjectNode)bad.path("confirmations").get(0)).put("confirmedAmount",invalid);variants.add(bad.toString());}
        var numeric=base.deepCopy();((ObjectNode)numeric.path("confirmations").get(0)).put("confirmedAmount",10);variants.add(numeric.toString());var unit=base.deepCopy();((ObjectNode)unit.path("confirmations").get(0)).put("confirmedUnitCode","ML");variants.add(unit.toString());var foreign=base.deepCopy();((ObjectNode)foreign.path("confirmations").get(0)).put("lineKey","999:999");variants.add(foreign.toString());
        for(String raw:variants)assertThat(f.a.post("/shopping-lists",raw).statusCode()).isEqualTo(400);
        var fingerprint=base.deepCopy();fingerprint.put("sourceFingerprint","a".repeat(64));assertThat(f.a.post("/shopping-lists",fingerprint.toString()).statusCode()).isEqualTo(409);assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isZero();
    }
    @Test void 身份CSRF归属与失权历史不泄漏()throws Exception {
        var row=create(body(UUID.randomUUID().toString(),"NEED_BUY",true),201);String id=row.path("id").asText();assertThat(f.b.get("/shopping-lists/"+id).statusCode()).isEqualTo(404);assertThat(f.b.get(previewPath(1,option)).statusCode()).isEqualTo(404);assertThat(f.b.post("/shopping-lists",body(UUID.randomUUID().toString(),"NEED_BUY",true).toString()).statusCode()).isEqualTo(404);
        var anonymous=java.net.http.HttpClient.newHttpClient();assertThat(anonymous.send(java.net.http.HttpRequest.newBuilder(f.a.uri("/shopping-lists/"+id)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(f.a.client.send(java.net.http.HttpRequest.newBuilder(f.a.uri("/shopping-lists")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body(UUID.randomUUID().toString(),"HAVE_ENOUGH",false).toString())).build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        db.update("UPDATE recipe_source SET source_type='USER_CUSTOM',owner_user_id=2,visibility='PRIVATE' WHERE id=10");var hidden=f.a.get("/shopping-lists/"+id);assertThat(hidden.statusCode()).isEqualTo(404);assertThat(hidden.body()).doesNotContain("番茄","原始注意事项");assertThat(f.a.get(previewPath(1,option)).statusCode()).isEqualTo(404);
    }
    @Test void 事实变化保留历史而新创建拒绝修订后旧键仍重放()throws Exception {
        var request=body(UUID.randomUUID().toString(),"NEED_BUY",true);var original=create(request,201);String id=original.path("id").asText();String before=db.queryForObject("SELECT view_json::text FROM shopping_list WHERE id=?",String.class,Long.parseLong(id));
        db.update("UPDATE recipe SET servings=4 WHERE id=10");assertThat(get(id).path("factsStatus").asText()).isEqualTo("CHANGED");assertThat(create(request,200).path("factsStatus").asText()).isEqualTo("CHANGED");assertThat(f.a.post("/shopping-lists",body(UUID.randomUUID().toString(),"NEED_BUY",true).toString()).statusCode()).isEqualTo(409);
        store.register(1,Long.parseLong(f.conversation),Long.parseLong(meal),"REVISE",UUID.randomUUID().toString(),"修订","{}",null,1,true);
        assertThat(create(request,200).path("id").asText()).isEqualTo(id);assertThat(f.a.get(previewPath(1,option)).statusCode()).isEqualTo(409);assertThat(f.a.get(previewPath(2,option)).statusCode()).isEqualTo(409);assertThat(db.queryForObject("SELECT view_json::text FROM shopping_list WHERE id=?",String.class,Long.parseLong(id))).isEqualTo(before);
    }
    @Test void 明细保存后失败整个事务回滚且可原键再创建()throws Exception {
        var request=body(UUID.randomUUID().toString(),"NEED_BUY",true);db.execute("CREATE FUNCTION t16_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.line_order=2 THEN RAISE EXCEPTION 'test-only'; END IF; RETURN NEW; END $$");db.execute("CREATE TRIGGER t16_fail BEFORE INSERT ON shopping_list_item FOR EACH ROW EXECUTE FUNCTION t16_fail()");
        try{assertThat(f.a.post("/shopping-lists",request.toString()).statusCode()).isEqualTo(500);assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM shopping_list_item",Integer.class)).isZero();}finally{db.execute("DROP TRIGGER t16_fail ON shopping_list_item");db.execute("DROP FUNCTION t16_fail()");}assertThat(create(request,201).path("status").asText()).isEqualTo("READY_FOR_MATCHING");
    }
    @Test void 在途及过期指导只读拒绝不恢复不登记且归属绑定()throws Exception {
        var action=store.registerGuide(1,Long.parseLong(f.conversation),Long.parseLong(meal),UUID.randomUUID().toString(),1,Long.parseLong(option),"{}",true);
        assertThat(sourceMapper.busy(1,Long.parseLong(f.conversation),Long.parseLong(meal))).isTrue();
        assertThat(sourceMapper.busy(2,Long.parseLong(f.conversation),Long.parseLong(meal))).isFalse();
        assertThat(sourceMapper.busy(1,Long.parseLong(f.conversation)+100,Long.parseLong(meal))).isFalse();
        for(String state:List.of("PENDING","RUNNING")){
            db.update("UPDATE meal_action SET status=?,started_at=CASE WHEN ?='RUNNING' THEN clock_timestamp() ELSE NULL END,deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",state,state,action.actionId());
            var before=db.queryForList("SELECT status,finished_at,deadline_at::text FROM meal_action WHERE id=?",action.actionId());
            var response=f.a.get(previewPath(1,option));assertThat(response.statusCode()).isEqualTo(409);assertThat(response.body()).contains("MEAL_BUSY");
            assertThat(f.a.post("/shopping-lists",body(UUID.randomUUID().toString(),"HAVE_ENOUGH",false).toString()).statusCode()).isEqualTo(409);
            assertThat(db.queryForList("SELECT status,finished_at,deadline_at::text FROM meal_action WHERE id=?",action.actionId())).isEqualTo(before);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isZero();
    }
    @Test void 创建持锁至提交修订真实等待而旧键仍可重放()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var held=new AtomicBoolean();
        doAnswer(call->{Object result=realItem(call);if(held.compareAndSet(false,true)){entered.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("测试等待超时");}return result;}).when(shoppingMapper).item(anyLong(),anyLong(),anyInt(),any());
        var request=body(UUID.randomUUID().toString(),"HAVE_ENOUGH",false);refreshCsrf();var pool=Executors.newFixedThreadPool(2);
        try {
            var creation=pool.submit(()->f.a.prepared("/shopping-lists",request.toString()));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var revision=pool.submit(()->store.register(1,Long.parseLong(f.conversation),Long.parseLong(meal),"REVISE",UUID.randomUUID().toString(),"修订","{}",null,1,true));
            boolean blocked=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<limit){if(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%conversation%')",Boolean.class)){blocked=true;break;}Thread.sleep(30);}
            assertThat(blocked).isTrue();assertThat(revision.isDone()).isFalse();release.countDown();
            var result=f.data(creation.get(15,TimeUnit.SECONDS),201);assertThat(result.path("source").path("version").asInt()).isEqualTo(1);assertThat(revision.get(15,TimeUnit.SECONDS).version()).isEqualTo(2);
            assertThat(create(request,200).path("id")).isEqualTo(result.path("id"));assertThat(f.a.get(previewPath(1,option)).statusCode()).isEqualTo(409);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void 提交前独立连接改变事实整份清单回滚()throws Exception {
        var changed=new AtomicBoolean();
        doAnswer(call->{Object result=realItem(call);if(changed.compareAndSet(false,true)){
            var external=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
            external.update("UPDATE recipe SET servings=4 WHERE id=10");
        }return result;}).when(shoppingMapper).item(anyLong(),anyLong(),anyInt(),any());
        var response=f.a.post("/shopping-lists",body(UUID.randomUUID().toString(),"NEED_BUY",true).toString());assertThat(response.statusCode()).isEqualTo(409);assertThat(response.body()).contains("MEAL_FACTS_CHANGED");
        assertThat(db.queryForObject("SELECT servings FROM recipe WHERE id=10",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM shopping_list_item",Integer.class)).isZero();
    }
    @Test void 预算未知与足量为零保存原预算而不生成报价()throws Exception {
        db.update("UPDATE meal_session SET view_json=jsonb_set(view_json,'{requirements,budgetYuan}',to_jsonb('12.50'::text)) WHERE id=?",Long.parseLong(meal));
        preview=f.data(f.a.get(previewPath(1,option)),200);
        var unknown=create(body(UUID.randomUUID().toString(),"NEED_BUY",true),201);assertThat(unknown.path("costs").path("budgetStatus").asText()).isEqualTo("UNKNOWN");assertThat(unknown.path("costs").path("purchaseOutlay").isNull()).isTrue();
        var enough=create(body(UUID.randomUUID().toString(),"HAVE_ENOUGH",false),201);assertThat(enough.path("costs").path("budgetStatus").asText()).isEqualTo("WITHIN_BUDGET");assertThat(enough.path("source").path("budgetYuan").asText()).isEqualTo("12.50");
        db.update("UPDATE meal_session SET view_json=jsonb_set(view_json,'{requirements,budgetYuan}',to_jsonb('1.00'::text)) WHERE id=?",Long.parseLong(meal));assertThat(get(enough.path("id").asText()).path("source").path("budgetYuan").asText()).isEqualTo("12.50");
    }
    private void refreshCsrf()throws Exception {f.a.csrf=json.readTree(f.a.get("/auth/csrf").body()).path("data").path("token").asText();}
    // 使用同一SqlSessionTemplate真实执行XML，仅在插入后设置测试同步点。
    private int realItem(org.mockito.invocation.InvocationOnMock call){return sqlSession.getMapper(com.cooker.shopping.mapper.ShoppingMapper.class).item(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));}
    private String path(){return f.path(meal);}
    private String previewPath(int version,String option){return path()+"/shopping-preview?expectedVersion="+version+"&optionId="+option;}
    private ObjectNode body(String key,String availability,boolean confirm){var body=json.createObjectNode();body.put("requestId",key);body.put("conversationId",f.conversation);body.put("mealId",meal);body.put("expectedVersion",1);body.put("optionId",option);body.put("sourceFingerprint",preview.path("source").path("sourceFingerprint").asText());var confirmations=body.putArray("confirmations");for(var line:preview.path("lines")){var item=confirmations.addObject();item.put("lineKey",line.path("lineKey").asText());item.put("availability",availability);if(confirm){item.put("confirmedAmount","10.00000000");item.put("confirmedUnitCode","G");}else{item.putNull("confirmedAmount");item.putNull("confirmedUnitCode");}}return body;}
    private JsonNode create(ObjectNode body,int status)throws Exception{return f.data(f.a.post("/shopping-lists",body.toString()),status);}
    private JsonNode get(String id)throws Exception{return f.data(f.a.get("/shopping-lists/"+id),200);}
    @AfterEach void noModels(){verifyNoInteractions(gateway,embedding,retrieval);}
}
