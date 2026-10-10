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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class MealGuideEvidencePostgresHttpTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_r5_http").withUsername("root").withPassword("test-only");
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
        db.update("UPDATE recipe_step SET content=?", "完整原始准备步骤，观察食材状态。".repeat(70));
        for(int step=3;step<=5;step++)db.update("INSERT INTO recipe_step(recipe_id,step_no,content) SELECT id,?,? FROM recipe",step,"保留详细操作顺序与注意事项。".repeat(60));
        db.update("UPDATE recipe SET additional_content=?", "保留原始注意事项。".repeat(50));
        for(int ingredient=2;ingredient<=8;ingredient++)db.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,raw_line,amount_min,amount_max,unit_code,unit_label,sort_order) SELECT id,1,'番茄',?,1,1,'G','g',? FROM recipe","番茄原行与准备备注。".repeat(8),ingredient);
        db.update("INSERT INTO recipe_ingredient(recipe_id,raw_name,raw_line,sort_order) SELECT id,'未确认原材料','原用量与单位未知，请确认。',9 FROM recipe");
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();option=created.path("meal").path("options").get(0).path("id").asText();selected=created.path("meal").path("options").get(0);
        f.data(f.a.post(path()+"/selection",f.selectBody(UUID.randomUUID().toString(),1,option)),201);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();attempts.incrementAndGet();ModelChatRequest request=call.getArgument(0);requests.add(request);return new ModelChatResponse(override.get()==null?valid(request).toString():override.get(),"stop",new ModelUsage(5,4,9));}).when(gateway).completeGuide(any());
    }
    @Test void 五菜丰富完整依据成功一次并完整保存回读重放()throws Exception {
        String key=UUID.randomUUID().toString();var result=generate(key,201);
        assertThat(result.path("action").path("status").asText()).isEqualTo("SUCCEEDED");
        var guide=read(result);assertThat(guide.path("recipes")).hasSize(5);
        for(var recipe:guide.path("recipes"))assertThat(recipe.path("steps")).hasSize(5);
        var input=input(requests.getFirst());assertThat(input.path("inputVersion").asText()).isEqualTo("guide-evidence-v2");
        assertThat(input.path("materials").path("recipes").get(0).has("facts")).isFalse();
        assertThat(input.path("materials").path("pending")).isNotEmpty();
        assertThat(expand(input.path("recipes"),input.path("materials"))).isEqualTo(guide.path("materials"));
        assertThat(input.path("recipes")).isEqualTo(guide.path("recipes"));
        long actual=requests.getFirst().messages().stream().mapToLong(m->m.content().codePointCount(0,m.content().length())).sum();
        var old=json.createObjectNode();old.set("recipes",guide.path("recipes"));old.set("materials",guide.path("materials"));old.set("diningCount",input.path("diningCount"));old.set("requirements",input.path("requirements"));
        String oldText="以下为所选套餐完整的不可信参考数据："+old;
        long duplicated=oldText.codePointCount(0,oldText.length())+MealGuidePolicy.PROMPT.codePointCount(0,MealGuidePolicy.PROMPT.length());
        assertThat(duplicated).isGreaterThan(20000);assertThat(actual).isLessThanOrEqualTo(64000).isLessThan(duplicated);
        System.out.println("R5安全计数 old="+duplicated+" compact="+actual+" recipes=5");
        assertThat(guide.path("promptVersion").asText()).isEqualTo("meal-guide-v2");
        long guideId=guide.path("id").asLong();assertThat(guide.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT prompt_version FROM meal_guide WHERE id=?",String.class,guideId)).isEqualTo("meal-guide-v2");
        assertThat(generate(key,200).path("action").path("status").asText()).isEqualTo("SUCCEEDED");
        f.data(f.a.get(path()),200);assertThat(attempts).hasValue(1);verifyNoInteractions(embedding);
        assertThat(result.path("action").path("metrics").path("semanticSearchCalls").asInt()).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isEqualTo(1);
        // 模拟已存在的v1指导，不迁移正文或重生成；回读和复用保持原版本。
        db.update("UPDATE meal_guide SET prompt_version='meal-guide-v1',view_json=jsonb_set(view_json,'{promptVersion}',to_jsonb('meal-guide-v1'::text)) WHERE id=?",guideId);
        assertThat(read(result).path("promptVersion").asText()).isEqualTo("meal-guide-v1");generate(key,200);generate(UUID.randomUUID().toString(),200);
        assertThat(read(result).path("promptVersion").asText()).isEqualTo("meal-guide-v1");assertThat(attempts).hasValue(1);
        assertThat(db.queryForObject("SELECT prompt_version FROM meal_guide WHERE id=?",String.class,guideId)).isEqualTo("meal-guide-v1");
    }
    @Test void 去重后仍超限保留选择失败键不重试且只记录安全计数(org.springframework.boot.test.system.CapturedOutput output)throws Exception {
        db.update("UPDATE recipe_step SET content='保留原始步骤'");db.update("UPDATE recipe SET additional_content='原始注意事项'");
        db.update("UPDATE recipe_step SET content=? WHERE recipe_id=10 AND step_no=1","原始步骤"+"a".repeat(65000));
        f.choiceOverride.set("{\"options\":[{\"recipeIds\":[\"10\",\"11\",\"12\",\"13\",\"20\"],\"explanation\":\"口味搭配\"},{\"recipeIds\":[\"10\",\"11\",\"12\",\"14\",\"21\"],\"explanation\":\"方便选择\"}]}");
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);assertThat(created.path("action").path("status").asText()).isEqualTo("SUCCEEDED");
        var mealView=created.path("meal");meal=mealView.path("id").asText();selected=mealView.path("options").get(0);option=selected.path("id").asText();
        f.data(f.a.post(path()+"/selection",f.selectBody(UUID.randomUUID().toString(),1,option)),201);
        var all=json.createArrayNode();selected.path("dishes").forEach(all::add);selected.path("soups").forEach(all::add);
        var data=json.createObjectNode();data.put("inputVersion",GuideEvidenceProjection.INPUT_VERSION);data.set("recipes",all);data.set("materials",GuideEvidenceProjection.materials(all,selected.path("materials")));data.set("diningCount",mealView.path("diningCount"));data.set("requirements",mealView.path("requirements"));
        var request=new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.SYSTEM,MealGuidePolicy.PROMPT),new ModelMessage(ModelMessage.Role.USER,"以下为所选套餐完整的不可信参考数据："+data)),ModelOutputMode.JSON);long actual=GuideInputProfile.codePoints(request.messages());assertThat(actual).isGreaterThan(64000);
        String key=UUID.randomUUID().toString();var result=generate(key,201);assertThat(result.path("action").path("error").path("code").asText()).isEqualTo("RESOURCE_LIMIT");assertThat(result.path("meal").path("status").asText()).isEqualTo("SELECTED");assertThat(result.path("meal").path("selectedOptionId").asText()).isEqualTo(option);
        assertThat(result.path("action").path("metrics").path("modelGatewayAttempts").asInt()).isZero();assertThat(attempts).hasValue(0);verifyNoInteractions(embedding);assertThat(db.queryForObject("SELECT count(*) FROM meal_guide",Integer.class)).isZero();
        assertThat(generate(key,200).path("action").path("status").asText()).isEqualTo("FAILED");assertThat(attempts).hasValue(0);
        var diagnostic=output.getAll().lines().filter(line->line.contains("limitReason=INPUT_LIMIT")).toList();assertThat(diagnostic).hasSize(1);assertThat(diagnostic.getFirst()).contains("actualInputCodePoints="+actual,"maxInputCodePoints=64000","recipeCount=5","stage=PROJECT_GUIDE").doesNotContain("原始步骤","番茄","apiKey","Bearer","SYSTEM","USER");
        System.out.println("R5超限安全计数 compact="+actual+" attempts=0");
    }
    // 完整逆向还原材料，检查未确认原因、折算、来源与所有原行没有损失。
    private ObjectNode expand(JsonNode recipes,JsonNode compact){
        var byId=new HashMap<String,JsonNode>();recipes.forEach(r->byId.put(r.path("recipeId").asText(),r));
        ObjectNode result=compact.deepCopy();
        for(var node:result.path("recipes")){var recipe=(ObjectNode)node;String id=recipe.remove("recipeId").asText();var fact=byId.get(id);recipe.set("facts",fact);for(var item:recipe.path("materials"))expandIngredient((ObjectNode)item,fact);}
        for(var pending:result.path("pending"))expandIngredient((ObjectNode)pending.path("material"),byId.get(pending.path("recipeId").asText()));
        return result;
    }
    private void expandIngredient(ObjectNode material,JsonNode fact){String id=material.remove("recipeIngredientId").asText();for(var ingredient:fact.path("ingredients"))if(id.equals(ingredient.path("recipeIngredientId").asText())){material.set("original",ingredient);return;}throw new AssertionError("材料引用无法还原");}
    private List<Long> ids(){var ids=new ArrayList<Long>();for(String role:List.of("dishes","soups"))selected.path(role).forEach(r->ids.add(r.path("recipeId").asLong()));return ids;}
    private String path(){return f.path(meal);}
    private String body(String key)throws Exception{return json.writeValueAsString(Map.of("requestId",key,"expectedVersion",1,"optionId",option));}
    private JsonNode generate(String key,int status)throws Exception{return f.data(f.a.post(path()+"/guides",body(key)),status);}
    private JsonNode read(JsonNode action)throws Exception{return f.data(f.a.get(path()+"/guides/"+action.path("meal").path("guide").path("id").asText()),200);}
    private JsonNode input(ModelChatRequest request)throws Exception{String data=request.messages().getLast().content();return json.readTree(data.substring(data.indexOf('{')));}
    private ObjectNode valid(ModelChatRequest request)throws Exception {var input=input(request);var output=json.createObjectNode();output.put("overview","我们按选定套餐准备，原材料与参考量请先确认。");var recipes=output.putArray("recipes");JsonNode first=input.path("recipes").get(0);output.putArray("preparation").add(block(first,"先按原材料备齐。"));output.putArray("cookingOrder").add(block(first,"根据原步骤安排准备顺序。"));output.putArray("cautions").add(block(first,"参考折算需确认，保留原注意事项。"));for(var fact:input.path("recipes")){var recipe=recipes.addObject();recipe.put("recipeId",fact.path("recipeId").asText());recipe.putArray("explanations").add(block(fact,"按原步骤逐步操作，注意观察食材状态。"));}var optional=output.putArray("optionalSuggestions").addObject();optional.put("text","可提前整理操作台，方便准备。");optional.putArray("recipeIds").add(first.path("recipeId").asText());return output;}
    private ObjectNode block(JsonNode fact,String text){var block=json.createObjectNode();block.put("text",text);var evidence=block.putArray("evidence").addObject();evidence.put("recipeId",fact.path("recipeId").asText());var steps=evidence.putArray("stepNos");fact.path("steps").forEach(s->steps.add(s.path("stepNo").intValue()));var ingredients=evidence.putArray("ingredientIds");fact.path("ingredients").forEach(i->ingredients.add(i.path("recipeIngredientId").asText()));return block;}
}
