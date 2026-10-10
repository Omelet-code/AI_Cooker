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
class MealDecimalPostgresHttpTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_u5_decimal").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("cooker.identity.jwt-secret",()->"test-only-u4-secret-01234567890123456789");r.add("cooker.identity.llm-credential.master-key",()->"");r.add("cooker.retrieval.index.api-key",()->"");r.add("cooker.agent.execution-enabled",()->true);r.add("cooker.t6.enabled",()->false);}
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MealPersistenceService store;
    @Autowired CurrentUserService current;@Autowired UserDiningProfileService profiles;@Autowired MealPolicy safety;@Autowired MealGuidePolicy policy;@Autowired RecipeMealFactsService facts;
    @MockitoBean ModelGateway gateway;@MockitoBean QueryEmbeddingService embedding;@MockitoBean RecommendationClock clock;
    final MealPlanningPostgresHttpTest f=new MealPlanningPostgresHttpTest();
    final AtomicInteger attempts=new AtomicInteger();final AtomicReference<String> override=new AtomicReference<>();final List<ModelChatRequest> requests=new CopyOnWriteArrayList<>();
    JsonNode selected;String meal,option;
    static final java.nio.file.Path FIXTURES=java.nio.file.Path.of("target/u5-r4-fixtures.json");
    static final Set<String> DECIMALS=Set.of("amountMin","amountMax","referenceAmount","budgetYuan");
    @BeforeAll static void resetFixtures()throws Exception{java.nio.file.Files.writeString(FIXTURES,"{}");}
    @BeforeEach void setUp()throws Exception {
        f.port=port;f.json=json;f.db=db;f.store=store;f.current=current;f.profiles=profiles;f.policy=safety;f.gateway=gateway;f.embedding=embedding;f.clock=clock;f.initialize();attempts.set(0);requests.clear();
        db.update("UPDATE recipe_ingredient SET amount_min=400,amount_max=400 WHERE recipe_id=10");
        db.update("UPDATE recipe_ingredient SET amount_min=20,amount_max=30 WHERE recipe_id=11");
        db.update("UPDATE recipe_ingredient SET amount_min=1000,amount_max=1000 WHERE recipe_id=12");
        db.update("UPDATE recipe_ingredient SET amount_min=0,amount_max=0 WHERE recipe_id=13");
        db.update("UPDATE recipe_ingredient SET amount_min=0.000001,amount_max=0.000002 WHERE recipe_id=14");
        db.update("UPDATE recipe_ingredient SET amount_min=null,amount_max=null WHERE recipe_id=20");
        db.update("UPDATE recipe_ingredient SET amount_min=12345.123456,amount_max=12345.123456 WHERE recipe_id=21");
        f.choiceOverride.set("{\"options\":[{\"recipeIds\":[\"10\",\"11\",\"12\",\"13\",\"20\"],\"explanation\":\"口味搭配。\"},{\"recipeIds\":[\"11\",\"12\",\"13\",\"14\",\"21\"],\"explanation\":\"另一种搭配。\"}]}");
        doAnswer(call->{attempts.incrementAndGet();ModelChatRequest request=call.getArgument(0);requests.add(request);return new ModelChatResponse(valid(request).toString(),"stop",null);}).when(gateway).completeGuide(any());
    }
    @Test void 新餐单修订选择指导所有约定数量精确普通十进制()throws Exception {
        var before=facts.readFacts(1,List.of(10L,11L,12L,13L,14L,20L,21L));
        String key=UUID.randomUUID().toString();var created=f.create(f.a,key,"三人随机四菜一汤",null);fixture("newCreate",created);plain(created);
        var ingredient=created.path("meal").path("options").get(0).path("dishes").get(0).path("ingredients").get(0);assertThat(ingredient.path("amountMin").asText()).isEqualTo("400");
        meal=created.path("meal").path("id").asText();String revisionKey=UUID.randomUUID().toString();var revised=f.data(f.a.post(path()+"/revisions",f.body(revisionKey,"三人随机四菜一汤",null,1)),201);fixture("newRevise",revised);plain(revised);
        selected=revised.path("meal").path("options").get(1);option=selected.path("id").asText();String selectKey=UUID.randomUUID().toString();var chosen=f.data(f.a.post(path()+"/selection",f.selectBody(selectKey,2,option)),201);fixture("newSelected",chosen);plain(chosen);
        fixture("newSelectReplay",f.data(f.a.post(path()+"/selection",f.selectBody(selectKey,2,option)),200));fixture("newReviseReplay",f.data(f.a.post(path()+"/revisions",f.body(revisionKey,"三人随机四菜一汤",null,1)),200));
        var guideAction=f.data(f.a.post(path()+"/guides",json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",2,"optionId",option))),201);fixture("newGuideAction",guideAction);var guide=read(guideAction);fixture("newGuide",guide);plain(guide);
        var micro=guide.path("recipes").get(3).path("ingredients").get(0);assertThat(micro.path("amountMin").asText()).isEqualTo("0.000001");assertThat(micro.path("amountMax").asText()).isEqualTo("0.000002");
        assertThat(f.createResponse(f.a,key,"三人随机四菜一汤",null,200).path("meal").path("version").asInt()).isEqualTo(1);
        assertThat(facts.readFacts(1,List.of(10L,11L,12L,13L,14L,20L,21L))).isEqualTo(before);assertThat(f.attempts).hasValue(4);assertThat(attempts).hasValue(1);verifyNoInteractions(embedding);
    }
    @Test void 旧指数快照列表详情动作原键与指导只读兼容不写回()throws Exception {
        String key=UUID.randomUUID().toString();var created=f.create(f.a,key,"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();selected=created.path("meal").path("options").get(0);option=selected.path("id").asText();String selectKey=UUID.randomUUID().toString();f.data(f.a.post(path()+"/selection",f.selectBody(selectKey,1,option)),201);
        String guideKey=UUID.randomUUID().toString();var guided=generate(guideKey,201);String guide=guided.path("meal").path("guide").path("id").asText();
        // 只在隔离PG构造旧格式快照，数量值和原事实指纹不变；不触碰开发库。
        legacy("meal_session","view_json");legacy("meal_action","result");legacy("meal_option","facts");legacy("meal_guide","view_json");
        var before=stored();var actual=f.data(f.a.get(path()),200);fixture("legacyMeal",actual);plain(actual);
        var action=f.data(f.a.get(path()+"/actions/"+created.path("action").path("id").asText()),200);fixture("legacyAction",action);plain(action);
        var page=f.data(f.a.get("/conversations/"+f.conversation+"/meals"),200);fixture("legacyPage",page);plain(page);
        var replay=f.createResponse(f.a,key,"三人随机四菜一汤",null,200);fixture("legacyReplay",replay);plain(replay);assertThat(replay.path("action").path("id")).isEqualTo(created.path("action").path("id"));
        var selectReplay=f.data(f.a.post(path()+"/selection",f.selectBody(selectKey,1,option)),200);fixture("legacySelectReplay",selectReplay);plain(selectReplay);
        var readGuide=f.data(f.a.get(path()+"/guides/"+guide),200);fixture("legacyGuide",readGuide);plain(readGuide);fixture("legacyGuideReplay",generate(guideKey,200));plain(generate(guideKey,200));
        assertThat(stored()).isEqualTo(before);assertThat(before.toString()).contains("4E+2");assertThat(f.attempts).hasValue(2);assertThat(attempts).hasValue(1);verifyNoInteractions(embedding);
        assertThat(f.b.get(path()).statusCode()).isEqualTo(404);assertThat(f.b.get(path()+"/guides/"+guide).statusCode()).isEqualTo(404);
        db.update("UPDATE recipe SET servings=4 WHERE id=10");var changed=f.data(f.a.get(path()),200);assertThat(changed.path("factsStatus").asText()).isEqualTo("CHANGED");plain(changed);
        db.update("DELETE FROM recipe WHERE id=10");var unavailable=f.data(f.a.get(path()),200);assertThat(unavailable.path("factsStatus").asText()).isEqualTo("UNAVAILABLE");assertThat(unavailable.path("options")).isEmpty();assertThat(f.data(f.a.get(path()+"/guides/"+guide),200).path("recipes")).isEmpty();
    }
    @Test void 历史非法数字和超限安全拒绝失权先隐藏不绕过安全检查()throws Exception {
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();var view=(ObjectNode)created.path("meal").deepCopy();
        var ingredient=(ObjectNode)view.path("options").get(0).path("dishes").get(0).path("ingredients").get(0);
        for(var sample:Map.of("1E+1000000000",413,"NaN",400).entrySet()){
            ingredient.put("amountMin",sample.getKey());db.update("UPDATE meal_session SET view_json=?::jsonb WHERE id=?",view.toString(),Long.parseLong(meal));var before=stored();var response=f.a.get(path());assertThat(response.statusCode()).isEqualTo(sample.getValue());assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(sample.getValue()==413?"RESOURCE_LIMIT":"INVALID_RESPONSE");assertThat(stored()).isEqualTo(before);
        }
        ((ObjectNode)view.path("options").get(0).path("dishes").get(0)).put("additionalContent","治疗疾病");db.update("UPDATE meal_session SET view_json=?::jsonb WHERE id=?",view.toString(),Long.parseLong(meal));var unsafe=f.a.get(path());assertThat(unsafe.statusCode()).isEqualTo(400);assertThat(json.readTree(unsafe.body()).path("code").asText()).isEqualTo("UNSAFE_CONTENT");assertThat(unsafe.body()).doesNotContain("治疗疾病");
        db.update("DELETE FROM recipe WHERE id=10");var hidden=f.data(f.a.get(path()),200);assertThat(hidden.path("factsStatus").asText()).isEqualTo("UNAVAILABLE");assertThat(hidden.path("options")).isEmpty();assertThat(f.attempts).hasValue(2);verifyNoInteractions(embedding);
    }
    private void plain(JsonNode node){if(node.isObject())node.fields().forEachRemaining(e->{if(DECIMALS.contains(e.getKey())&&!e.getValue().isNull())assertThat(e.getValue().isTextual()&&e.getValue().asText().matches("-?[0-9]+(?:\\.[0-9]+)?")).as(e.getKey()+"="+e.getValue()).isTrue();plain(e.getValue());});else if(node.isArray())node.forEach(this::plain);}
    private void fixture(String name,JsonNode value)throws Exception{var fixtures=(ObjectNode)json.readTree(java.nio.file.Files.readString(FIXTURES));fixtures.set(name,value);java.nio.file.Files.writeString(FIXTURES,fixtures.toString());}
    private void legacy(String table,String column)throws Exception{for(var row:db.queryForList("SELECT id,"+column+"::text AS snapshot FROM "+table+" WHERE "+column+" IS NOT NULL")){var snapshot=json.readTree(row.get("snapshot").toString());exponents(snapshot);db.update("UPDATE "+table+" SET "+column+"=?::jsonb WHERE id=?",snapshot.toString(),row.get("id"));}}
    private void exponents(JsonNode node){if(node.isObject())node.fields().forEachRemaining(e->{if(DECIMALS.contains(e.getKey())&&!e.getValue().isNull())((ObjectNode)node).put(e.getKey(),new java.math.BigDecimal(e.getValue().asText()).stripTrailingZeros().toString());else exponents(e.getValue());});else if(node.isArray())node.forEach(this::exponents);}
    private List<Object> stored(){var result=new ArrayList<Object>();for(String table:List.of("meal_session","meal_action","meal_option","meal_guide"))result.add(db.queryForList("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY id",String.class));return result;}
    private List<Long> ids(){var ids=new ArrayList<Long>();for(String role:List.of("dishes","soups"))selected.path(role).forEach(r->ids.add(r.path("recipeId").asLong()));return ids;}
    private String path(){return f.path(meal);}
    private String body(String key)throws Exception{return json.writeValueAsString(Map.of("requestId",key,"expectedVersion",1,"optionId",option));}
    private JsonNode generate(String key,int status)throws Exception{return f.data(f.a.post(path()+"/guides",body(key)),status);}
    private JsonNode read(JsonNode action)throws Exception{return f.data(f.a.get(path()+"/guides/"+action.path("meal").path("guide").path("id").asText()),200);}
    private JsonNode input(ModelChatRequest request)throws Exception{String data=request.messages().getLast().content();return json.readTree(data.substring(data.indexOf('{')));}
    private ObjectNode valid(ModelChatRequest request)throws Exception {var input=input(request);var output=json.createObjectNode();output.put("overview","我们按选定套餐准备，原材料与参考量请先确认。");var recipes=output.putArray("recipes");JsonNode first=input.path("recipes").get(0);output.putArray("preparation").add(block(first,"先按原材料备齐。"));output.putArray("cookingOrder").add(block(first,"根据原步骤安排准备顺序。"));output.putArray("cautions").add(block(first,"参考折算需确认，保留原注意事项。"));for(var fact:input.path("recipes")){var recipe=recipes.addObject();recipe.put("recipeId",fact.path("recipeId").asText());recipe.putArray("explanations").add(block(fact,"按原步骤逐步操作，注意观察食材状态。"));}var optional=output.putArray("optionalSuggestions").addObject();optional.put("text","可提前整理操作台，方便准备。");optional.putArray("recipeIds").add(first.path("recipeId").asText());return output;}
    private ObjectNode block(JsonNode fact,String text){var block=json.createObjectNode();block.put("text",text);var evidence=block.putArray("evidence").addObject();evidence.put("recipeId",fact.path("recipeId").asText());var steps=evidence.putArray("stepNos");fact.path("steps").forEach(s->steps.add(s.path("stepNo").intValue()));var ingredients=evidence.putArray("ingredientIds");fact.path("ingredients").forEach(i->ingredients.add(i.path("recipeIngredientId").asText()));return block;}
}
