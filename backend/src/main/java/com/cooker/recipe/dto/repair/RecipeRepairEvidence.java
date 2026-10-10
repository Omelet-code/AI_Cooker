package com.cooker.recipe.dto.repair;

import com.cooker.recipe.diagnostic.RecipeParsePreviewTool;
import com.cooker.recipe.parser.HowToCookMarkdownParser;
import com.cooker.recipe.dto.repair.RecipeRepairApproval;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.math.BigDecimal;
import java.util.*;

/** 固定公开证据；任何审批都不能替换解析器输出或降低源文件校验。 */
public final class RecipeRepairEvidence {
    public static final String INPUT_HASH = "571CD40658931616E1B47218EF3E230E17065E182A9B10726EA0D250B7842EF8";
    public static final String MANIFEST_HASH = "AD2841C231559965FDA8E6424100600F655433511CBA96162A18711F35F61155";
    public static final String RULE = "d1-a-parser-v2";
    public static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    public record Candidate(String key, String category, ObjectNode evidence, String hash, Integer addedSortOrder) { }
    private final List<JsonNode> snapshot;
    private final List<JsonNode> manifest;
    private final Map<String, Candidate> candidates;
    private final Map<Long, JsonNode> recipes;
    private final Map<Long, JsonNode> dictionary;
    private final String reviewHash;

    public static RecipeRepairEvidence load(Path input, Path proposal) throws Exception {
        byte[] a = Files.readAllBytes(input), b = Files.readAllBytes(proposal);
        require(hash(a).equals(INPUT_HASH) && hash(b).equals(MANIFEST_HASH), "D1-A固定输入或提案hash不一致");
        return new RecipeRepairEvidence(readLines(a), readLines(b));
    }
    private RecipeRepairEvidence(List<JsonNode> input, List<JsonNode> proposal) {
        require(HowToCookMarkdownParser.RULE_VERSION.equals(RULE), "当前解析规则不是已签收v2");
        snapshot = List.copyOf(input); manifest = List.copyOf(proposal);
        // 复用D1-A同一个解析器/预览器，不相信审批文件带来的newFields。
        require(canonical(JSON.valueToTree(new RecipeParsePreviewTool().preview(snapshot)))
                .equals(canonical(JSON.valueToTree(manifest))), "提案与已签收解析规则重算不一致");
        recipes = new TreeMap<>(); dictionary = new TreeMap<>(); candidates = new LinkedHashMap<>();
        for (JsonNode n : snapshot) {
            if (n.path("kind").asText().equals("recipe")) recipes.put(n.path("recipeId").asLong(), n);
            if (n.path("kind").asText().equals("dictionary"))
                for (JsonNode d : n.path("items")) dictionary.put(d.path("id").asLong(), d);
        }
        Map<Long, Integer> addedOrders = new HashMap<>();
        for (var e : recipes.entrySet()) {
            int max = 0; for (JsonNode l : e.getValue().path("ingredients")) max = Math.max(max, l.path("sort_order").asInt());
            addedOrders.put(e.getKey(), max);
        }
        for (JsonNode n : manifest) {
            String kind = n.path("kind").asText(), key = null, category = null; Integer order = null;
            long rid = n.path("recipeId").asLong();
            if (kind.equals("line")) {
                String status = n.path("status").asText();
                if (status.equals("ADD_CANDIDATE_REVIEW")) {
                    key = "ADD:" + rid + ":" + n.path("rawLineHash").asText() + ":" + n.path("parsedSortOrder").asInt(n.path("newFields").path("sortOrder").asInt());
                    category = "新增漏料"; order = addedOrders.compute(rid, (id, max) -> max + 1);
                } else if (status.equals("REMOVE_CANDIDATE_REVIEW")) { key = "LINE:" + rid + ":" + n.path("lineId"); category = "移除误解析"; }
                else if (!status.equals("UNCHANGED")) { key = "LINE:" + rid + ":" + n.path("lineId"); category = "名称数量单位"; }
                else if (n.path("newFields").path("amountMin").isNull() || n.path("newFields").path("unitCode").isNull()) {
                    key = "UNKNOWN:" + rid + ":" + n.path("lineId"); category = "不确定材料";
                }
            } else if (kind.equals("recipe")) {
                if (!n.path("oldServings").equals(n.path("newServings"))) { key = "PEOPLE:" + rid; category = "人数基准"; }
                else if (n.path("newServings").isNull()) { key = "UNKNOWN_PEOPLE:" + rid; category = "缺人数待复核"; }
            } else if (kind.equals("dictionaryCollision")) { key = "CONFLICT:" + hash(n); category = "词典冲突"; }
            if (key != null) {
                ObjectNode node = ((ObjectNode)n).deepCopy();
                if (kind.equals("line") && !node.path("newFields").isNull()) {
                    node.put("uncertainMaterial", node.path("newFields").path("amountMin").isNull() || node.path("newFields").path("unitCode").isNull());
                    node.put("identityRisk", "名称不能代替标准ID；当前身份处理须明确批准，未获准保留原ID");
                }
                if (kind.equals("recipe")) node.set("rawMarkdown", recipes.get(rid).path("rawMarkdown"));
                if (kind.equals("dictionaryCollision")) {
                    Set<Long> ids = new HashSet<>(); n.path("originalIdsAndNames").forEach(d -> ids.add(d.path("id").asLong()));
                    ArrayNode related = JSON.createArrayNode();
                    for (var recipe : recipes.values()) for (var line : recipe.path("ingredients"))
                        if (ids.contains(line.path("ingredient_id").asLong())) {
                            ObjectNode ref = related.addObject(); ref.set("recipeId", recipe.path("recipeId"));
                            ref.set("sourceId", recipe.path("sourceId")); ref.set("contentHash", recipe.path("contentHash"));
                            ref.set("originalLine", line);
                        }
                    node.set("relatedLines", related);
                }
                if (order != null) node.put("plannedAddedSortOrder", order);
                Candidate c = new Candidate(key, category, node, hash(node), order);
                require(candidates.put(key, c) == null, "候选键重复");
            }
        }
        ArrayNode review = JSON.createArrayNode();
        for (Candidate c : candidates.values()) review.add(JSON.valueToTree(c));
        reviewHash = hash(review);
    }
    public List<JsonNode> snapshot() { return snapshot.stream().map(n -> (JsonNode)n.deepCopy()).toList(); }
    public Map<Long, JsonNode> recipes() { Map<Long, JsonNode> out = new TreeMap<>(); recipes.forEach((k,v) -> out.put(k,v.deepCopy())); return Collections.unmodifiableMap(out); }
    public Map<Long, JsonNode> dictionary() { Map<Long, JsonNode> out = new TreeMap<>(); dictionary.forEach((k,v) -> out.put(k,v.deepCopy())); return Collections.unmodifiableMap(out); }
    public Map<String, Candidate> candidates() { Map<String, Candidate> out = new LinkedHashMap<>(); candidates.forEach((k,v) -> out.put(k,new Candidate(v.key(),v.category(),v.evidence().deepCopy(),v.hash(),v.addedSortOrder()))); return Collections.unmodifiableMap(out); }
    public String reviewHash() { return reviewHash; }
    public RecipeRepairApproval approvalTemplate() {
        var decisions = candidates.values().stream().map(c -> new RecipeRepairApproval.Decision(c.key(), c.hash(), "DEFER", "待人工逐ID审定", null, null, null, c.addedSortOrder(), false, false)).toList();
        return new RecipeRepairApproval("d1-b-approval-v1", RULE, INPUT_HASH, MANIFEST_HASH, reviewHash,
                null, null, null, null, List.of(), decisions, null);
    }
    public List<RecipeRepairApproval.Decision> validate(RecipeRepairApproval approval) {
        require(approval != null && "d1-b-approval-v1".equals(approval.schemaVersion()) && RULE.equals(approval.ruleVersion()), "审批格式或规则版本不匹配");
        require(INPUT_HASH.equals(approval.inputHash()) && MANIFEST_HASH.equals(approval.manifestHash()) && reviewHash.equals(approval.reviewHash()), "审批未绑定固定证据");
        require(approval.requestKey() != null && approval.targetDatabase() != null && !approval.targetDatabase().isBlank()
                && approval.targetIdentifier() != null && !approval.targetIdentifier().isBlank()
                && approval.reviewer() != null && !approval.reviewer().isBlank(), "审批缺少请求键、目标库或审定人");
        require(approval.approvalHash() != null && approval.approvalHash().equals(approvalHash(approval)), "审批内容hash不一致");
        require(approval.allowedActions() != null && approval.allowedActions().stream().allMatch(a -> Set.of("APPLY", "ROLLBACK").contains(a)), "审批动作非法");
        require(approval.decisions() != null && approval.decisions().size() == candidates.size(), "审批必须覆盖全部候选，未决定项保留DEFER");
        Set<String> seen = new HashSet<>(); List<RecipeRepairApproval.Decision> approved = new ArrayList<>();
        for (var d : approval.decisions()) {
            Candidate c = candidates.get(d.candidateKey());
            require(c != null && seen.add(d.candidateKey()) && c.hash().equals(d.candidateHash()), "审批候选键重复、未知或hash漂移");
            require(Set.of("APPROVE", "REJECT", "DEFER").contains(d.decision()) && d.reason() != null && !d.reason().isBlank(), "审批决定或理由不明确");
            if (!d.decision().equals("APPROVE")) continue;
            require(!c.key().startsWith("UNKNOWN"), "不确定项仅能拒绝或暂缓，不能自动补事实");
            if (c.category().equals("词典冲突")) require(("REUSE".equals(d.identityMode()) && d.ingredientId() != null) || ("CREATE".equals(d.identityMode()) && d.canonicalName() != null), "词典冲突必须明确批准复用ID或新规范身份");
            if (c.category().equals("新增漏料")) require(d.confirmAdded() && Objects.equals(c.addedSortOrder(), d.addedSortOrder()), "新增必须单独确认固定追加排序");
            if (c.category().equals("移除误解析")) require(d.confirmNonIngredient(), "移除必须单独确认为非材料误解析");
            approved.add(d);
        }
        require(approved.stream().anyMatch(d -> !d.candidateKey().startsWith("CONFLICT:")), "审批没有获准的实际事实候选");
        return List.copyOf(approved);
    }
    public static String approvalHash(RecipeRepairApproval approval) {
        ObjectNode node = JSON.valueToTree(approval); node.remove("approvalHash"); return hash(node);
    }
    public static String hash(JsonNode node) { return hash(canonical(node).toString().getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("当前环境缺少SHA-256", e); }
    }
    public static JsonNode canonical(JsonNode n) {
        if (n.isObject()) { ObjectNode out = JSON.createObjectNode(); new TreeSet<>(iterable(n.fieldNames())).forEach(k -> out.set(k, canonical(n.get(k)))); return out; }
        if (n.isArray()) { ArrayNode out = JSON.createArrayNode(); n.forEach(v -> out.add(canonical(v))); return out; }
        if (n.isNumber()) return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());
        return n;
    }
    private static List<String> iterable(Iterator<String> values) { List<String> out = new ArrayList<>(); values.forEachRemaining(out::add); return out; }
    private static List<JsonNode> readLines(byte[] bytes) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").lines().toList()) if (!line.isBlank()) rows.add(JSON.readTree(line));
        return rows;
    }
    public static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
