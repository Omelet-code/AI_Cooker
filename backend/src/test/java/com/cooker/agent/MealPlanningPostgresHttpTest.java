package com.cooker.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.cooker.agent.dto.*;
import com.cooker.agent.service.*;
import com.cooker.retrieval.service.QueryEmbeddingService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
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

/** 真认证/HTTP/PG/U1/U2/T5，只替换外部模型与查询Embedding，测试绝不访问收费供应商。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MealPlanningPostgresHttpTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_u3_http").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("cooker.identity.jwt-secret",()->"test-only-u3-secret-01234567890123456789");r.add("cooker.identity.llm-credential.master-key",()->"");r.add("cooker.retrieval.index.api-key",()->"");r.add("cooker.agent.execution-enabled",()->true);r.add("cooker.t6.enabled",()->false);}
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired com.cooker.conversation.service.MealPersistenceService store;
    @Autowired com.cooker.identity.service.CurrentUserService current;
    @Autowired com.cooker.identity.service.UserDiningProfileService profiles;
    @Autowired com.cooker.policy.MealPolicy policy;
    @MockitoBean ModelGateway gateway;
    @MockitoBean QueryEmbeddingService embedding;
    @MockitoBean RecommendationClock clock;
    AtomicInteger attempts=new AtomicInteger();
    AtomicReference<String> understanding=new AtomicReference<>(),choiceOverride=new AtomicReference<>();
    List<ModelChatRequest> requests=new CopyOnWriteArrayList<>();
    Browser a,b;String conversation;
    @BeforeEach void initialize()throws Exception {
        db.execute("TRUNCATE recipe_source,app_user,ingredient,cooking_tool RESTART IDENTITY CASCADE");
        attempts.set(0);requests.clear();choiceOverride.set(null);understanding.set(understanding("RANDOM",4,1,3));
        when(clock.nanoTime()).thenReturn(0L);
        when(embedding.embed(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;});
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();ModelChatRequest request=call.getArgument(0);requests.add(request);attempts.incrementAndGet();assertThat(request.outputMode()).isEqualTo(ModelOutputMode.JSON);String result=request.messages().getFirst().content().contains("changes")?understanding.get():choice(request);return new ModelChatResponse(result,"stop",null);}).when(gateway).complete(any());
        a=new Browser("u3_alice");b=new Browser("u3_bob");conversation=data(a.post("/conversations","{\"title\":\"新餐测试\"}"),201).path("id").asText();
        db.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES(1,'番茄','REVIEWED')");
        for(long id=10;id<=16;id++)seed(id,"meat_dish",null);for(long id=20;id<=21;id++)seed(id,"soup",null);
    }
    @Test void 四菜一汤两套真实材料字符串ID与零模型选择回看()throws Exception{
        var created=create(a,UUID.randomUUID().toString(),"请随机推荐四菜一汤给三人",null);var meal=created.path("meal");
        assertThat(meal.path("status").asText()).isEqualTo("OPTIONS_READY");assertThat(meal.path("options")).hasSize(2);assertThat(attempts).hasValue(2);
        for(var option:meal.path("options")){assertThat(option.path("dishes")).hasSize(4);assertThat(option.path("soups")).hasSize(1);var fact=option.path("dishes").get(0);assertThat(fact.path("recipeId").isTextual()).isTrue();assertThat(fact.path("sourceDocumentId").isTextual()).isTrue();assertThat(fact.path("ingredients").get(0).path("amountMin").isTextual()).isTrue();assertThat(option.path("materials").path("recipes")).hasSize(5);assertThat(option.path("materials").path("recipes").get(0).path("materials").get(0).path("referenceAmount").asText()).isEqualTo("1.5");}
        assertThat(meal.path("guide").isNull()).isTrue();assertThat(meal.path("guideAvailable").asBoolean()).isFalse();verifyNoInteractions(embedding);
        String id=meal.path("id").asText(),option=meal.path("options").get(0).path("id").asText();
        var selected=data(a.post(path(id)+"/selection",selectBody(UUID.randomUUID().toString(),1,option)),201);
        assertThat(selected.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(selected.path("meal").path("guideAvailable").asBoolean()).isFalse();
        var repeated=data(a.post(path(id)+"/selection",selectBody(UUID.randomUUID().toString(),1,option)),200);assertThat(repeated.path("action").path("id")).isEqualTo(selected.path("action").path("id"));
        data(a.get(path(id)),200);data(a.get("/conversations/"+conversation+"/meals"),200);data(a.get(path(id)+"/actions/"+created.path("action").path("id").asText()),200);
        assertThat(attempts).hasValue(2);assertThat(db.queryForObject("SELECT count(*) FROM chat_message",Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM conversation_turn",Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM meal_action WHERE type='SELECT'",Integer.class)).isEqualTo(1);
    }
    @Test void 默认显式缺失冲突人数与档案不追改修订旧键回读()throws Exception{
        understanding.set(understanding("RANDOM",1,1,null));
        var missing=create(a,UUID.randomUUID().toString(),"随机一道菜一汤",null);assertThat(missing.path("meal").path("status").asText()).isEqualTo("CLARIFICATION");assertThat(attempts).hasValue(1);
        data(a.put("/users/me/profile","{\"defaultDiningCount\":2}"),200);
        String key=UUID.randomUUID().toString();var original=create(a,key,"随机一道菜一汤",null);var meal=original.path("meal");assertThat(meal.path("diningCountSource").asText()).isEqualTo("PROFILE");assertThat(meal.path("diningCount").asInt()).isEqualTo(2);
        data(a.put("/users/me/profile","{\"defaultDiningCount\":7}"),200);
        var revised=data(a.post(path(meal.path("id").asText())+"/revisions",body(UUID.randomUUID().toString(),"随机一道菜一汤",null,1)),201);assertThat(revised.path("meal").path("diningCount").asInt()).isEqualTo(2);assertThat(revised.path("meal").path("version").asInt()).isEqualTo(2);
        int count=attempts.get();var replay=createResponse(a,key,"随机一道菜一汤",null,200);assertThat(replay.path("meal").path("version").asInt()).isEqualTo(1);assertThat(attempts).hasValue(count);
        var explicit=create(a,UUID.randomUUID().toString(),"随机一道菜一汤",4);assertThat(explicit.path("meal").path("diningCountSource").asText()).isEqualTo("EXPLICIT");assertThat(explicit.path("meal").path("diningCount").asInt()).isEqualTo(4);
        understanding.set(understanding("RANDOM",1,1,3));var conflict=create(a,UUID.randomUUID().toString(),"三人随机一道菜一汤",4);assertThat(conflict.path("meal").path("status").asText()).isEqualTo("CLARIFICATION");assertThat(conflict.path("meal").path("answer").asText()).contains("人数不同");
    }
    @Test void 语义真实U2与T5每角色一次随机零向量并保持未知计数()throws Exception{
        understanding.set(understanding("SEMANTIC",1,1,3));var value=create(a,UUID.randomUUID().toString(),"三人番茄一道菜一汤",null);assertThat(value.path("meal").path("status").asText()).isEqualTo("OPTIONS_READY");verify(embedding,times(2)).embed("番茄");assertThat(value.path("action").path("metrics").path("semanticSearchCalls").asInt()).isEqualTo(2);assertThat(value.path("action").path("metrics").path("queryEmbeddingRequests").isNull()).isTrue();assertThat(attempts).hasValue(2);
    }
    @Test void 候选只有一套不足一套及未知角色不自动补齐()throws Exception{
        db.update("DELETE FROM recipe WHERE id NOT IN (10,20)");understanding.set(understanding("RANDOM",1,1,3));var one=create(a,UUID.randomUUID().toString(),"三人随机一道菜一汤",null);assertThat(one.path("meal").path("options")).hasSize(1);assertThat(one.path("meal").path("noticeCodes").toString()).contains("ONLY_ONE_OPTION");
        db.update("UPDATE recipe SET category_code='unknown' WHERE id=20");int count=attempts.get();var none=create(a,UUID.randomUUID().toString(),"三人随机一道菜一汤",null);assertThat(none.path("meal").path("answer").asText()).contains("NO_MATCH");assertThat(none.path("meal").path("options")).isEmpty();assertThat(attempts).hasValue(count+1);
    }
    @Test void 严格请求JSON与模型未知尾随小数重复越界假时间安全内容()throws Exception{
        long rows=db.queryForObject("SELECT count(*) FROM meal_session",Long.class);
        for(String invalid:List.of("{}","null","[]","{\"requestId\":\""+UUID.randomUUID()+"\",\"content\":\"x\",\"diningCount\":2.5}",body(UUID.randomUUID().toString(),"x",null,null).replace("\"content\"","\"userId\""),body(UUID.randomUUID().toString(),"x",null,null)+"{}"))assertThat(a.post("/conversations/"+conversation+"/meals",invalid).statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM meal_session",Long.class)).isEqualTo(rows);assertThat(attempts).hasValue(0);
        String good=understanding.get();
        for(String invalid:List.of(good+"{}",good.replace("\"intent\":\"MEAL_PLAN\"","\"intent\":\"MEAL_PLAN\",\"evil\":1"),good.replace("\"value\":4","\"value\":4.9"))){understanding.set(invalid);var failed=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);assertThat(failed.path("action").path("error").path("code").asText()).isEqualTo("MODEL_INVALID_RESPONSE");}
        understanding.set(good);choiceOverride.set("{\"options\":[{\"recipeIds\":[\"10\",\"10\",\"11\",\"12\",\"20\"],\"explanation\":\"口味搭配\"},{\"recipeIds\":[\"999\"],\"explanation\":\"其他\"}]}");assertThat(create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null).path("action").path("error").path("code").asText()).isEqualTo("MODEL_INVALID_RESPONSE");
        choiceOverride.set(null);db.update("UPDATE recipe_step SET content='加入药物治疗' WHERE recipe_id=10");var isolated=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);assertThat(isolated.path("meal").path("status").asText()).isEqualTo("OPTIONS_READY");for(var option:isolated.path("meal").path("options"))for(var dish:option.path("dishes"))assertThat(dish.path("recipeId").asText()).isNotEqualTo("10");
    }
    @Test void 所有读写越权拒绝及事实变化撤权历史保护()throws Exception{
        var created=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);String meal=created.path("meal").path("id").asText(),option=created.path("meal").path("options").get(0).path("id").asText(),action=created.path("action").path("id").asText();
        for(String url:List.of(path(meal),path(meal)+"/actions/"+action,"/conversations/"+conversation+"/meals"))assertThat(b.get(url).statusCode()).isEqualTo(404);
        assertThat(b.post(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),1,option)).statusCode()).isEqualTo(404);assertThat(b.post(path(meal)+"/revisions",body(UUID.randomUUID().toString(),"x",null,1)).statusCode()).isEqualTo(404);
        long recipe=created.path("meal").path("options").get(0).path("dishes").get(0).path("recipeId").asLong();db.update("UPDATE recipe SET servings=4 WHERE id=?",recipe);assertThat(data(a.get(path(meal)),200).path("factsStatus").asText()).isEqualTo("CHANGED");assertThat(a.post(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),1,option)).statusCode()).isEqualTo(409);
        db.update("DELETE FROM recipe WHERE id=?",recipe);var protectedMeal=data(a.get(path(meal)),200);assertThat(protectedMeal.path("factsStatus").asText()).isEqualTo("UNAVAILABLE");assertThat(protectedMeal.path("options")).isEmpty();assertThat(data(a.get(path(meal)+"/actions/"+action),200).path("meal").path("options")).isEmpty();assertThat(attempts).hasValue(2);
    }
    @Test void 同键在途并发异键忙终态异参数与双选幂等()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);var defaultAnswer=understanding.get();
        doAnswer(call->{ModelChatRequest request=call.getArgument(0);attempts.incrementAndGet();if(request.messages().getFirst().content().contains("changes")){entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return new ModelChatResponse(defaultAnswer,"stop",null);}return new ModelChatResponse(choice(request),"stop",null);}).when(gateway).complete(any());
        String key=UUID.randomUUID().toString(),content="三人随机四菜一汤";
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->create(a,key,content,null));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var pending=data(a.post("/conversations/"+conversation+"/meals",body(key,content,null,null)),202);String meal=pending.path("meal").path("id").asText();assertThat(a.post(path(meal)+"/revisions",body(UUID.randomUUID().toString(),content,null,1)).statusCode()).isEqualTo(409);release.countDown();var ready=first.get(10,TimeUnit.SECONDS);assertThat(attempts).hasValue(2);
            assertThat(a.post("/conversations/"+conversation+"/meals",body(key,content+"!",null,null)).statusCode()).isEqualTo(409);
            String one=ready.path("meal").path("options").get(0).path("id").asText(),two=ready.path("meal").path("options").get(1).path("id").asText();
            a.csrf=json.readTree(a.get("/auth/csrf").body()).path("data").path("token").asText();var selectA=executor.submit(()->a.prepared(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),1,one)));var selectB=executor.submit(()->a.prepared(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),1,two)));
            assertThat(List.of(selectA.get().statusCode(),selectB.get().statusCode())).containsExactlyInAnyOrder(201,409);assertThat(db.queryForObject("SELECT count(*) FROM meal_action WHERE type='SELECT'",Integer.class)).isEqualTo(1);
        }finally{release.countDown();}
    }
    @Test void 软预算阶段前后停止且不启动候选或第二次模型()throws Exception{
        AtomicLong nanos=new AtomicLong();when(clock.nanoTime()).thenAnswer(call->nanos.get());
        doAnswer(call->{attempts.incrementAndGet();nanos.set(90_000_000_000L);return new ModelChatResponse(understanding.get(),"stop",null);}).when(gateway).complete(any());
        var failure=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);assertThat(failure.path("action").path("error").path("code").asText()).isEqualTo("RESOURCE_LIMIT");assertThat(attempts).hasValue(1);assertThat(failure.path("action").path("metrics").path("candidateServiceCalls").asInt()).isZero();
    }
    @Test void 成功快照落库异常整体回滚不以内存假称成功()throws Exception{
        db.execute("CREATE FUNCTION u3_fail_option() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only rollback'; END $$");db.execute("CREATE TRIGGER u3_option_fail BEFORE INSERT ON meal_option FOR EACH ROW EXECUTE FUNCTION u3_fail_option()");
        try{var failed=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);assertThat(failed.path("action").path("status").asText()).isEqualTo("FAILED");assertThat(db.queryForObject("SELECT count(*) FROM meal_option",Integer.class)).isZero();assertThat(failed.path("meal").path("options")).isEmpty();}finally{db.execute("DROP TRIGGER u3_option_fail ON meal_option");db.execute("DROP FUNCTION u3_fail_option()");}
    }
    @Test void 关闭执行新建零落库而旧键重放及选择仍零模型()throws Exception {
        String key=UUID.randomUUID().toString(),content="三人随机四菜一汤";var created=create(a,key,content,null);
        var disabled=new com.cooker.agent.service.impl.MealPlanningServiceImpl(store,current,profiles,null,null,null,policy,clock,false);
        long count=db.queryForObject("SELECT count(*) FROM meal_action",Long.class);
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L,null,List.of()));
            assertThatThrownBy(()->disabled.submit(conversation,null,"CREATE",new MealHttpRequest(UUID.randomUUID().toString(),content,null,null,null)))
                    .isInstanceOfSatisfying(com.cooker.common.ApiException.class,error->assertThat(error.code()).isEqualTo("AGENT_EXECUTION_DISABLED"));
            assertThat(db.queryForObject("SELECT count(*) FROM meal_action",Long.class)).isEqualTo(count);
            assertThat(disabled.submit(conversation,null,"CREATE",new MealHttpRequest(key,content,null,null,null)).status()).isEqualTo(200);
            String meal=created.path("meal").path("id").asText(),option=created.path("meal").path("options").get(0).path("id").asText();
            assertThat(disabled.submit(conversation,meal,"SELECT",new MealHttpRequest(UUID.randomUUID().toString(),"",null,1,option)).status()).isEqualTo(201);
            assertThat(attempts).hasValue(2);
        }finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void 过期动作GET不恢复新写锁内恢复且迟到终态不可覆盖()throws Exception {
        String parameters="{\"test\":\"expired\"}";
        var registration=store.register(1,Long.parseLong(conversation),null,"CREATE",UUID.randomUUID().toString(),"原文",parameters,null,null,true);
        store.start(1,Long.parseLong(conversation),registration.mealId(),registration.actionId());
        db.update("UPDATE meal_action SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",registration.actionId());
        var untouched=data(a.get(path(Long.toString(registration.mealId()))+"/actions/"+registration.actionId()),200);assertThat(untouched.path("action").path("status").asText()).isEqualTo("RUNNING");
        var revised=store.register(1,Long.parseLong(conversation),registration.mealId(),"REVISE",UUID.randomUUID().toString(),"新文", "{\"test\":\"new\"}",null,1,true);
        assertThat(revised.version()).isEqualTo(2);var old=store.action(1,Long.parseLong(conversation),registration.mealId(),registration.actionId());assertThat(json.valueToTree(old).path("action").path("error").path("code").asText()).isEqualTo("RUNTIME_INTERRUPTED");
        assertThatThrownBy(()->store.complete(1,Long.parseLong(conversation),registration.mealId(),registration.actionId(),"{}","{}" )).isInstanceOf(com.cooker.common.ApiException.class);
        assertThat(attempts).hasValue(0);
    }
    @Test void 组方案等待中事实变化失败及整餐时间先澄清不冒充满足()throws Exception {
        doAnswer(call->{ModelChatRequest request=call.getArgument(0);attempts.incrementAndGet();if(request.messages().getFirst().content().contains("changes"))return new ModelChatResponse(understanding.get(),"stop",null);String selected=choice(request);db.update("UPDATE recipe SET servings=4");return new ModelChatResponse(selected,"stop",null);}).when(gateway).complete(any());
        var changed=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);assertThat(changed.path("action").path("error").path("code").asText()).isEqualTo("MEAL_FACTS_CHANGED");assertThat(db.queryForObject("SELECT count(*) FROM meal_option",Integer.class)).isZero();
        int count=attempts.get();var unclear=create(a,UUID.randomUUID().toString(),"三人四菜一汤整餐在30分钟完成",null);assertThat(unclear.path("meal").path("answer").asText()).contains("不能核实整餐完成时间");assertThat(attempts).hasValue(count+1);
    }
    @Test void 两次完整应用上下文重建仍只读同版本方案()throws Exception {
        var created=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);long meal=created.path("meal").path("id").asLong();
        for(int restart=0;restart<2;restart++)try(var application=new org.springframework.boot.builder.SpringApplicationBuilder(com.cooker.CookerApplication.class).run(
                "--server.port=0","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username=root","--spring.datasource.password=test-only",
                "--cooker.identity.jwt-secret=test-only-u3-restart-secret-0123456789","--cooker.identity.llm-credential.master-key=","--cooker.retrieval.index.api-key=","--cooker.agent.execution-enabled=false","--cooker.t6.enabled=false")){
            var recovered=application.getBean(com.cooker.conversation.service.MealPersistenceService.class).get(1,Long.parseLong(conversation),meal);
            assertThat(json.valueToTree(recovered).path("options")).hasSize(2);assertThat(json.valueToTree(recovered).path("version").asInt()).isEqualTo(1);
        }
        assertThat(attempts).hasValue(2);
    }
    @Test void 别名改变硬资格即使事实指纹未变也拒绝选择及失败修订清旧选定()throws Exception {
        var parsed=(ObjectNode)json.readTree(understanding.get());var excluded=(ObjectNode)parsed.path("changes").path("excludedIngredientNames");excluded.put("action","SET");excluded.put("evidence","排除茄红");excluded.putArray("value").add("茄红");understanding.set(parsed.toString());
        var created=create(a,UUID.randomUUID().toString(),"三人随机四菜一汤排除茄红",null);String meal=created.path("meal").path("id").asText(),option=created.path("meal").path("options").get(0).path("id").asText();
        db.update("INSERT INTO ingredient_alias(ingredient_id,alias_name) VALUES(1,'茄红')");
        assertThat(data(a.get(path(meal)),200).path("factsStatus").asText()).isEqualTo("CURRENT");
        assertThat(a.post(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),1,option)).statusCode()).isEqualTo(409);
        db.update("DELETE FROM ingredient_alias WHERE alias_name='茄红'");
        data(a.post(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),1,option)),201);
        understanding.set("{}");var failed=data(a.post(path(meal)+"/revisions",body(UUID.randomUUID().toString(),"重新安排",null,1)),201);
        assertThat(failed.path("meal").path("version").asInt()).isEqualTo(2);assertThat(failed.path("meal").path("status").asText()).isEqualTo("FAILED");assertThat(failed.path("meal").path("selectedOptionId").isNull()).isTrue();assertThat(failed.path("meal").path("options")).isEmpty();
        assertThat(a.post(path(meal)+"/selection",selectBody(UUID.randomUUID().toString(),2,option)).statusCode()).isEqualTo(409);
        var first=requests.get(requests.size()-1);var data=json.readTree(first.messages().getLast().content().substring(first.messages().getLast().content().indexOf('{')));assertThat(data.path("currentMeal").has("options")).isFalse();assertThat(data.path("currentMeal").has("requirements")).isTrue();assertThat(attempts).hasValue(3);
    }
    String understanding(String mode,int dish,int soup,Integer dining)throws Exception{var value=json.createObjectNode();value.put("intent","MEAL_PLAN");value.put("mode",mode);if("RANDOM".equals(mode))value.putNull("query");else value.put("query","番茄");var changes=value.putObject("changes");for(String field:com.cooker.policy.MealPolicy.FIELDS){var change=changes.putObject(field);change.put("action","KEEP");change.putNull("value");change.putNull("evidence");}set(changes,"dishCount",dish);set(changes,"soupCount",soup);if(dining!=null)set(changes,"diningCount",dining);value.putArray("clarificationCodes");return value.toString();}
    void set(ObjectNode changes,String field,int value){var change=(ObjectNode)changes.path(field);change.put("action","SET");change.put("value",value);change.put("evidence",field.equals("diningCount")?"三人":field.equals("dishCount")?(value==4?"四菜":"一道菜"):(value==1?"一汤":"零汤"));}
    String choice(ModelChatRequest request)throws Exception{if(choiceOverride.get()!=null)return choiceOverride.get();String content=request.messages().getLast().content();var input=json.readTree(content.substring(content.indexOf('{')));var dishes=new ArrayList<String>();var soups=new ArrayList<String>();for(var candidate:input.path("candidates")){("DISH".equals(candidate.path("role").path("role").asText())?dishes:soups).add(candidate.path("recipeId").asText());}int d=input.path("requirements").path("dishCount").asInt(),s=input.path("requirements").path("soupCount").asInt(),count=input.path("requiredOptions").asInt();var output=json.createObjectNode();var options=output.putArray("options");for(int i=0;i<count;i++){var option=options.addObject();var ids=option.putArray("recipeIds");for(int n=0;n<d;n++)ids.add(dishes.get((n+(i>0&&dishes.size()>d?1:0))%dishes.size()));for(int n=0;n<s;n++)ids.add(soups.get((n+(i>0&&dishes.size()==d?1:0))%soups.size()));option.put("explanation",i==0?"口味相互搭配，方便选择。":"换个搭配，口味更丰富。");}return output.toString();}
    void seed(long id,String category,Long owner){db.update("INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES(?,?,?,?,?)",id,owner==null?"SYSTEM_HOWTOCOOK":"USER_CUSTOM","u3-"+id,owner,owner==null?"PUBLIC":"PRIVATE");db.update("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(?,?,'u3',repeat('a',64),'# 番茄','SUCCESS')",id,id);db.update("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,servings,estimated_minutes,additional_content) VALUES(?,?,?,? ,?,2,10,'原始注意事项')",id,id,id,"番茄"+(category.equals("soup")?"汤":"菜")+id,category);db.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,amount_min,amount_max,unit_code,unit_label,raw_line,sort_order) VALUES(?,1,'番茄',1,1,'G','g','番茄 1 g',1)",id);db.update("INSERT INTO recipe_step(recipe_id,step_no,content) VALUES(?,1,'原始步骤')",id);db.update("INSERT INTO recipe_search_document(recipe_id,source_document_id,source_type,owner_user_id,visibility,chunk_type,chunk_no,content,content_hash,document_template_version,embedding_model,embedding_dimension,embedding_status) VALUES(?,?,?,?,?,'overview',1,'番茄家常菜',repeat('b',64),1,'text-embedding-v4',1024,'READY')",id,id,owner==null?"SYSTEM_HOWTOCOOK":"USER_CUSTOM",owner,owner==null?"PUBLIC":"PRIVATE");}
    String path(String meal){return "/conversations/"+conversation+"/meals/"+meal;}
    String body(String key,String content,Integer count,Integer version)throws Exception{var node=json.createObjectNode();node.put("requestId",key);node.put("content",content);if(count!=null)node.put("diningCount",count);if(version!=null)node.put("expectedVersion",version);return node.toString();}
    String selectBody(String key,int version,String option)throws Exception{return json.writeValueAsString(Map.of("requestId",key,"expectedVersion",version,"optionId",option));}
    JsonNode create(Browser browser,String key,String content,Integer count)throws Exception{return createResponse(browser,key,content,count,201);}
    JsonNode createResponse(Browser browser,String key,String content,Integer count,int status)throws Exception{return data(browser.post("/conversations/"+conversation+"/meals",body(key,content,count,null)),status);}
    JsonNode data(HttpResponse<String> response,int status)throws Exception{assertThat(response.statusCode()).as(response.body()).isEqualTo(status);return json.readTree(response.body()).path("data");}
    class Browser{
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
        Browser(String name)throws Exception{var token=client.send(HttpRequest.newBuilder(uri("/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());csrf=json.readTree(token.body()).path("data").path("token").asText();String account=json.writeValueAsString(Map.of("username",name,"password","test-only-password"));data(post("/auth/register",account),201);data(post("/auth/login",account),200);csrf=json.readTree(get("/auth/csrf").body()).path("data").path("token").asText();}
        URI uri(String path){return URI.create("http://localhost:"+port+"/api"+path);}
        HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> post(String path,String body)throws Exception{return send("POST",path,body);}
        HttpResponse<String> prepared(String path,String body)throws Exception{return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> put(String path,String body)throws Exception{return send("PUT",path,body);}
        HttpResponse<String> send(String method,String path,String body)throws Exception{csrf=json.readTree(get("/auth/csrf").body()).path("data").path("token").asText();return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
