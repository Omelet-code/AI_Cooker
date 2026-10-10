package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.repair.*;
import com.cooker.recipe.dto.repair.RecipeRepairApproval.Decision;
import com.cooker.recipe.dto.repair.RecipeRepairEvidence.Candidate;
import com.cooker.recipe.entity.RecipeRepairBatchEntity;
import com.cooker.recipe.mapper.RecipeRepairMapper;
import com.cooker.recipe.service.RecipeRepairService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.cooker.recipe.dto.repair.RecipeRepairEvidence.*;

/** 事务内锁定、重验、写事实和审计；显式回滚同样先校验after前置条件。 */
@Service
public class RecipeRepairServiceImpl implements RecipeRepairService {
    private final RecipeRepairMapper mapper;
    private final PlatformTransactionManager transactions;
    public RecipeRepairServiceImpl(RecipeRepairMapper mapper, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.transactions = transactions;
    }
    @Override public RecipeRepairResult dryRun(RecipeRepairEvidence evidence, RecipeRepairApproval approval) {
        var selected = evidence.validate(approval);
        var tx = new TransactionTemplate(transactions); tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            target(approval); Plan plan = validatePlan(evidence, selected, false);
            return new RecipeRepairResult(approval.requestKey(), "DRY_RUN", false, List.copyOf(plan.beforeRecipes.keySet()), Map.of(), List.of(), selected.size());
        });
    }
    @Override public RecipeRepairResult apply(RecipeRepairEvidence evidence, RecipeRepairApproval approval) {
        var selected = evidence.validate(approval);
        require(approval.allowedActions().contains("APPLY"), "审批未允许APPLY");
        return new TransactionTemplate(transactions).execute(status -> {
            target(approval); mapper.lockGovernance();
            var prior = mapper.batch(approval.requestKey().toString());
            if (prior != null) { sameBatch(prior, approval); return result(prior.resultJson); }
            mapper.lockProtection();
            Plan plan = validatePlan(evidence, selected, true);
            String protection = mapper.protectionDigests();
            Map<String, Long> createdByName = new TreeMap<>(), mapping = new LinkedHashMap<>();
            List<Long> createdIds = new ArrayList<>();
            // 身份操作在所有前置校验之后；CREATE受唯一约束保护，失败回滚整批。
            for (var d : selected) {
                if ("RENAME".equals(d.identityMode())) {
                    require(mapper.renameIdentity(d.ingredientId(), d.canonicalName()) == 1, "规范身份改名失败");
                } else if ("CREATE".equals(d.identityMode()) && !createdByName.containsKey(d.canonicalName())) {
                    Long id = mapper.createIdentity(d.canonicalName()); createdByName.put(d.canonicalName(), id); createdIds.add(id);
                }
            }
            for (var d : selected) {
                Candidate c = plan.candidates.get(d.candidateKey()); JsonNode n = c.evidence();
                if (d.candidateKey().startsWith("CONFLICT:")) continue;
                long rid = n.path("recipeId").asLong();
                if (d.candidateKey().startsWith("PEOPLE:")) {
                    require(mapper.servings(rid, integer(n.path("newServings"))) == 1, "人数更新失败"); continue;
                }
                Long lineId = nullableLong(n.path("lineId"));
                if (c.category().equals("移除误解析")) require(mapper.removeLine(lineId, rid) == 1, "原误解析行删除失败");
                else {
                    JsonNode fields = n.path("newFields");
                    Long identity = switch (d.identityMode()) {
                        case "KEEP" -> nullableLong(n.path("oldIngredientId"));
                        case "REUSE", "RENAME" -> d.ingredientId();
                        case "CREATE" -> createdByName.get(d.canonicalName());
                        default -> throw new IllegalArgumentException("身份动作未知");
                    };
                    if (lineId == null) {
                        lineId = mapper.addLine(rid, identity, fields.path("name").asText(), decimal(fields.path("amountMin")), decimal(fields.path("amountMax")), text(fields.path("unitCode")), text(fields.path("unitLabel")), fields.path("optional").asBoolean(), n.path("rawLine").asText(), c.addedSortOrder());
                    } else require(change(lineId, rid, identity, fields) == 1, "原材料更新失败");
                }
                // 移除后的关联不存在；原行ID保存在审计original_line_id，不能伪装成执行后ID。
                if (lineId != null && !c.category().equals("移除误解析")) mapping.put(c.key(), lineId);
            }
            var afterRecipes = captureRecipes(plan.beforeRecipes.keySet(), false);
            Set<Long> allIdentityIds = new TreeSet<>(plan.beforeIdentities.keySet()); allIdentityIds.addAll(createdIds);
            require(equal(read(protection), read(mapper.protectionDigests())), "受保护历史或投影发生并发变化，拒绝整批应用");
            ObjectNode state = JSON.createObjectNode(); state.set("protectionDigests", read(protection)); state.set("beforeRecipes", JSON.valueToTree(plan.beforeRecipes));
            state.set("afterRecipes", JSON.valueToTree(afterRecipes)); state.set("beforeIdentities", JSON.valueToTree(plan.beforeIdentities));
            state.set("afterIdentities", JSON.valueToTree(captureIdentities(allIdentityIds, false)));
            state.set("decisions", JSON.valueToTree(selected)); state.set("createdIngredientIds", JSON.valueToTree(createdIds));
            state.set("lineIdMappings", JSON.valueToTree(mapping));
            RecipeRepairResult outcome = new RecipeRepairResult(approval.requestKey(), "APPLIED", true, List.copyOf(plan.beforeRecipes.keySet()), mapping, createdIds, selected.size());
            RecipeRepairBatchEntity batch = new RecipeRepairBatchEntity(); batch.requestKey = approval.requestKey().toString();
            batch.inputHash = INPUT_HASH; batch.manifestHash = MANIFEST_HASH; batch.approvalHash = approval.approvalHash();
            batch.ruleVersion = RULE; batch.targetDatabase = approval.targetDatabase(); batch.targetIdentifier = approval.targetIdentifier(); batch.reviewer = approval.reviewer(); batch.status = "APPLIED";
            batch.stateJson = state.toString(); batch.resultJson = tree(outcome).toString(); require(mapper.insertBatch(batch) == 1, "批次审计失败");
            for (var d : selected) {
                Candidate c = plan.candidates.get(d.candidateKey()); JsonNode n = c.evidence();
                require(mapper.audit(batch.id, c.key(), nullableLong(n.path("sourceId")), nullableLong(n.path("recipeId")), nullableLong(n.path("lineId")), mapping.get(c.key()), c.category(), n.has("oldFields") ? n.path("oldFields").toString() : n.has("oldServings") ? n.path("oldServings").toString() : n.path("originalIdsAndNames").toString(), n.has("newFields") ? n.path("newFields").toString() : n.has("newServings") ? n.path("newServings").toString() : tree(d).toString(), tree(d).toString()) == 1, "条目审计失败");
            }
            return outcome;
        });
    }
    @Override public RecipeRepairResult rollback(RecipeRepairEvidence evidence, RecipeRepairApproval approval) {
        evidence.validate(approval); require(approval.allowedActions().contains("ROLLBACK"), "审批未允许ROLLBACK");
        return new TransactionTemplate(transactions).execute(status -> {
            target(approval); mapper.lockGovernance(); var batch = mapper.batch(approval.requestKey().toString());
            require(batch != null, "找不到本次成功应用批次"); sameBatch(batch, approval);
            if (batch.status.equals("ROLLED_BACK")) return result(batch.resultJson);
            require(batch.status.equals("APPLIED"), "只能回滚成功应用批次");
            mapper.lockProtection();
            JsonNode saved = read(batch.stateJson);
            require(equal(read(mapper.protectionDigests()), saved.path("protectionDigests")), "受保护历史/投影有后续变化或新依赖，拒绝回滚");
            var ids = longs(saved.path("afterRecipes").fieldNames());
            require(equal(tree(captureRecipes(ids, true)), saved.path("afterRecipes")), "应用后事实或原文发生后续修改，拒绝回滚");
            var identityIds = longs(saved.path("afterIdentities").fieldNames());
            require(equal(tree(captureIdentities(identityIds, true)), saved.path("afterIdentities")), "身份或引用有后续变化，拒绝回滚");
            List<Decision> decisions = JSON.convertValue(saved.path("decisions"), JSON.getTypeFactory().constructCollectionType(List.class, Decision.class));
            Map<String, Candidate> candidates = evidence.candidates();
            // 先删本批新增关联，再恢复原行和人数，避免追加排序/旧ID冲突。
            for (Decision d : decisions) if (d.candidateKey().startsWith("ADD:")) {
                long rid = candidates.get(d.candidateKey()).evidence().path("recipeId").asLong();
                require(mapper.removeLine(saved.path("lineIdMappings").path(d.candidateKey()).asLong(), rid) == 1, "本批新增行回滚失败");
            }
            for (Decision d : decisions) {
                Candidate c = candidates.get(d.candidateKey()); JsonNode n = c.evidence(); long rid = n.path("recipeId").asLong();
                if (d.candidateKey().startsWith("PEOPLE:")) { require(mapper.servings(rid, integer(n.path("oldServings"))) == 1, "人数恢复失败"); continue; }
                if (!d.candidateKey().startsWith("LINE:")) continue;
                long id = n.path("lineId").asLong(); JsonNode original = null;
                for (JsonNode line : saved.path("beforeRecipes").path(Long.toString(rid)).path("lines")) if (line.path("id").asLong() == id) original = line;
                require(original != null, "审计缺少原行");
                if (c.category().equals("移除误解析")) require(restore(original) == 1, "原误解析行恢复失败");
                else require(change(id, rid, nullableLong(original.path("ingredient_id")), oldFields(original)) == 1, "材料原事实恢复失败");
            }
            for (JsonNode id : saved.path("createdIngredientIds")) {
                require(mapper.identityReferences(id.asLong()).isEmpty() && mapper.aliases(id.asLong()).isEmpty(), "新身份仍有外部依赖，拒绝删除");
                require(mapper.deleteIdentity(id.asLong()) == 1, "本批新规范身份删除失败");
            }
            saved.path("beforeIdentities").fields().forEachRemaining(e -> {
                JsonNode identity = e.getValue().path("identity");
                require(mapper.renameIdentity(Long.parseLong(e.getKey()), identity.path("canonical_name").asText()) == 1, "原规范名称恢复失败");
            });
            require(equal(tree(captureRecipes(ids, false)), saved.path("beforeRecipes")), "回滚后事实不等于原审计");
            require(equal(tree(captureIdentities(longs(saved.path("beforeIdentities").fieldNames()), false)), saved.path("beforeIdentities")), "回滚后身份不等于原审计");
            require(equal(read(mapper.protectionDigests()), saved.path("protectionDigests")), "回滚期间受保护对象并发变化，拒绝整批");
            RecipeRepairResult old = result(batch.resultJson);
            RecipeRepairResult out = new RecipeRepairResult(old.requestKey(), "ROLLED_BACK", false, old.recipeIds(), old.lineIdMappings(), old.createdIngredientIds(), old.approvedCandidates());
            require(mapper.rolledBack(batch.id, tree(out).toString()) == 1, "回滚批次状态失败"); return out;
        });
    }
    private record Plan(Map<Long, ObjectNode> beforeRecipes, Map<Long, ObjectNode> beforeIdentities, Map<String, Candidate> candidates) { }
    private Plan validatePlan(RecipeRepairEvidence evidence, List<Decision> selected, boolean lock) {
        Map<String, Candidate> candidates = evidence.candidates(); Map<Long, JsonNode> baseline = evidence.recipes(), dictionary = evidence.dictionary();
        Set<Long> recipeIds = new TreeSet<>(), identityIds = new TreeSet<>(), approvedLineIds = new HashSet<>();
        Map<String, Decision> decisions = new HashMap<>(); selected.forEach(d -> decisions.put(d.candidateKey(), d));
        for (Decision d : selected) {
            Candidate c = candidates.get(d.candidateKey()); JsonNode n = c.evidence();
            if (!d.candidateKey().startsWith("CONFLICT:")) recipeIds.add(n.path("recipeId").asLong());
            if (!n.path("lineId").isNull() && !n.path("lineId").isMissingNode()) approvedLineIds.add(n.path("lineId").asLong());
            Long old = nullableLong(n.path("oldIngredientId")); if (old != null) identityIds.add(old);
            if (d.ingredientId() != null) identityIds.add(d.ingredientId());
            if (c.category().equals("词典冲突")) n.path("originalIdsAndNames").forEach(i -> identityIds.add(i.path("id").asLong()));
        }
        var beforeRecipes = captureRecipes(recipeIds, lock);
        for (var e : beforeRecipes.entrySet()) {
            JsonNode n = baseline.get(e.getKey()), h = e.getValue().path("header"), r = h.path("recipe"), s = h.path("source"), doc = h.path("document");
            require(n != null && s.path("source_type").asText().equals("SYSTEM_HOWTOCOOK") && s.path("visibility").asText().equals("PUBLIC") && s.path("owner_user_id").isNull(), "治理只允许公开系统菜谱");
            require(r.path("source_id").asLong() == n.path("sourceId").asLong() && r.path("source_document_id").asLong() == n.path("documentId").asLong()
                    && doc.path("source_id").asLong() == n.path("sourceId").asLong() && s.path("source_key").asText().equals(n.path("sourceKey").asText())
                    && doc.path("source_version").asText().equals(n.path("sourceVersion").asText()), "来源/document身份漂移");
            require(doc.path("content_hash").asText().trim().equals(n.path("contentHash").asText()) && doc.path("raw_markdown").asText().equals(n.path("rawMarkdown").asText())
                    && markdownHash(doc.path("raw_markdown").asText()).equalsIgnoreCase(n.path("contentHash").asText()), "原文或content_hash漂移");
            require(equal(r.path("servings"), n.path("servings")) && r.path("name").asText().equals(n.path("name").asText()), "原结构化人数或菜名漂移");
            ArrayNode expected = JSON.createArrayNode(); n.path("ingredients").forEach(expected::add);
            List<JsonNode> ordered = new ArrayList<>(); expected.forEach(ordered::add); ordered.sort(Comparator.comparingLong(i -> i.path("id").asLong()));
            require(equal(tree(ordered), e.getValue().path("lines")), "原材料字段/身份/raw_line/排序发生漂移");
        }
        var identities = captureIdentities(identityIds, lock);
        for (var e : identities.entrySet()) {
            JsonNode expected = dictionary.get(e.getKey()), actual = e.getValue().path("identity");
            require(expected != null && actual.path("canonical_name").asText().equals(expected.path("name").asText())
                    && actual.path("normalization_status").asText().equals(expected.path("status").asText()), "规范身份前置条件漂移或不是公开词典ID");
        }
        Map<String, String> identityActions = new HashMap<>();
        for (Decision d : selected) {
            Candidate c = candidates.get(d.candidateKey()); JsonNode n = c.evidence();
            if (d.candidateKey().startsWith("PEOPLE:")) {
                require(d.identityMode() == null && d.ingredientId() == null && d.canonicalName() == null, "人数审批不能附带身份操作"); continue;
            }
            if (c.category().equals("移除误解析")) {
                require(d.identityMode() == null && d.ingredientId() == null && d.canonicalName() == null, "移除审批不能附带身份操作"); continue;
            }
            boolean group = c.category().equals("词典冲突");
            require(d.identityMode() != null && Set.of("KEEP","REUSE","RENAME","CREATE").contains(d.identityMode()), "材料审批缺少明确身份处理");
            if (group) {
                if (d.identityMode().equals("REUSE")) {
                    require(n.path("originalIdsAndNames").findValues("id").stream().anyMatch(i -> i.asLong() == d.ingredientId())
                            && dictionary.get(d.ingredientId()).path("name").asText().equals(n.path("proposedName").asText()), "冲突组规范ID不是该组已有明确规范名");
                } else require(d.identityMode().equals("CREATE") && Objects.equals(d.canonicalName(), n.path("proposedName").asText()), "冲突组新身份必须单独批准固定规范名");
            }
            if (d.identityMode().equals("KEEP")) require(d.ingredientId() == null && d.canonicalName() == null, "KEEP只能保留原ID");
            if (d.identityMode().equals("REUSE")) require(d.ingredientId() != null && d.canonicalName() == null && dictionary.containsKey(d.ingredientId()), "REUSE必须明确现有规范ID");
            if (Set.of("CREATE","RENAME").contains(d.identityMode())) {
                String proposed = group ? n.path("proposedName").asText() : n.path("newFields").path("name").asText();
                require(d.canonicalName() != null && d.canonicalName().equals(proposed) && !proposed.isBlank() && proposed.length() <= 255, "规范名只能取固定提案，不能任意覆盖");
                Long named = mapper.identityNamed(proposed);
                require(named == null || (d.identityMode().equals("RENAME") && named.equals(d.ingredientId())), "规范名已存在，应显式复用而非盲目新增或撞名");
                if (d.identityMode().equals("CREATE")) require(d.ingredientId() == null, "CREATE不能伪造规范ID");
                if (d.identityMode().equals("RENAME")) {
                    require(d.ingredientId() != null && d.ingredientId().equals(nullableLong(n.path("oldIngredientId"))), "RENAME只能改本行原规范身份");
                    for (JsonNode ref : identities.get(d.ingredientId()).path("references")) require(approvedLineIds.contains(ref.path("id").asLong()), "规范改名影响未批准或私有引用，拒绝");
                    String prior = identityActions.putIfAbsent(Long.toString(d.ingredientId()), proposed);
                    require(prior == null || prior.equals(proposed), "同身份改名策略冲突");
                }
            }
            if (!group && Set.of("REUSE","CREATE").contains(d.identityMode())) {
                for (Candidate g : candidates.values()) if (g.category().equals("词典冲突")) {
                    boolean related = g.evidence().path("originalIdsAndNames").findValues("id").stream().anyMatch(i -> Objects.equals(nullableLong(n.path("oldIngredientId")), i.asLong()) || Objects.equals(d.ingredientId(), i.asLong()));
                    if (!related) continue; Decision approved = decisions.get(g.key());
                    require(approved != null && approved.identityMode().equals(d.identityMode()) && Objects.equals(approved.ingredientId(), d.ingredientId()) && Objects.equals(approved.canonicalName(), d.canonicalName()), "冲突组未逐组批准一致规范ID/新身份");
                }
            }
        }
        return new Plan(beforeRecipes, identities, candidates);
    }
    private Map<Long,ObjectNode> captureRecipes(Set<Long> ids, boolean lock) {
        Map<Long,ObjectNode> out = new TreeMap<>();
        for (long id : ids) {
            String raw = lock ? mapper.lockHeader(id) : mapper.header(id); require(raw != null, "目标菜谱不存在");
            ObjectNode state = JSON.createObjectNode(); state.set("header", read(raw));
            state.set("lines", list(lock ? mapper.lockLines(id) : mapper.lines(id))); out.put(id,state);
        } return out;
    }
    private Map<Long,ObjectNode> captureIdentities(Set<Long> ids, boolean lock) {
        Map<Long,ObjectNode> out = new TreeMap<>();
        for (long id : ids) {
            String raw = lock ? mapper.lockIdentity(id) : mapper.identity(id); require(raw != null, "规范ID不存在");
            ObjectNode state = JSON.createObjectNode(); state.set("identity", read(raw));
            // dry-run不能加行锁；引用通过已有锁定材料状态读取，写入模式在本批锁下复核。
            state.set("references", list(lock ? mapper.identityReferences(id) : mapper.readIdentityReferences(id)));
            state.set("aliases", list(mapper.aliases(id))); out.put(id,state);
        } return out;
    }
    private void target(RecipeRepairApproval approval) {
        require(mapper.database().equals(approval.targetDatabase()), "准确目标数据库与审批不一致");
        require(transactions instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager, "治理需要同一数据源事务管理器");
        var source = ((org.springframework.jdbc.datasource.DataSourceTransactionManager)transactions).getDataSource();
        var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
        try {
            // 驱动参数不属于目标标识；CLI拒绝带凭证/参数的目标，测试只有loggerLevel。
            String actual = connection.getMetaData().getURL().split("\\?",2)[0];
            require(actual.equals(approval.targetIdentifier()), "准确主机端口/数据库与审批目标不一致");
        } catch (java.sql.SQLException e) { throw new IllegalArgumentException("无法核对治理连接目标",e); }
        finally { org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,source); }
    }
    private void sameBatch(RecipeRepairBatchEntity batch, RecipeRepairApproval approval) {
        require(batch.inputHash.equals(INPUT_HASH) && batch.manifestHash.equals(MANIFEST_HASH) && batch.approvalHash.equals(approval.approvalHash())
                && batch.ruleVersion.equals(RULE) && batch.targetDatabase.equals(approval.targetDatabase()) && batch.targetIdentifier.equals(approval.targetIdentifier()), "同请求键异内容，拒绝重放");
    }
    private int change(long id,long rid,Long identity,JsonNode fields) {
        return mapper.changeLine(id,rid,identity,fields.path("name").asText(),decimal(fields.path("amountMin")),decimal(fields.path("amountMax")),text(fields.path("unitCode")),text(fields.path("unitLabel")),fields.path("optional").asBoolean(),fields.path("sortOrder").asInt());
    }
    private int restore(JsonNode n) { return mapper.restoreLine(n.path("id").asLong(),n.path("recipe_id").asLong(),nullableLong(n.path("ingredient_id")),n.path("raw_name").asText(),decimal(n.path("amount_min")),decimal(n.path("amount_max")),text(n.path("unit_code")),text(n.path("unit_label")),n.path("optional").asBoolean(),n.path("raw_line").asText(),n.path("sort_order").asInt()); }
    private JsonNode oldFields(JsonNode n) { ObjectNode out=JSON.createObjectNode(); out.set("name",n.path("raw_name"));out.set("amountMin",n.path("amount_min"));out.set("amountMax",n.path("amount_max"));out.set("unitCode",n.path("unit_code"));out.set("unitLabel",n.path("unit_label"));out.set("optional",n.path("optional"));out.set("sortOrder",n.path("sort_order"));return out; }
    private static JsonNode tree(Object v) { return JSON.valueToTree(v); }
    private static JsonNode read(String v) { try { return JSON.readTree(v); } catch(Exception e) { throw new IllegalArgumentException("治理审计JSON格式错误",e); } }
    private static ArrayNode list(List<String> values) { ArrayNode out=JSON.createArrayNode();values.forEach(s -> out.add(read(s)));return out; }
    private static boolean equal(JsonNode a,JsonNode b) { return canonical(a).equals(canonical(b)); }
    private static Long nullableLong(JsonNode n) { return n.isNull() || n.isMissingNode() ? null : n.asLong(); }
    private static Integer integer(JsonNode n) { return n.isNull() || n.isMissingNode() ? null : n.asInt(); }
    private static String text(JsonNode n) { return n.isNull() || n.isMissingNode() ? null : n.asText(); }
    private static BigDecimal decimal(JsonNode n) { return n.isNull() || n.isMissingNode() ? null : new BigDecimal(n.asText()); }
    private static Set<Long> longs(Iterator<String> values) { Set<Long> out=new TreeSet<>();values.forEachRemaining(s -> out.add(Long.parseLong(s)));return out; }
    private static RecipeRepairResult result(String json) { try { return JSON.readValue(json,RecipeRepairResult.class); } catch(Exception e) { throw new IllegalArgumentException("批次结果JSON错误",e); } }
    private static String markdownHash(String raw) { return hash(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
