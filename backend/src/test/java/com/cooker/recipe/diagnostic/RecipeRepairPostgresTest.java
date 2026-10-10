package com.cooker.recipe.diagnostic;

import static org.assertj.core.api.Assertions.*;
import static com.cooker.recipe.dto.repair.RecipeRepairEvidence.*;
import com.cooker.recipe.dto.repair.*;
import com.cooker.recipe.dto.repair.RecipeRepairApproval.Decision;
import com.cooker.recipe.dto.repair.RecipeRepairEvidence.Candidate;
import com.cooker.recipe.service.RecipeRepairService;
import com.cooker.recipe.service.impl.RecipeRepairServiceImpl;
import com.cooker.recipe.mapper.RecipeRepairMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** 固定公开原文/提案＋临时PG，审批仅为测试模拟，绝不应用开发库。 */
@Testcontainers
class RecipeRepairPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_d1b_test").withUsername("root").withPassword("test-only");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static RecipeRepairService service;
    static RecipeRepairEvidence evidence;
    static Map<String,Candidate> candidates;
    static List<JsonNode> snapshot;
    static String target;
    @BeforeAll static void setup() throws Exception {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
        evidence = RecipeRepairEvidence.load(Path.of(Objects.requireNonNull(RecipeRepairPostgresTest.class.getResource("/fixtures/d1-b/database-input.jsonl")).toURI()),
                Path.of(Objects.requireNonNull(RecipeRepairPostgresTest.class.getResource("/fixtures/d1-b/manifest.jsonl")).toURI()));
        candidates = evidence.candidates(); snapshot = evidence.snapshot(); target = POSTGRES.getJdbcUrl().split("\\?",2)[0];
        service = RecipeRepairTool.service(source);
    }
    @BeforeEach void seed() {
        jdbc.execute("DROP TRIGGER IF EXISTS d1b_fail_audit ON recipe_repair_entry");
        jdbc.execute("TRUNCATE recipe_repair_batch,recipe_source,ingredient,app_user RESTART IDENTITY CASCADE");
        seedFacts(jdbc);
    }
    static void seedFacts(JdbcTemplate db) {
        var dict = evidence.dictionary().values().stream().map(n -> new Object[]{n.path("id").asLong(),n.path("name").asText(),n.path("status").asText()}).toList();
        db.batchUpdate("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES(?,?,?)",dict);
        var recipes = evidence.recipes().values();
        db.batchUpdate("INSERT INTO recipe_source(id,source_type,source_key,visibility) VALUES(?,'SYSTEM_HOWTOCOOK',?,'PUBLIC')",recipes.stream().map(n -> new Object[]{n.path("sourceId").asLong(),n.path("sourceKey").asText()}).toList());
        db.batchUpdate("INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(?,?,?,?,?,'SUCCESS')",recipes.stream().map(n -> new Object[]{n.path("documentId").asLong(),n.path("sourceId").asLong(),n.path("sourceVersion").asText(),n.path("contentHash").asText(),n.path("rawMarkdown").asText()}).toList());
        db.batchUpdate("INSERT INTO recipe(id,source_id,source_document_id,name,category_code,servings) VALUES(?,?,?,?,?,?)",recipes.stream().map(n -> new Object[]{n.path("recipeId").asLong(),n.path("sourceId").asLong(),n.path("documentId").asLong(),n.path("name").asText(),n.path("sourceKey").asText().split("/")[0],integer(n.path("servings"))}).toList());
        List<Object[]> lines = new ArrayList<>();
        for (JsonNode r : recipes) for (JsonNode n : r.path("ingredients")) lines.add(new Object[]{n.path("id").asLong(),n.path("recipe_id").asLong(),id(n.path("ingredient_id")),n.path("raw_name").asText(),decimal(n.path("amount_min")),decimal(n.path("amount_max")),text(n.path("unit_code")),text(n.path("unit_label")),n.path("optional").asBoolean(),n.path("raw_line").asText(),n.path("sort_order").asInt()});
        db.batchUpdate("INSERT INTO recipe_ingredient(id,recipe_id,ingredient_id,raw_name,amount_min,amount_max,unit_code,unit_label,optional,raw_line,sort_order) VALUES(?,?,?,?,?,?,?,?,?,?,?)",lines);
        db.execute("SELECT setval(pg_get_serial_sequence('ingredient','id'),(SELECT max(id) FROM ingredient))");
        db.execute("SELECT setval(pg_get_serial_sequence('recipe_ingredient','id'),(SELECT max(id) FROM recipe_ingredient))");
        db.execute("INSERT INTO app_user(id,username,password_hash) VALUES(1,'d1b_test_only','test-only')");
        db.execute("INSERT INTO favorite_recipe(user_id,recipe_id) VALUES(1,264)");
        db.execute("INSERT INTO recipe_cook_record(user_id,recipe_id,idempotency_key,rating) VALUES(1,264,gen_random_uuid(),5)");
        db.execute("INSERT INTO conversation(id,user_id,title) VALUES(1,1,'隔离历史')");
        db.execute("INSERT INTO meal_session(id,conversation_id,user_id,status,view_json) VALUES(1,1,1,'OPTIONS_READY','{\"历史\":\"不可覆写\"}')");
        db.execute("INSERT INTO meal_option(id,meal_id,conversation_id,user_id,version,ordinal,facts) VALUES(1,1,1,1,1,1,'{\"recipeId\":264,\"fingerprint\":\"旧事实\"}')");
        db.execute("INSERT INTO meal_action(id,meal_id,conversation_id,user_id,version,request_id,type,status,input_text,parameters,input_hash,deadline_at,started_at,finished_at,result) VALUES(1,1,1,1,1,gen_random_uuid(),'GUIDE','SUCCEEDED','隔离历史','{}',repeat('a',64),clock_timestamp(),clock_timestamp(),clock_timestamp(),'{\"旧结果\":true}')");
        db.execute("INSERT INTO meal_guide(id,user_id,conversation_id,meal_id,version,option_id,action_id,persona_version,prompt_version,view_json) VALUES(1,1,1,1,1,1,1,'fixture','fixture','{\"旧指导\":true}')");
        db.execute("INSERT INTO shopping_list(id,user_id,conversation_id,meal_id,version,option_id,request_id,input_hash,source_fingerprint,status,budget_status,unknown_item_count,evidence_json,view_json) VALUES(1,1,1,1,1,1,gen_random_uuid(),repeat('a',64),repeat('a',64),'DRAFT','UNKNOWN',1,'{\"旧材料\":true}','{\"旧清单\":true}')");
        db.execute("INSERT INTO shopping_product_match(id,user_id,list_id,request_id,input_hash,source_fingerprint,city_code,catalog_version,matching_status,unknown_item_count,known_subtotal,estimated_known_subtotal,estimated_unknown_item_count,budget_status,view_json) VALUES(1,1,1,gen_random_uuid(),repeat('a',64),repeat('a',64),'110000','fixture','NO_MATCH',1,0,0,1,'UNKNOWN','{\"旧报价\":true}')");
        db.execute("INSERT INTO recipe_search_document(recipe_id,source_document_id,source_type,visibility,chunk_type,chunk_no,content,content_hash,document_template_version,embedding_model,embedding_dimension,embedding_status) VALUES(264,264,'SYSTEM_HOWTOCOOK','PUBLIC','ingredients',0,'旧检索投影',repeat('a',64),1,'test-only',1024,'PENDING')");
    }
    static Decision decision(Candidate c,String mode,Long identity,String name) {
        return new Decision(c.key(),c.hash(),"APPROVE","仅临时PG模拟逐ID人工审定",mode,identity,name,c.addedSortOrder(),c.category().equals("新增漏料"),c.category().equals("移除误解析"));
    }
    static RecipeRepairApproval approval(Decision... selected) {
        var template = evidence.approvalTemplate(); Map<String,Decision> choices = new HashMap<>();for(var d:selected)choices.put(d.candidateKey(),d);
        var decisions = template.decisions().stream().map(d -> choices.getOrDefault(d.candidateKey(),d)).toList();
        return seal(new RecipeRepairApproval(template.schemaVersion(),template.ruleVersion(),template.inputHash(),template.manifestHash(),template.reviewHash(),UUID.randomUUID(),POSTGRES.getDatabaseName(),target,"临时测试审批",List.of("APPLY","ROLLBACK"),decisions,null));
    }
    static RecipeRepairApproval seal(RecipeRepairApproval a) { return new RecipeRepairApproval(a.schemaVersion(),a.ruleVersion(),a.inputHash(),a.manifestHash(),a.reviewHash(),a.requestKey(),a.targetDatabase(),a.targetIdentifier(),a.reviewer(),a.allowedActions(),a.decisions(),approvalHash(a)); }
    static Candidate one(String category) {return candidates.values().stream().filter(c -> c.category().equals(category)).findFirst().orElseThrow();}
    static Candidate people() {return candidates.get("PEOPLE:264");}
    static Long id(JsonNode n) {return n.isNull()||n.isMissingNode()?null:n.asLong();}
    static Integer integer(JsonNode n) {return n.isNull()?null:n.asInt();}
    static String text(JsonNode n) {return n.isNull()?null:n.asText();}
    static BigDecimal decimal(JsonNode n) {return n.isNull()?null:new BigDecimal(n.asText());}
    static String rows(String table) {return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM "+table+" t",String.class);}
    static String facts() {return rows("recipe")+rows("recipe_ingredient")+rows("ingredient");}
    static void receipt(String scenario,RecipeRepairResult result) {System.out.println("D1B_TEMP_RECEIPT|"+scenario+"|"+JSON.valueToTree(result));}
    static String history() {return rows("recipe_source_document")+rows("favorite_recipe")+rows("recipe_cook_record")+rows("meal_option")+rows("meal_guide")+rows("shopping_list")+rows("shopping_product_match")+rows("recipe_search_document");}
    @Test void 固定证据及待审包逐ID完整且全部待审() throws Exception {
        assertThat(candidates).hasSize(1416);assertThat(evidence.validate(approval(decision(people(),null,null,null)))).hasSize(1);
        Path folder=Files.createTempDirectory("d1b-review-");RecipeRepairReview.write(evidence,folder);
        var root=JSON.readTree(Files.readString(folder.resolve("review.json")));assertThat(root.path("applied").asBoolean()).isFalse();
        assertThat(root.path("candidates").size()).isEqualTo(1416);
        assertThat(evidence.approvalTemplate().decisions()).allMatch(d -> d.decision().equals("DEFER"));
        assertThat(root.path("candidates").findValues("category").stream().filter(n -> n.asText().equals("词典冲突"))).hasSize(52);
        assertThat(candidates.values().stream().filter(c -> c.evidence().path("uncertainMaterial").asBoolean())).hasSize(530);
    }
    @Test void 输入或提案字节漂移拒绝() throws Exception {
        Path input=Files.createTempFile("d1b-changed-",".jsonl");Files.writeString(input,"{}");
        assertThatThrownBy(() -> RecipeRepairEvidence.load(input,Path.of(getClass().getResource("/fixtures/d1-b/manifest.jsonl").toURI()))).hasMessageContaining("hash");
    }
    @Test void 未批准空审批及内容防篡改拒绝且零写() throws Exception {
        String before=facts();assertThatThrownBy(() -> service.apply(evidence,evidence.approvalTemplate())).isInstanceOf(IllegalArgumentException.class);
        var a=approval();assertThatThrownBy(() -> service.apply(evidence,a)).hasMessageContaining("没有获准");
        var valid=approval(decision(people(),null,null,null));var node=JSON.valueToTree(valid);((com.fasterxml.jackson.databind.node.ObjectNode)node).put("reviewer","篡改");
        assertThatThrownBy(() -> service.apply(evidence,JSON.treeToValue(node,RecipeRepairApproval.class))).hasMessageContaining("内容hash");
        assertThat(facts()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isZero();
    }
    @Test void 审批不能添加任意事实字段() throws Exception {
        var node=JSON.valueToTree(approval(decision(people(),null,null,null)));((com.fasterxml.jackson.databind.node.ObjectNode)node.path("decisions").get(0)).put("newFields","任意覆写");
        assertThatThrownBy(() -> JSON.treeToValue(node,RecipeRepairApproval.class)).isInstanceOf(Exception.class);
    }
    @Test void 证据返回值不能被调用方修改固定提案() {
        var c=evidence.candidates().get(people().key());c.evidence().put("newServings",999);
        var a=approval(decision(people(),null,null,null));service.apply(evidence,a);
        assertThat(jdbc.queryForObject("SELECT servings FROM recipe WHERE id=264",Integer.class)).isEqualTo(2);
    }
    @Test void 默认dryRun只读零审计零事实写入() {
        String before=facts();var out=service.dryRun(evidence,approval(decision(people(),null,null,null)));
        assertThat(out.status()).isEqualTo("DRY_RUN");assertThat(out.applied()).isFalse();assertThat(facts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isZero();
    }
    @Test void 准确目标库和主机端口均拒绝错配() {
        var a=approval(decision(people(),null,null,null));var node=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(a);
        node.put("targetIdentifier",target + "_wrong_database");
        var wrong=seal(JSON.convertValue(node,RecipeRepairApproval.class));assertThatThrownBy(() -> service.apply(evidence,wrong)).hasMessageContaining("审批目标");
    }
    @Test void 人数策略应用回滚保留原文历史投影与原主键() {
        var a=approval(decision(people(),null,null,null));String original=facts(), protectedRows=history();
        var out=service.apply(evidence,a);receipt("人数",out);assertThat(out.recipeIds()).containsExactly(264L);
        assertThat(jdbc.queryForObject("SELECT servings FROM recipe WHERE id=264",Integer.class)).isEqualTo(2);
        assertThat(history()).isEqualTo(protectedRows);assertThat(service.rollback(evidence,a).status()).isEqualTo("ROLLED_BACK");
        assertThat(facts()).isEqualTo(original);assertThat(history()).isEqualTo(protectedRows);
    }
    @ParameterizedTest @ValueSource(longs={153,177,180,192})
    void 四个真实约人数仍为2且保留依据(long recipe) {
        var preview=new RecipeParsePreviewTool().preview(snapshot).stream().filter(n -> n.path("kind").asText().equals("recipe")&&n.path("recipeId").asLong()==recipe).findFirst().orElseThrow();
        assertThat(preview.path("newServings").asInt()).isEqualTo(2);assertThat(preview.path("servingsBasis").asText()).isEqualTo("APPROXIMATE_PEOPLE_AS_BASELINE");
    }
    @ParameterizedTest @ValueSource(strings={"原文","结构化材料","原行","排序","人数","来源","私有","词典"})
    void 应用前任一来源或结构化漂移整批拒绝(String drift) {
        Candidate c=one("名称数量单位");long rid=c.evidence().path("recipeId").asLong(),line=c.evidence().path("lineId").asLong();
        var a=approval(decision(c,"KEEP",null,null),decision(people(),null,null,null));
        switch(drift) {
            case "原文" -> jdbc.update("UPDATE recipe_source_document SET raw_markdown=raw_markdown||'漂移' WHERE id=?",c.evidence().path("documentId").asLong());
            case "结构化材料" -> jdbc.update("UPDATE recipe_ingredient SET raw_name='后续修改' WHERE id=?",line);
            case "原行" -> jdbc.update("UPDATE recipe_ingredient SET raw_line='后续原行' WHERE id=?",line);
            case "排序" -> jdbc.update("UPDATE recipe_ingredient SET sort_order=100000 WHERE id=?",line);
            case "人数" -> jdbc.update("UPDATE recipe SET servings=123 WHERE id=?",rid);
            case "来源" -> jdbc.update("UPDATE recipe_source_document SET source_version='后续版本' WHERE id=?",c.evidence().path("documentId").asLong());
            case "私有" -> jdbc.update("UPDATE recipe_source SET source_type='USER_CUSTOM',visibility='PRIVATE',owner_user_id=1 WHERE id=?",c.evidence().path("sourceId").asLong());
            case "词典" -> jdbc.update("UPDATE ingredient SET canonical_name='后续规范名' WHERE id=?",c.evidence().path("oldIngredientId").asLong());
        }
        String before=facts();assertThatThrownBy(() -> service.apply(evidence,a)).isInstanceOf(IllegalArgumentException.class);
        assertThat(facts()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isZero();
    }
    @Test void 原材料字段修复保留旧ID及未批准行且可回滚() {
        Candidate c=one("名称数量单位");var a=approval(decision(c,"KEEP",null,null));String before=facts(),protectedRows=history();
        var out=service.apply(evidence,a);receipt("旧行字段",out);assertThat(out.lineIdMappings().get(c.key())).isEqualTo(c.evidence().path("lineId").asLong());
        assertThat(history()).isEqualTo(protectedRows);service.rollback(evidence,a);assertThat(facts()).isEqualTo(before);
    }
    @Test void 新增漏料固定追加顺序不移动旧行且完整回滚() {
        Candidate c=one("新增漏料");var a=approval(decision(c,"KEEP",null,null));String before=facts();
        var out=service.apply(evidence,a);receipt("新增关联",out);long id=out.lineIdMappings().get(c.key());assertThat(id).isPositive();
        assertThat(jdbc.queryForObject("SELECT sort_order FROM recipe_ingredient WHERE id=?",Integer.class,id)).isEqualTo(c.addedSortOrder());
        service.rollback(evidence,a);assertThat(facts()).isEqualTo(before);
    }
    @Test void 明确误解析移除必须单独确认并原ID恢复() {
        Candidate c=one("移除误解析");var valid=decision(c,null,null,null);var bad=new Decision(valid.candidateKey(),valid.candidateHash(),valid.decision(),valid.reason(),null,null,null,null,false,false);
        assertThatThrownBy(() -> service.apply(evidence,approval(bad))).hasMessageContaining("非材料");
        String before=facts();var a=approval(valid);var out=service.apply(evidence,a);receipt("移除误解析",out);assertThat(out.lineIdMappings()).doesNotContainKey(c.key());assertThat(jdbc.queryForObject("SELECT resulting_line_id FROM recipe_repair_entry WHERE candidate_key=?",Long.class,c.key())).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_ingredient WHERE id=?",Long.class,c.evidence().path("lineId").asLong())).isZero();
        service.rollback(evidence,a);assertThat(facts()).isEqualTo(before);
    }
    @Test void 新增排序不能任意指定撞旧序号() {
        Candidate c=candidates.values().stream().filter(n -> n.category().equals("新增漏料") && n.addedSortOrder()>1).findFirst().orElseThrow();var d=decision(c,"KEEP",null,null);var bad=new Decision(d.candidateKey(),d.candidateHash(),d.decision(),d.reason(),d.identityMode(),d.ingredientId(),d.canonicalName(),1,true,false);
        assertThatThrownBy(() -> service.apply(evidence,approval(bad))).hasMessageContaining("固定追加排序");
    }
    static Candidate creation() {
        Set<String> names=new HashSet<>();evidence.dictionary().values().forEach(d -> names.add(d.path("name").asText()));
        return candidates.values().stream().filter(c -> c.category().equals("名称数量单位") && !c.evidence().path("newFields").path("name").asText().isBlank() && !names.contains(c.evidence().path("newFields").path("name").asText()) && !groupContains(id(c.evidence().path("oldIngredientId")))).findFirst().orElseThrow();
    }
    static boolean groupContains(Long id) {return id!=null&&candidates.values().stream().filter(c -> c.category().equals("词典冲突")).anyMatch(c -> c.evidence().path("originalIdsAndNames").findValues("id").stream().anyMatch(n -> n.asLong()==id));}
    @Test void 新规范身份显式创建保留旧ID且无依赖可回滚() {
        Candidate c=creation();var a=approval(decision(c,"CREATE",null,c.evidence().path("newFields").path("name").asText()));String before=facts();
        var out=service.apply(evidence,a);receipt("新标准身份",out);assertThat(out.createdIngredientIds()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE id=?",Long.class,c.evidence().path("oldIngredientId").asLong())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT normalization_status FROM ingredient WHERE id=?",String.class,out.createdIngredientIds().getFirst())).isEqualTo("UNREVIEWED");
        service.rollback(evidence,a);assertThat(facts()).isEqualTo(before);
    }
    static List<Decision> collisionMapping() {
        for(Candidate g:candidates.values()) if(g.category().equals("词典冲突")) {
            var proper=evidence.dictionary().values().stream().filter(i -> i.path("name").asText().equals(g.evidence().path("proposedName").asText())).findFirst();if(proper.isEmpty())continue;
            long target=proper.get().path("id").asLong();Set<Long> oldIds=new HashSet<>();g.evidence().path("originalIdsAndNames").forEach(n -> oldIds.add(n.path("id").asLong()));
            var row=candidates.values().stream().filter(c -> c.category().equals("名称数量单位")&&oldIds.contains(c.evidence().path("oldIngredientId").asLong())&&c.evidence().path("oldIngredientId").asLong()!=target).findFirst();
            if(row.isPresent())return List.of(decision(g,"REUSE",target,null),decision(row.get(),"REUSE",target,null));
        }throw new IllegalStateException("固定公开fixture缺少映射例");
    }
    @Test void 词典冲突逐组与逐行批准复用规范ID且旧ID保留() {
        var selected=collisionMapping();var a=approval(selected.toArray(Decision[]::new));String before=facts();
        assertThatThrownBy(() -> service.apply(evidence,approval(selected.get(1)))).hasMessageContaining("冲突组");
        var out=service.apply(evidence,a);receipt("逐组逐行复用",out);assertThat(out.createdIngredientIds()).isEmpty();service.rollback(evidence,a);assertThat(facts()).isEqualTo(before);
    }
    @Test void 规范改名影响未批准引用时拒绝() {
        Candidate c=creation();long old=c.evidence().path("oldIngredientId").asLong();
        jdbc.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,optional,raw_line,sort_order) VALUES(264,?,'未批准引用',false,'未批准引用',100000)",old);
        var a=approval(decision(c,"RENAME",old,c.evidence().path("newFields").path("name").asText()));
        assertThatThrownBy(() -> service.apply(evidence,a)).hasMessageContaining("未批准");
    }
    @Test void 规范名称唯一冲突拒绝而非盲目新建() {
        Candidate c=creation();String name=c.evidence().path("newFields").path("name").asText();jdbc.update("INSERT INTO ingredient(canonical_name,normalization_status) VALUES(?,'UNREVIEWED')",name);
        assertThatThrownBy(() -> service.apply(evidence,approval(decision(c,"CREATE",null,name)))).hasMessageContaining("已存在");
    }
    @Test void 审计后置异常事实与批次条目全部回滚() {
        Candidate c=one("名称数量单位");String before=facts();
        jdbc.execute("CREATE OR REPLACE FUNCTION d1b_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '测试注入审计失败'; END $$");
        jdbc.execute("CREATE TRIGGER d1b_fail_audit BEFORE INSERT ON recipe_repair_entry FOR EACH ROW EXECUTE FUNCTION d1b_fail_audit()");
        assertThatThrownBy(() -> service.apply(evidence,approval(decision(c,"KEEP",null,null)))).isInstanceOf(RuntimeException.class);
        assertThat(facts()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_entry",Long.class)).isZero();
    }
    @Test void 同键同hash幂等且异内容拒绝() {
        var a=approval(decision(people(),null,null,null));var first=service.apply(evidence,a);String after=facts();
        assertThat(service.apply(evidence,a)).isEqualTo(first);assertThat(facts()).isEqualTo(after);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isEqualTo(1);
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(a);node.put("reviewer","另一个审批内容");
        assertThatThrownBy(() -> service.apply(evidence,seal(JSON.convertValue(node,RecipeRepairApproval.class)))).hasMessageContaining("同请求键异内容");
    }
    @Test void 并发同键重放只有一次写入和同一审计映射() throws Exception {
        Candidate c=one("新增漏料");var a=approval(decision(c,"KEEP",null,null));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<RecipeRepairResult> work=() -> {gate.await();return service.apply(evidence,a);};
            var left=pool.submit(work);var right=pool.submit(work);gate.countDown();assertThat(left.get(30,TimeUnit.SECONDS)).isEqualTo(right.get(30,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_entry",Long.class)).isEqualTo(1);
    }
    @Test void 已回滚重放不重新应用且重复rollback原结果() {
        var a=approval(decision(people(),null,null,null));service.apply(evidence,a);var back=service.rollback(evidence,a);String restored=facts();
        assertThat(service.rollback(evidence,a)).isEqualTo(back);assertThat(service.apply(evidence,a)).isEqualTo(back);assertThat(facts()).isEqualTo(restored);
    }
    @ParameterizedTest @ValueSource(strings={"材料","人数","原文","新身份引用","新别名","历史"})
    void 回滚拒绝后续事实身份或历史依赖(String update) {
        Candidate c=creation();var a=approval(decision(c,"CREATE",null,c.evidence().path("newFields").path("name").asText()),decision(people(),null,null,null));var out=service.apply(evidence,a);
        long line=c.evidence().path("lineId").asLong(),identity=out.createdIngredientIds().getFirst();
        switch(update) {
            case "材料" -> jdbc.update("UPDATE recipe_ingredient SET raw_name='后续材料' WHERE id=?",line);
            case "人数" -> jdbc.execute("UPDATE recipe SET servings=5 WHERE id=264");
            case "原文" -> jdbc.execute("UPDATE recipe_source_document SET raw_markdown=raw_markdown||'后续' WHERE id=264");
            case "新身份引用" -> jdbc.update("INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,optional,raw_line,sort_order) VALUES(300,?,'后续引用',false,'后续引用',100000)",identity);
            case "新别名" -> jdbc.update("INSERT INTO ingredient_alias(ingredient_id,alias_name) VALUES(?,'后续别名')",identity);
            case "历史" -> jdbc.execute("UPDATE meal_guide SET view_json='{\"后续指导\":true}' WHERE id=1");
        }
        String before=facts();assertThatThrownBy(() -> service.rollback(evidence,a)).isInstanceOf(IllegalArgumentException.class);assertThat(facts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM recipe_repair_batch",String.class)).isEqualTo("APPLIED");
    }
    @Test void 重复迁移不重复执行且已存在事实历史保留() {
        var flyway=Flyway.configure().dataSource(source).load();String before=facts(),protectedRows=history();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Long.class)).isEqualTo(15);
        assertThat(facts()).isEqualTo(before);assertThat(history()).isEqualTo(protectedRows);
    }
    @Test void V14到V15真实升级仅增加审计表且保留非空历史与事实() {
        // 新数据库只存在于本类拥有的临时容器，绝不使用开发连接。
        jdbc.execute("CREATE DATABASE cooker_d1b_upgrade");
        try {
            var upgradeSource = new DriverManagerDataSource(target.substring(0,target.lastIndexOf('/')+1)+"cooker_d1b_upgrade",POSTGRES.getUsername(),POSTGRES.getPassword());
            var db = new JdbcTemplate(upgradeSource);
            assertThat(Flyway.configure().dataSource(upgradeSource).target("14").load().migrate().migrationsExecuted).isEqualTo(14);
            seedFacts(db);
            List<String> tables = List.of("recipe","recipe_ingredient","ingredient","recipe_source_document","favorite_recipe","recipe_cook_record","meal_option","meal_guide","shopping_list","shopping_product_match","recipe_search_document");
            var before = tables.stream().map(table -> db.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "+table+" t",String.class)).toList();
            assertThat(Flyway.configure().dataSource(upgradeSource).load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(tables.stream().map(table -> db.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "+table+" t",String.class)).toList()).isEqualTo(before);
            assertThat(db.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM recipe_repair_entry",Long.class)).isZero();
        } finally { jdbc.execute("DROP DATABASE cooker_d1b_upgrade WITH (FORCE)"); }
    }
    @Test void 单引用规范身份显式改名保持ID状态且完整回滚() {
        Set<Long> groups = new HashSet<>();for(var g:candidates.values())if(g.category().equals("词典冲突"))g.evidence().path("originalIdsAndNames").forEach(n -> groups.add(n.path("id").asLong()));
        var names = evidence.dictionary().values().stream().map(n -> n.path("name").asText()).toList();
        Candidate c = candidates.values().stream().filter(n -> n.category().equals("名称数量单位") && !n.evidence().path("oldIngredientId").isNull() && !groups.contains(n.evidence().path("oldIngredientId").asLong()) && !names.contains(n.evidence().path("newFields").path("name").asText()) && jdbc.queryForObject("SELECT count(*) FROM recipe_ingredient WHERE ingredient_id=?",Long.class,n.evidence().path("oldIngredientId").asLong())==1).findFirst().orElseThrow();
        long identity=c.evidence().path("oldIngredientId").asLong();String before=facts(),protectedRows=history();String status=jdbc.queryForObject("SELECT normalization_status FROM ingredient WHERE id=?",String.class,identity);
        var a=approval(decision(c,"RENAME",identity,c.evidence().path("newFields").path("name").asText()));receipt("显式改名",service.apply(evidence,a));
        assertThat(jdbc.queryForObject("SELECT canonical_name FROM ingredient WHERE id=?",String.class,identity)).isEqualTo(c.evidence().path("newFields").path("name").asText());
        assertThat(jdbc.queryForObject("SELECT normalization_status FROM ingredient WHERE id=?",String.class,identity)).isEqualTo(status);assertThat(history()).isEqualTo(protectedRows);
        service.rollback(evidence,a);assertThat(facts()).isEqualTo(before);
    }
    @Test void 后置数据库唯一约束异常使新增身份事实审计整批回滚() {
        Candidate c=creation();String before=facts();
        // 模拟前置查询通过后发生的规范名称唯一冲突，验证真实PG事务异常路径。
        jdbc.execute("CREATE OR REPLACE FUNCTION d1b_unique_collision() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE unique_violation USING MESSAGE='测试注入并发唯一冲突'; END $$");
        jdbc.execute("CREATE TRIGGER d1b_unique_collision BEFORE INSERT ON ingredient FOR EACH ROW EXECUTE FUNCTION d1b_unique_collision()");
        try {
            assertThatThrownBy(() -> service.apply(evidence,approval(decision(c,"CREATE",null,c.evidence().path("newFields").path("name").asText())))).isInstanceOf(RuntimeException.class);
            assertThat(facts()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_batch",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_repair_entry",Long.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER d1b_unique_collision ON ingredient"); }
    }
}
