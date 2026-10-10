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
class MealGuidancePostgresHttpTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_u4_http").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("cooker.identity.jwt-secret",()->"test-only-u4-secret-01234567890123456789");r.add("cooker.identity.llm-credential.master-key",()->"");r.add("cooker.retrieval.index.api-key",()->"");r.add("cooker.agent.execution-enabled",()->true);r.add("cooker.t6.enabled",()->false);}
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MealPersistenceService store;
    @Autowired CurrentUserService current;@Autowired UserDiningProfileService profiles;@Autowired MealPolicy safety;@Autowired MealGuidePolicy policy;@Autowired RecipeMealFactsService facts;
    @MockitoBean ModelGateway gateway;@MockitoBean QueryEmbeddingService embedding;@MockitoBean RecommendationClock clock;
    final MealPlanningPostgresHttpTest f=new MealPlanningPostgresHttpTest();
    final AtomicInteger attempts=new AtomicInteger();final AtomicReference<String> override=new AtomicReference<>();final List<ModelChatRequest> requests=new CopyOnWriteArrayList<>();
    JsonNode selected;String meal,option;
    @BeforeEach void setUp()throws Exception {
        f.port=port;f.json=json;f.db=db;f.store=store;f.current=current;f.profiles=profiles;f.policy=safety;f.gateway=gateway;f.embedding=embedding;f.clock=clock;f.initialize();attempts.set(0);override.set(null);requests.clear();
        db.update("INSERT INTO recipe_step(recipe_id,step_no,content) SELECT id,2,'保留最后的原始步骤' FROM recipe");
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();option=created.path("meal").path("options").get(0).path("id").asText();selected=created.path("meal").path("options").get(0);
        f.data(f.a.post(path()+"/selection",f.selectBody(UUID.randomUUID().toString(),1,option)),201);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();attempts.incrementAndGet();ModelChatRequest request=call.getArgument(0);requests.add(request);return new ModelChatResponse(override.get()==null?valid(request).toString():override.get(),"stop",new ModelUsage(5,4,9));}).when(gateway).completeGuide(any());
    }
    @Test void 四菜一汤完整依据仅所选进入Prompt并独立指导与零模型历史()throws Exception {
        String key=UUID.randomUUID().toString();var result=generate(key,201);assertThat(result.path("action").path("type").asText()).isEqualTo("GUIDE");assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(result.path("meal").path("guideAvailable").asBoolean()).isTrue();
        var guide=read(result);assertThat(guide.path("recipes")).hasSize(5);assertThat(guide.path("content").path("recipes")).hasSize(5);assertThat(guide.path("materials").path("recipes")).hasSize(5);assertThat(guide.path("content").path("optionalSuggestions").get(0).path("title").asText()).isEqualTo("模型补充：可选建议");assertThat(guide.path("personaVersion").asText()).isEqualTo("xiaochu-v1");
        for(var recipe:guide.path("recipes")){assertThat(recipe.path("steps")).hasSize(2);assertThat(recipe.path("additionalContent").asText()).isEqualTo("原始注意事项");assertThat(recipe.path("ingredients").get(0).path("amountMin").isTextual()).isTrue();assertThat(recipe.path("recipeId").isTextual()).isTrue();}
        var input=input(requests.getFirst());assertThat(input.path("recipes")).hasSize(5);assertThat(input.has("content")).isFalse();assertThat(input.has("options")).isFalse();var expected=new HashSet<String>();for(var role:List.of("dishes","soups"))selected.path(role).forEach(r->expected.add(r.path("recipeId").asText()));var actual=new HashSet<String>();input.path("recipes").forEach(r->actual.add(r.path("recipeId").asText()));assertThat(actual).isEqualTo(expected);
        var replay=generate(key,200);var reused=generate(UUID.randomUUID().toString(),200);assertThat(reused.path("action").path("id")).isEqualTo(replay.path("action").path("id"));f.data(f.a.get(path()),200);f.data(f.a.get(path()+"/actions/"+result.path("action").path("id").asText()),200);assertThat(attempts).hasValue(1);assertThat(f.attempts).hasValue(2);verifyNoInteractions(embedding);assertThat(db.queryForObject("SELECT count(*) FROM meal_action WHERE type='GUIDE'",Integer.class)).isEqualTo(1);assertThat(result.path("action").path("metrics").path("modelUsages").get(0).path("totalTokens").asInt()).isEqualTo(9);
    }
    @Test void 严格请求参数越权和未选定拒绝零调用()throws Exception {
        for(String raw:List.of("{}",body(UUID.randomUUID().toString()).replace("\"expectedVersion\":1","\"expectedVersion\":1.5"),body(UUID.randomUUID().toString()).replace("}",",\"maxTokens\":8192}"),body(UUID.randomUUID().toString())+"{}"))assertThat(f.a.post(path()+"/guides",raw).statusCode()).isEqualTo(400);
        assertThat(f.b.post(path()+"/guides",body(UUID.randomUUID().toString())).statusCode()).isEqualTo(404);assertThat(attempts).hasValue(0);
        var result=generate(UUID.randomUUID().toString(),201);String guide=result.path("meal").path("guide").path("id").asText();assertThat(f.b.get(path()+"/guides/"+guide).statusCode()).isEqualTo(404);assertThat(f.b.get(path()+"/actions/"+result.path("action").path("id").asText()).statusCode()).isEqualTo(404);
    }
    @Test void 输出漏菜错引用虚构克数温度时间及危险文本全部失败不清套餐()throws Exception {
        var request=new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.USER,"数据")),ModelOutputMode.JSON);
        // 基于真实所选事实生成反例，每次新键至多一次，不补写缺菜。
        var input=json.createObjectNode();input.set("recipes",json.valueToTree(facts.readFacts(1,ids())));var prompt=new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.USER,"参考："+input)),ModelOutputMode.JSON);
        var good=valid(prompt);
        var values=new ArrayList<String>();var missing=good.deepCopy();((ArrayNode)missing.path("recipes")).remove(0);values.add(missing.toString());
        var wrong=good.deepCopy();((ObjectNode)wrong.path("recipes").get(0).path("explanations").get(0).path("evidence").get(0)).put("recipeId","999999");values.add(wrong.toString());
        for(String text:List.of("加入999克盐","加热到999℃","等待999分钟","治疗疾病","https://invalid.example")){var bad=good.deepCopy();((ObjectNode)bad.path("preparation").get(0)).put("text",text);values.add(bad.toString());}
        values.add(good.toString()+"{}");values.add(good.toString().replace("\"overview\":","\"overview\":\"重复\",\"overview\":"));
        for(String raw:values){override.set(raw);var result=generate(UUID.randomUUID().toString(),201);assertThat(result.path("action").path("status").asText()).isEqualTo("FAILED");assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(result.path("meal").path("selectedOptionId").asText()).isEqualTo(option);assertThat(result.path("meal").path("options")).hasSize(2);assertThat(result.path("meal").path("guideAvailable").asBoolean()).isFalse();}
        assertThat(attempts).hasValue(values.size());assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isZero();
        override.set(null);generate(UUID.randomUUID().toString(),201);assertThat(attempts).hasValue(values.size()+1);
    }
    @Test void 同键在途异键忙修订忙并发只启动一次()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();attempts.incrementAndGet();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return new ModelChatResponse(valid(call.getArgument(0)).toString(),"stop",null);}).when(gateway).completeGuide(any());
        String key=UUID.randomUUID().toString();var executor=Executors.newSingleThreadExecutor();var future=executor.submit(()->generate(key,201));
        try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var running=f.a.post(path()+"/guides",body(key));assertThat(running.statusCode()).isEqualTo(202);assertThat(running.headers().firstValue("Location")).isPresent();assertThat(f.a.post(path()+"/guides",body(UUID.randomUUID().toString())).statusCode()).isEqualTo(409);assertThat(f.a.post(path()+"/revisions",f.body(UUID.randomUUID().toString(),"重新安排",null,1)).statusCode()).isEqualTo(409);}finally{release.countDown();executor.shutdown();}assertThat(future.get(10,TimeUnit.SECONDS).path("action").path("status").asText()).isEqualTo("SUCCEEDED");assertThat(attempts).hasValue(1);
    }
    @Test void 指导过期GET不恢复新GUIDE恢复保持选定且拒绝迟到成功()throws Exception {
        var registration=store.registerGuide(1,Long.parseLong(f.conversation),Long.parseLong(meal),UUID.randomUUID().toString(),1,Long.parseLong(option),"{\"test\":\"expired\"}",true);store.start(1,Long.parseLong(f.conversation),Long.parseLong(meal),registration.actionId());db.update("UPDATE meal_action SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",registration.actionId());
        assertThat(f.data(f.a.get(path()+"/actions/"+registration.actionId()),200).path("action").path("status").asText()).isEqualTo("RUNNING");
        var result=generate(UUID.randomUUID().toString(),201);assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");var old=f.data(f.a.get(path()+"/actions/"+registration.actionId()),200);assertThat(old.path("action").path("error").path("code").asText()).isEqualTo("RUNTIME_INTERRUPTED");assertThat(old.path("meal").path("selectedOptionId").asText()).isEqualTo(option);
        assertThatThrownBy(()->store.completeGuide(1,Long.parseLong(f.conversation),Long.parseLong(meal),registration.actionId(),Long.parseLong(option),"{}","{}" )).isInstanceOf(com.cooker.common.ApiException.class);assertThat(attempts).hasValue(1);
    }
    @Test void 事实中途变化失败历史变化删除全面隐藏()throws Exception {
        doAnswer(call->{attempts.incrementAndGet();var content=valid(call.getArgument(0));db.update("UPDATE recipe SET servings=4 WHERE id=?",ids().getFirst());return new ModelChatResponse(content.toString(),"stop",null);}).when(gateway).completeGuide(any());
        var changed=generate(UUID.randomUUID().toString(),201);assertThat(changed.path("action").path("error").path("code").asText()).isEqualTo("MEAL_FACTS_CHANGED");assertThat(changed.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isZero();
    }
    @Test void 成功指导事实变化和撤权后各历史入口都不泄漏()throws Exception {
        var result=generate(UUID.randomUUID().toString(),201);db.update("UPDATE recipe SET servings=4 WHERE id=?",ids().getFirst());assertThat(read(result).path("factsStatus").asText()).isEqualTo("CHANGED");
        db.update("DELETE FROM recipe WHERE id=?",ids().getFirst());var unavailable=read(result);assertThat(unavailable.path("factsStatus").asText()).isEqualTo("UNAVAILABLE");assertThat(unavailable.path("recipes")).isEmpty();assertThat(unavailable.path("materials").isNull()).isTrue();assertThat(unavailable.path("content").isNull()).isTrue();
        for(String endpoint:List.of(path(),path()+"/actions/"+result.path("action").path("id").asText())){var value=f.data(f.a.get(endpoint),200);if(value.has("meal"))value=value.path("meal");assertThat(value.path("guide").isNull()).isTrue();assertThat(value.path("guideAvailable").asBoolean()).isFalse();assertThat(value.path("options")).isEmpty();}assertThat(generate(result.path("action").path("requestId").asText(),200).path("meal").path("guideAvailable").asBoolean()).isFalse();assertThat(attempts).hasValue(1);
    }
    @Test void 保存异常整体回滚且失败不清已选项()throws Exception {
        db.execute("CREATE FUNCTION u4_fail_guide() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only rollback'; END $$");db.execute("CREATE TRIGGER u4_guide_fail BEFORE INSERT ON meal_guide FOR EACH ROW EXECUTE FUNCTION u4_fail_guide()");try{var result=generate(UUID.randomUUID().toString(),201);assertThat(result.path("action").path("status").asText()).isEqualTo("FAILED");assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(result.path("meal").path("guideAvailable").asBoolean()).isFalse();assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isZero();}finally{db.execute("DROP TRIGGER u4_guide_fail ON meal_guide");db.execute("DROP FUNCTION u4_fail_guide()");}
    }
    @Test void 软预算模型后超限及完整输入超限不裁剪不追加()throws Exception {
        var time=new AtomicLong();when(clock.nanoTime()).thenAnswer(call->time.get());doAnswer(call->{attempts.incrementAndGet();var content=valid(call.getArgument(0));time.set(90_000_000_000L);return new ModelChatResponse(content.toString(),"stop",new ModelUsage(5,4,9));}).when(gateway).completeGuide(any());var failed=generate(UUID.randomUUID().toString(),201);assertThat(failed.path("action").path("error").path("code").asText()).isEqualTo("RESOURCE_LIMIT");assertThat(failed.path("action").path("metrics").path("modelUsages").get(0).path("totalTokens").asInt()).isEqualTo(9);assertThat(attempts).hasValue(1);
    }
    @Test void 新修订清引用旧GUIDE动作和独立GET仍回历史版本()throws Exception {
        var result=generate(UUID.randomUUID().toString(),201);var revised=f.data(f.a.post(path()+"/revisions",f.body(UUID.randomUUID().toString(),"三人随机四菜一汤",null,1)),201);assertThat(revised.path("meal").path("guide").isNull()).isTrue();assertThat(revised.path("meal").path("guideAvailable").asBoolean()).isFalse();assertThat(read(result).path("version").asInt()).isEqualTo(1);assertThat(generate(result.path("action").path("requestId").asText(),200).path("meal").path("version").asInt()).isEqualTo(1);assertThat(f.a.post(path()+"/guides",body(UUID.randomUUID().toString())).statusCode()).isEqualTo(409);assertThat(attempts).hasValue(1);
    }
    @Test void 完整输入超过六万四码点零模型且不截断来源()throws Exception {
        long longRecipe=ids().getFirst();db.update("UPDATE recipe_step SET content=? WHERE recipe_id=? AND step_no=1","完整来源"+"a".repeat(65000),longRecipe);
        // 新餐随机种子不同；明确让替身模型选择长依据，避免沿用旧餐的optionId产生假失败。
        var dishes=java.util.stream.LongStream.rangeClosed(10,16).filter(id->id!=longRecipe).boxed().toList();
        var choice=json.createObjectNode();var chosen=choice.putArray("options");
        for(int i=0;i<2;i++){var item=chosen.addObject();var recipeIds=item.putArray("recipeIds");recipeIds.add(Long.toString(longRecipe));for(int n=0;n<3;n++)recipeIds.add(Long.toString(dishes.get(n+i)));recipeIds.add(i==0?"20":"21");item.put("explanation","口味相互搭配，方便选择。");}
        f.choiceOverride.set(choice.toString());
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();
        var options=created.path("meal").path("options");for(var candidate:options){boolean contains=false;for(String role:List.of("dishes","soups"))for(var recipe:candidate.path(role))if(recipe.path("steps").get(0).path("content").asText().length()>64000)contains=true;if(contains){option=candidate.path("id").asText();selected=candidate;break;}}
        f.data(f.a.post(path()+"/selection",f.selectBody(UUID.randomUUID().toString(),1,option)),201);var result=generate(UUID.randomUUID().toString(),201);assertThat(result.path("action").path("error").path("code").asText()).isEqualTo("RESOURCE_LIMIT");assertThat(attempts).hasValue(0);assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");
    }
    @Test void 数据库期限模型期间到期与失败同键不再执行()throws Exception {
        doAnswer(call->{attempts.incrementAndGet();var content=valid(call.getArgument(0));db.update("UPDATE meal_action SET deadline_at=clock_timestamp()-interval '1 second' WHERE type='GUIDE' AND status='RUNNING'");return new ModelChatResponse(content.toString(),"stop",null);}).when(gateway).completeGuide(any());String key=UUID.randomUUID().toString();var result=generate(key,201);assertThat(result.path("action").path("error").path("code").asText()).isEqualTo("RUNTIME_INTERRUPTED");assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(result.path("meal").path("selectedOptionId").asText()).isEqualTo(option);assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isZero();assertThat(generate(key,200).path("action").path("status").asText()).isEqualTo("FAILED");assertThat(attempts).hasValue(1);
    }
    @Test void 模型期间私有来源撤权不保存指导也不外发失权事实()throws Exception {
        doAnswer(call->{attempts.incrementAndGet();var content=valid(call.getArgument(0));db.update("UPDATE recipe_source SET source_type='USER_CUSTOM',owner_user_id=2,visibility='PRIVATE' WHERE id=?",ids().getFirst());return new ModelChatResponse(content.toString(),"stop",null);}).when(gateway).completeGuide(any());var result=generate(UUID.randomUUID().toString(),201);assertThat(result.path("action").path("status").asText()).isEqualTo("FAILED");assertThat(result.path("meal").path("factsStatus").asText()).isEqualTo("UNAVAILABLE");assertThat(result.path("meal").path("options")).isEmpty();assertThat(result.path("meal").path("guideAvailable").asBoolean()).isFalse();assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isZero();assertThat(db.queryForObject("SELECT selected_option_id FROM meal_session WHERE id=?",Long.class,Long.parseLong(meal))).isEqualTo(Long.parseLong(option));
    }
    @Test void 关闭新指导零落库完成后复用允许与完整上下文重建回读()throws Exception {
        var disabled=new com.cooker.agent.service.impl.MealGuidanceServiceImpl(store,current,facts,gateway,policy,safety,clock,false);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L,null,List.of()));try{assertThatThrownBy(()->disabled.generate(f.conversation,meal,new MealHttpRequest(UUID.randomUUID().toString(),"",null,1,option))).isInstanceOfSatisfying(com.cooker.common.ApiException.class,e->assertThat(e.code()).isEqualTo("AGENT_EXECUTION_DISABLED"));}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}assertThat(db.queryForObject("SELECT count(*) FROM meal_action WHERE type='GUIDE'",Integer.class)).isZero();
        var result=generate(UUID.randomUUID().toString(),201);long guide=result.path("meal").path("guide").path("id").asLong();
        try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(com.cooker.CookerApplication.class).run("--server.port=0","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username=root","--spring.datasource.password=test-only","--cooker.identity.jwt-secret=test-only-u4-restart-012345678901234","--cooker.identity.llm-credential.master-key=","--cooker.retrieval.index.api-key=","--cooker.agent.execution-enabled=false","--cooker.t6.enabled=false")){var persisted=app.getBean(MealPersistenceService.class).guide(1,Long.parseLong(f.conversation),Long.parseLong(meal),guide);assertThat(json.valueToTree(persisted).path("content").path("recipes")).hasSize(5);}
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L,null,List.of()));try{assertThat(disabled.generate(f.conversation,meal,new MealHttpRequest(UUID.randomUUID().toString(),"",null,1,option)).status()).isEqualTo(200);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}assertThat(attempts).hasValue(1);
    }
    private List<Long> ids(){var ids=new ArrayList<Long>();for(String role:List.of("dishes","soups"))selected.path(role).forEach(r->ids.add(r.path("recipeId").asLong()));return ids;}
    private String path(){return f.path(meal);}
    private String body(String key)throws Exception{return json.writeValueAsString(Map.of("requestId",key,"expectedVersion",1,"optionId",option));}
    private JsonNode generate(String key,int status)throws Exception{return f.data(f.a.post(path()+"/guides",body(key)),status);}
    private JsonNode read(JsonNode action)throws Exception{return f.data(f.a.get(path()+"/guides/"+action.path("meal").path("guide").path("id").asText()),200);}
    private JsonNode input(ModelChatRequest request)throws Exception{String data=request.messages().getLast().content();return json.readTree(data.substring(data.indexOf('{')));}
    private ObjectNode valid(ModelChatRequest request)throws Exception {var input=input(request);var output=json.createObjectNode();output.put("overview","我们按选定套餐准备，原材料与参考量请先确认。");var recipes=output.putArray("recipes");JsonNode first=input.path("recipes").get(0);output.putArray("preparation").add(block(first,"先按原材料备齐。"));output.putArray("cookingOrder").add(block(first,"根据原步骤安排准备顺序。"));output.putArray("cautions").add(block(first,"参考折算需确认，保留原注意事项。"));for(var fact:input.path("recipes")){var recipe=recipes.addObject();recipe.put("recipeId",fact.path("recipeId").asText());recipe.putArray("explanations").add(block(fact,"按原步骤逐步操作，注意观察食材状态。"));}var optional=output.putArray("optionalSuggestions").addObject();optional.put("text","可提前整理操作台，方便准备。");optional.putArray("recipeIds").add(first.path("recipeId").asText());return output;}
    private ObjectNode block(JsonNode fact,String text){var block=json.createObjectNode();block.put("text",text);var evidence=block.putArray("evidence").addObject();evidence.put("recipeId",fact.path("recipeId").asText());var steps=evidence.putArray("stepNos");fact.path("steps").forEach(s->steps.add(s.path("stepNo").intValue()));var ingredients=evidence.putArray("ingredientIds");fact.path("ingredients").forEach(i->ingredients.add(i.path("recipeIngredientId").asText()));return block;}
}
