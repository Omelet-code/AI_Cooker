package com.cooker.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cooker.agent.service.*;
import com.cooker.conversation.service.MealPersistenceService;
import com.cooker.identity.service.*;
import com.cooker.policy.MealPolicy;
import com.cooker.shopping.config.MockCatalogProperties;
import com.cooker.shopping.provider.ProductProvider;
import com.cooker.retrieval.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真认证HTTP、T16创建、PG报价持久化；Provider本地Mock，不调用外部平台。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProductMatchPostgresHttpTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres")).withDatabaseName("cooker_t17_http").withUsername("root").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);r.add("cooker.identity.jwt-secret",()->"test-only-u4-secret-01234567890123456789");r.add("cooker.identity.llm-credential.master-key",()->"");r.add("cooker.retrieval.index.api-key",()->"");r.add("cooker.agent.execution-enabled",()->true);r.add("cooker.t6.enabled",()->false);}
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MealPersistenceService store;@Autowired CurrentUserService current;@Autowired UserDiningProfileService profiles;@Autowired MealPolicy safety;@Autowired MockCatalogProperties config;
    @MockitoBean ModelGateway gateway;@MockitoBean QueryEmbeddingService embedding;@MockitoBean RecommendationClock clock;
    @MockitoSpyBean RecipeSearchService retrieval;@MockitoSpyBean ProductProvider provider;
    final MealPlanningPostgresHttpTest f=new MealPlanningPostgresHttpTest();String meal,option;JsonNode preview;
    @BeforeEach void setup()throws Exception {
        config.setCatalogVersion("mock-v1");config.setProducts(List.of());f.port=port;f.json=json;f.db=db;f.store=store;f.current=current;f.profiles=profiles;f.policy=safety;f.gateway=gateway;f.embedding=embedding;f.clock=clock;f.initialize();
        db.update("UPDATE recipe_ingredient SET optional=true WHERE recipe_id=20");
        f.choiceOverride.set("{\"options\":[{\"recipeIds\":[\"10\",\"11\",\"12\",\"13\",\"20\"],\"explanation\":\"口味搭配\"},{\"recipeIds\":[\"11\",\"12\",\"13\",\"14\",\"21\"],\"explanation\":\"方便选择\"}]}");
        var created=f.create(f.a,UUID.randomUUID().toString(),"三人随机四菜一汤",null);meal=created.path("meal").path("id").asText();option=created.path("meal").path("options").get(0).path("id").asText();f.data(f.a.post(f.path(meal)+"/selection",f.selectBody(UUID.randomUUID().toString(),1,option)),201);
        db.update("UPDATE meal_session SET view_json=jsonb_set(view_json,'{requirements,budgetYuan}',to_jsonb('4.00'::text)) WHERE id=?",Long.parseLong(meal));
        preview=f.data(f.a.get(f.path(meal)+"/shopping-preview?expectedVersion=1&optionId="+option),200);clearInvocations(gateway,embedding,retrieval,provider);
    }
    private void catalog(String price){config.setProducts(List.of(new ProductProvider.Product("mock-tomato",1L,"Mock番茄100g",List.of("110000"),true,new BigDecimal("100"),"G",price==null?null:new BigDecimal(price),"CNY",null,null)));}
    private String demand(boolean enough,boolean quantity)throws Exception {
        return demand(enough,quantity,false);
    }
    private String demand(boolean enough,boolean quantity,boolean optionalPending)throws Exception {
        var body=json.createObjectNode();body.put("requestId",UUID.randomUUID().toString()).put("conversationId",f.conversation).put("mealId",meal).put("expectedVersion",1).put("optionId",option).put("sourceFingerprint",preview.path("source").path("sourceFingerprint").asText());var confirmations=body.putArray("confirmations");
        for(var line:preview.path("lines")){var c=confirmations.addObject();c.put("lineKey",line.path("lineKey").asText()).put("availability",enough?"HAVE_ENOUGH":"NEED_BUY");if(quantity&&!(optionalPending&&line.path("optional").asBoolean())){c.put("confirmedAmount","10").put("confirmedUnitCode","G");}else{c.putNull("confirmedAmount");c.putNull("confirmedUnitCode");}}
        return f.data(f.a.post("/shopping-lists",body.toString()),201).path("id").asText();
    }
    private String path(String list){return "/shopping-lists/"+list+"/product-matches";}
    private String request(String key,String city){return "{\"requestId\":\""+key+"\",\"cityCode\":\""+city+"\"}";}
    private JsonNode match(String list,String key,String city,int status)throws Exception{return f.data(f.a.post(path(list),request(key,city)),status);}
    @Test void 新匹配明确Mock同商品合包超预算原清单不改()throws Exception {
        String list=demand(false,true);String before=db.queryForObject("SELECT row_to_json(s)::text FROM shopping_list s WHERE id=?",String.class,Long.parseLong(list));catalog("10");var v=match(list,UUID.randomUUID().toString(),"110000",201);
        assertThat(v.path("provider").asText()).isEqualTo("MOCK");assertThat(v.path("realData").asBoolean()).isFalse();assertThat(v.path("catalogVersion").asText()).isEqualTo("mock-v1");assertThat(v.path("cityCode").asText()).isEqualTo("110000");assertThat(v.path("capturedAt").asText()).isNotBlank();assertThat(v.path("matchingStatus").asText()).isEqualTo("MATCHED");
        assertThat(v.path("lines")).hasSize(2);assertThat(v.path("purchaseGroups")).hasSize(1);assertThat(v.path("purchaseGroups").get(0).path("itemKeys")).hasSize(2);assertThat(v.path("purchaseGroups").get(0).path("packageCount").asText()).isEqualTo("1");assertThat(v.path("costs").path("consumptionCost").asText()).isEqualTo("5.00");assertThat(v.path("costs").path("purchaseOutlay").asText()).isEqualTo("10.00");assertThat(v.path("costs").path("budgetStatus").asText()).isEqualTo("OVER_BUDGET");assertThat(v.path("costs").path("estimatedDemandCost").isNull()).isTrue();assertThat(v.path("lines").get(0).path("product").path("purchaseUrl").isNull()).isTrue();
        assertThat(db.queryForObject("SELECT row_to_json(s)::text FROM shopping_list s WHERE id=?",String.class,Long.parseLong(list))).isEqualTo(before);assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_match_item",Integer.class)).isEqualTo(2);assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_purchase_group",Integer.class)).isEqualTo(1);verify(provider,times(1)).lookup(anyString(),anyList());
    }
    @Test void GET重放latest零Provider旧价不跟随目录变化()throws Exception {
        String list=demand(false,true),key=UUID.randomUUID().toString();assertThat(f.data(f.a.get(path(list)+"/latest"),200).isNull()).isTrue();catalog("10");var first=match(list,key,"110000",201);clearInvocations(provider);catalog("20");config.setCatalogVersion("mock-v2");
        assertThat(f.data(f.a.get(path(list)+"/"+first.path("id").asText()),200)).isEqualTo(first);assertThat(f.data(f.a.get(path(list)+"/latest"),200)).isEqualTo(first);assertThat(match(list,key,"110000",200)).isEqualTo(first);verifyNoInteractions(provider);
        var newer=match(list,UUID.randomUUID().toString(),"110000",201);assertThat(newer.path("catalogVersion").asText()).isEqualTo("mock-v2");assertThat(newer.path("costs").path("purchaseOutlay").asText()).isEqualTo("20.00");assertThat(f.data(f.a.get(path(list)+"/latest"),200)).isEqualTo(newer);assertThat(f.data(f.a.get(path(list)+"/"+first.path("id").asText()),200)).isEqualTo(first);
    }
    @Test void 足量已有和未知用量零Provider默认空目录不造价()throws Exception {
        String enough=demand(true,false);var a=match(enough,UUID.randomUUID().toString(),"110000",201);assertThat(a.path("matchingStatus").asText()).isEqualTo("NOT_NEEDED");assertThat(a.path("costs").path("estimatedDemandCost").asText()).isEqualTo("0.00");verifyNoInteractions(provider);
        String pending=demand(false,false);var b=match(pending,UUID.randomUUID().toString(),"110000",201);assertThat(b.path("matchingStatus").asText()).isEqualTo("NO_MATCH");assertThat(b.path("lines").get(0).path("status").asText()).isEqualTo("QUANTITY_PENDING");verifyNoInteractions(provider);
        var c=match(demand(false,true),UUID.randomUUID().toString(),"110000",201);assertThat(c.path("matchingStatus").asText()).isEqualTo("NO_MATCH");assertThat(c.path("costs").path("purchaseOutlay").isNull()).isTrue();assertThat(c.path("costs").path("budgetStatus").asText()).isEqualTo("UNKNOWN");
    }
    @Test void 城市缺货缺报价各保留未知与原因()throws Exception {
        String list=demand(false,true);catalog("10");var other=match(list,UUID.randomUUID().toString(),"310000",201);assertThat(other.path("lines").get(0).path("reason").asText()).contains("城市");
        config.setProducts(List.of(new ProductProvider.Product("mock-tomato",1L,"Mock商品",List.of("110000"),false,new BigDecimal("100"),"G",new BigDecimal("10"),"CNY",null,null)));assertThat(match(list,UUID.randomUUID().toString(),"110000",201).path("lines").get(0).path("status").asText()).isEqualTo("OUT_OF_STOCK");
        catalog(null);assertThat(match(list,UUID.randomUUID().toString(),"110000",201).path("lines").get(0).path("status").asText()).isEqualTo("QUOTE_UNKNOWN");
    }
    @Test void 真实HTTP严格参数身份CSRF双用户及异键内容()throws Exception {
        String list=demand(false,true);String key=UUID.randomUUID().toString();catalog("10");match(list,key,"110000",201);assertThat(f.a.post(path(list),request(key,"310000")).statusCode()).isEqualTo(409);String second=demand(false,true);assertThat(f.a.post(path(second),request(key,"110000")).statusCode()).isEqualTo(409);
        for(String raw:List.of("{}",request(UUID.randomUUID().toString(),"110000")+"{}",request(UUID.randomUUID().toString(),"110000").replace("\"cityCode\":","\"price\":10,\"cityCode\":"),request(UUID.randomUUID().toString(),"110000").replace("\"110000\"","110000")))assertThat(f.a.post(path(list),raw).statusCode()).isEqualTo(400);
        assertThat(f.b.get(path(list)+"/latest").statusCode()).isEqualTo(404);assertThat(f.b.post(path(list),request(UUID.randomUUID().toString(),"110000")).statusCode()).isEqualTo(404);assertThat(f.b.get(path(list)+"/1").statusCode()).isEqualTo(404);
        var anonymous=java.net.http.HttpClient.newHttpClient();assertThat(anonymous.send(java.net.http.HttpRequest.newBuilder(f.a.uri(path(list)+"/latest")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(f.a.client.send(java.net.http.HttpRequest.newBuilder(f.a.uri(path(list))).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(request(UUID.randomUUID().toString(),"110000"))).build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    @Test void 并发同键仅一份一个Provider调用()throws Exception {
        String list=demand(false,true);catalog("10");String body=request(UUID.randomUUID().toString(),"110000");f.a.csrf=json.readTree(f.a.get("/auth/csrf").body()).path("data").path("token").asText();var pool=Executors.newFixedThreadPool(4);
        try{var futures=new ArrayList<Future<java.net.http.HttpResponse<String>>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->f.a.prepared(path(list),body)));var statuses=new ArrayList<Integer>();var ids=new HashSet<String>();for(var future:futures){var response=future.get(20,TimeUnit.SECONDS);statuses.add(response.statusCode());ids.add(json.readTree(response.body()).path("data").path("id").asText());}assertThat(statuses).containsExactlyInAnyOrder(201,200,200,200);assertThat(ids).hasSize(1);}finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_match",Integer.class)).isEqualTo(1);verify(provider,times(1)).lookup(anyString(),anyList());
    }
    @Test void 目录异常和明细故障完整回滚原键可以再试()throws Exception {
        String list=demand(false,true),key=UUID.randomUUID().toString();catalog("-1");var bad=f.a.post(path(list),request(key,"110000"));assertThat(bad.statusCode()).isEqualTo(502);assertThat(bad.body()).doesNotContain("packagePrice","Mock番茄100g");assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_match",Integer.class)).isZero();catalog("10");
        db.execute("CREATE FUNCTION t17_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.line_order=2 THEN RAISE EXCEPTION 'test-only'; END IF; RETURN NEW; END $$");db.execute("CREATE TRIGGER t17_fail BEFORE INSERT ON shopping_product_match_item FOR EACH ROW EXECUTE FUNCTION t17_fail()");
        try{assertThat(f.a.post(path(list),request(key,"110000")).statusCode()).isEqualTo(500);assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_match",Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_match_item",Integer.class)).isZero();}finally{db.execute("DROP TRIGGER t17_fail ON shopping_product_match_item");db.execute("DROP FUNCTION t17_fail()");}match(list,key,"110000",201);
    }
    @Test void 事实变化旧快照保留而新匹配拒绝失权不泄漏()throws Exception {
        String list=demand(false,true),key=UUID.randomUUID().toString();catalog("10");var first=match(list,key,"110000",201);String stored=db.queryForObject("SELECT view_json::text FROM shopping_product_match",String.class);clearInvocations(provider);db.update("UPDATE recipe SET servings=4 WHERE id=10");
        var historical=match(list,key,"110000",200);assertThat(historical.path("factsStatus").asText()).isEqualTo("CHANGED");assertThat(historical.path("costs")).isEqualTo(first.path("costs"));assertThat(f.a.post(path(list),request(UUID.randomUUID().toString(),"110000")).statusCode()).isEqualTo(409);assertThat(f.data(f.a.get(path(list)+"/latest"),200).path("factsStatus").asText()).isEqualTo("CHANGED");verifyNoInteractions(provider);assertThat(db.queryForObject("SELECT view_json::text FROM shopping_product_match",String.class)).isEqualTo(stored);
        db.update("UPDATE recipe_source SET source_type='USER_CUSTOM',owner_user_id=2,visibility='PRIVATE' WHERE id=10");var hidden=f.a.get(path(list)+"/"+first.path("id").asText());assertThat(hidden.statusCode()).isEqualTo(404);assertThat(hidden.body()).doesNotContain("番茄","Mock");assertThat(f.a.post(path(list),request(key,"110000")).statusCode()).isEqualTo(404);
    }
    @Test void 当前餐修订不改历史需求且新UUID仍用原材料数量()throws Exception {
        String list=demand(false,true),key=UUID.randomUUID().toString();catalog("10");var first=match(list,key,"110000",201);store.register(1,Long.parseLong(f.conversation),Long.parseLong(meal),"REVISE",UUID.randomUUID().toString(),"修订","{}",null,1,true);assertThat(match(list,key,"110000",200)).isEqualTo(first);var next=match(list,UUID.randomUUID().toString(),"110000",201);assertThat(next.path("costs")).isEqualTo(first.path("costs"));
    }
    @Test void 金额超列范围明确失败整单无残留()throws Exception {String list=demand(false,true);config.setProducts(List.of(new ProductProvider.Product("mock-expensive",1L,"Mock高价边界",List.of("110000"),true,new BigDecimal("0.00000001"),"G",new BigDecimal("9999999999999999.99"),"CNY",null,null)));assertThat(f.a.post(path(list),request(UUID.randomUUID().toString(),"110000")).statusCode()).isEqualTo(400);assertThat(db.queryForObject("SELECT count(*) FROM shopping_product_match",Integer.class)).isZero();}
    @Test void 部分匹配真实保存已知小计与未知项不隐藏()throws Exception {catalog("10");var view=match(demand(false,true,true),UUID.randomUUID().toString(),"110000",201);assertThat(view.path("matchingStatus").asText()).isEqualTo("PARTIAL");assertThat(view.path("lines")).hasSize(2);assertThat(view.path("costs").path("consumptionCost").isNull()).isTrue();assertThat(view.path("costs").path("knownSubtotal").asText()).isEqualTo("4.00");assertThat(view.path("costs").path("unknownItemCount").asInt()).isEqualTo(1);verify(provider).lookup(eq("110000"),argThat(needs->needs.size()==1));}
    @Test void Provider返回后独立事实改变在提交前回滚所有报价()throws Exception {
        String list=demand(false,true);catalog("10");doAnswer(call->{var offers=call.callRealMethod();var external=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));external.update("UPDATE recipe SET servings=4 WHERE id=10");return offers;}).when(provider).lookup(anyString(),anyList());
        var response=f.a.post(path(list),request(UUID.randomUUID().toString(),"110000"));assertThat(response.statusCode()).isEqualTo(409);assertThat(response.body()).contains("SHOPPING_SOURCE_CHANGED");for(String table:List.of("shopping_product_match","shopping_product_match_item","shopping_product_purchase_group"))assertThat(db.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM shopping_list",Integer.class)).isEqualTo(1);
    }
    @AfterEach void noModels(){verifyNoInteractions(gateway,embedding,retrieval);}
}
