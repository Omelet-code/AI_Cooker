package com.cooker.recipe.diagnostic;

import com.cooker.recipe.dto.ParsedIngredient;
import com.cooker.recipe.parser.HowToCookMarkdownParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/**
 * D1-A显式诊断工具：同生产解析器重解析公开系统快照，只生成提案。
 * 不注册Spring组件、不启动应用、不调用导入/索引/模型，也没有数据库写方法。
 */
public final class RecipeParsePreviewTool {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final HowToCookMarkdownParser parser = new HowToCookMarkdownParser();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("请提供只读快照JSONL和.data/d1-a输出目录");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed = Path.of(".data/d1-a").toAbsolutePath().normalize();
        if (!output.startsWith(allowed)) throw new IllegalArgumentException("预览输出必须位于当前检出的.data/d1-a目录");
        List<JsonNode> snapshot = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String value = line.replaceFirst("^\\uFEFF", "").strip();
            if (!value.isEmpty()) snapshot.add(JSON.readTree(value));
        }
        var manifest = new RecipeParsePreviewTool().preview(snapshot);
        Files.createDirectories(output);
        try (var writer = Files.newBufferedWriter(output.resolve("manifest.jsonl"), StandardCharsets.UTF_8)) {
            for (JsonNode item : manifest) { writer.write(JSON.writeValueAsString(item)); writer.newLine(); }
        }
        var summary = manifest.getLast();
        Files.writeString(output.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardCharsets.UTF_8);
        System.out.println(JSON.writeValueAsString(summary));
    }

    /** 可供显式调用方读取；必须在驱动和PG两层确认只读一致性，拒绝外部可写事务。 */
    public static List<JsonNode> readSnapshot(Connection connection) throws Exception {
        if (connection.getAutoCommit() || !connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new IllegalArgumentException("预览连接必须关闭自动提交并使用REPEATABLE READ只读事务");
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT current_setting('transaction_read_only')")) {
            if (!result.next() || !"on".equals(result.getString(1))) throw new IllegalArgumentException("PostgreSQL事务未强制只读");
        }
        String sql;
        try (var input = RecipeParsePreviewTool.class.getResourceAsStream("/diagnostic/recipe/d1-a-readonly.sql")) {
            if (input == null) throw new IllegalStateException("缺少只读诊断SQL");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").replaceAll("(?m)^--.*$", "");
        }
        List<JsonNode> rows = new ArrayList<>();
        for (String query : sql.split(";")) {
            if (query.isBlank()) continue;
            // 固定资源只有SELECT；没有锁查询、动态表名或用户输入SQL。
            if (!query.stripLeading().startsWith("SELECT ") || query.toUpperCase(Locale.ROOT).contains("FOR UPDATE"))
                throw new IllegalStateException("诊断资源超出只读SELECT边界");
            try (var statement = connection.prepareStatement(query)) {
                statement.setQueryTimeout(20);
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(JSON.readTree(result.getString(1)));
                }
            }
        }
        return List.copyOf(rows);
    }

    public List<ObjectNode> preview(List<JsonNode> snapshot) {
        var environment = snapshot.stream().filter(n -> "environment".equals(n.path("kind").asText())).toList();
        if (environment.size() != 1 || !"on".equals(environment.getFirst().path("readOnly").asText())
                || !"repeatable read".equals(environment.getFirst().path("isolation").asText()))
            throw new IllegalArgumentException("快照必须来自强制只读一致性事务");
        var dictionaries = snapshot.stream().filter(n -> "dictionary".equals(n.path("kind").asText())).toList();
        if (dictionaries.size() != 1) throw new IllegalArgumentException("快照缺少唯一公开食材词典");
        var dictionary = dictionaries.getFirst().path("items");
        Map<String,List<JsonNode>> ids = new TreeMap<>();
        for (JsonNode item : dictionary) ids.computeIfAbsent(item.path("name").asText(), ignored -> new ArrayList<>()).add(item);
        List<ObjectNode> output = new ArrayList<>();
        output.add(base("environment").set("snapshot", environment.getFirst()));
        int recipes = 0, oldLines = 0, newLines = 0, changed = 0, unchanged = 0, added = 0, removed = 0;
        int oldUnknown = 0, newUnknown = 0, missingServings = 0, failed = 0;
        Set<Long> recipeIds = new HashSet<>();
        Set<Long> changedRecipes = new TreeSet<>();
        for (JsonNode recipe : snapshot) {
            if (!"recipe".equals(recipe.path("kind").asText())) continue;
            long recipeId = recipe.path("recipeId").asLong();
            if (!recipeIds.add(recipeId)) throw new IllegalArgumentException("快照菜谱ID重复");
            if (!"SYSTEM_HOWTOCOOK".equals(recipe.path("sourceType").asText()))
                throw new IllegalArgumentException("诊断只接受公开系统来源");
            String raw = recipe.path("rawMarkdown").asText();
            String hash = sha256(raw);
            if (!hash.equals(recipe.path("contentHash").asText().strip()))
                throw new IllegalArgumentException("原文hash与快照不一致，拒绝生成预览，菜谱ID=" + recipeId);
            var parsed = parser.parse(raw, recipe.path("sourceKey").asText());
            recipes++;
            if (!parsed.successful()) failed++;
            if (parsed.servings() == null) missingServings++;
            ObjectNode report = context("recipe", recipe, hash);
            report.put("successful", parsed.successful());
            report.set("oldServings", recipe.path("servings"));
            report.set("newServings", JSON.valueToTree(parsed.servings()));
            // 依据来自同一解析器的结构化策略标记，拒绝把所有非空人数都称为明确人数。
            String servingsBasis = "UNKNOWN_REQUIRES_REVIEW";
            if (parsed.servings() != null) {
                String marker = HowToCookMarkdownParser.RULE_VERSION + "：人数依据=";
                boolean range = parsed.warnings().stream().anyMatch(w -> w.startsWith(marker + "RANGE_MIN_AS_BASELINE；"));
                boolean approximate = parsed.warnings().stream().anyMatch(w -> w.startsWith(marker + "APPROXIMATE_PEOPLE_AS_BASELINE；"));
                // 多处同值基准若同时存在范围和约量，范围依据优先，原诊断仍完整保留。
                servingsBasis = range ? "RANGE_MIN_AS_BASELINE"
                        : approximate ? "APPROXIMATE_PEOPLE_AS_BASELINE" : "EXPLICIT_PEOPLE";
            }
            report.put("servingsBasis", servingsBasis);
            report.set("diagnostics", JSON.valueToTree(parsed.warnings()));
            boolean recipeChanged = !Objects.equals(nullableInt(recipe.path("servings")), parsed.servings());
            Map<String,Deque<ParsedIngredient>> byRaw = new LinkedHashMap<>();
            for (ParsedIngredient item : parsed.ingredients()) byRaw.computeIfAbsent(item.rawLine(), ignored -> new ArrayDeque<>()).add(item);
            Set<ParsedIngredient> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<String,Deque<Integer>> physicalLines = physicalLines(raw);
            for (JsonNode item : recipe.path("ingredients")) {
                oldLines++;
                if (item.path("amount_min").isNull() || item.path("unit_code").isNull()) oldUnknown++;
                var candidates = byRaw.get(item.path("raw_line").asText());
                ParsedIngredient match = candidates == null || candidates.isEmpty() ? null : candidates.removeFirst();
                if (match != null) consumed.add(match);
                ObjectNode oldFields = oldFields(item);
                // 保留原ID和原sort_order；解析顺序单独列出，排除删除标题导致其余行无端重排。
                ObjectNode proposed = match == null ? null : fields(match, item.path("sort_order").asInt());
                boolean same = proposed != null && oldFields.equals(proposed);
                ObjectNode row = context("line", recipe, hash);
                row.set("lineId", item.path("id"));
                row.set("oldIngredientId", item.path("ingredient_id"));
                row.set("oldFields", oldFields);
                row.set("newFields", proposed == null ? NullNode.instance : proposed);
                row.put("rawLine", item.path("raw_line").asText());
                row.put("rawLineHash", sha256(item.path("raw_line").asText()));
                row.put("sourceLineNumber", nextLine(physicalLines, item.path("raw_line").asText()));
                row.put("status", same ? "UNCHANGED" : match == null ? "REMOVE_CANDIDATE_REVIEW" : "CHANGE_CANDIDATE_REVIEW");
                row.put("requiresReview", !same);
                row.set("diagnostics", JSON.valueToTree(parsed.warnings().stream().filter(w -> w.contains(item.path("raw_line").asText())).toList()));
                row.put("evidence", same ? "原文与语义字段一致" : match == null ? "原解析行未被可靠材料规则接纳；核对章节/工具/表格，不直接删除" : "同rawLine重解析；仅分隔符/明确数量/基准标签规则，不认定同义食材");
                if (match != null) {
                    row.put("parsedSortOrder", match.sortOrder());
                    row.set("candidateIngredientIds", JSON.valueToTree(ids.getOrDefault(match.rawName(), List.of()).stream().map(n -> n.path("id").asLong()).toList()));
                    row.put("identityDecision", "PENDING_REVIEW_NO_MERGE");
                }
                if (same) unchanged++; else { changed++; recipeChanged = true; if (match == null) removed++; }
                output.add(row);
            }
            for (ParsedIngredient item : parsed.ingredients()) {
                newLines++;
                if (item.amountMin() == null || item.unitCode() == null) newUnknown++;
                if (consumed.contains(item)) continue;
                ObjectNode row = context("line", recipe, hash);
                row.putNull("lineId"); row.putNull("oldIngredientId"); row.putNull("oldFields");
                row.set("newFields", fields(item, item.sortOrder()));
                row.put("rawLine", item.rawLine()); row.put("rawLineHash", sha256(item.rawLine()));
                row.put("sourceLineNumber", nextLine(physicalLines, item.rawLine()));
                row.put("status", "ADD_CANDIDATE_REVIEW"); row.put("requiresReview", true);
                row.put("evidence", "可靠原料章节恢复候选，不从操作步骤猜量");
                row.set("candidateIngredientIds", JSON.valueToTree(ids.getOrDefault(item.rawName(), List.of()).stream().map(n -> n.path("id").asLong()).toList()));
                row.put("identityDecision", "PENDING_REVIEW_NO_MERGE");
                row.set("diagnostics", JSON.valueToTree(parsed.warnings().stream().filter(w -> w.contains(item.rawLine())).toList()));
                output.add(row); added++; recipeChanged = true;
            }
            report.put("changed", recipeChanged);
            // 仅列候选，不改模板、不构造新向量；具体影响hash由D1-C另行验证。
            report.set("impactChunkCandidates", recipeChanged ? recipe.path("chunks") : JSON.createArrayNode());
            report.put("chunkDecision", "CANDIDATE_ONLY_NO_REINDEX");
            output.add(report);
            if (recipeChanged) changedRecipes.add(recipeId);
        }
        Map<String,List<JsonNode>> groups = new TreeMap<>();
        for (JsonNode item : dictionary) {
            String cleaned = item.path("name").asText().replaceFirst("\\s*[=＝]\\s*$", "").strip();
            groups.computeIfAbsent(cleaned, ignored -> new ArrayList<>()).add(item);
        }
        int collisions = 0;
        for (var group : groups.entrySet()) if (group.getValue().size() > 1) {
            ObjectNode conflict = base("dictionaryCollision");
            conflict.put("proposedName", group.getKey()); conflict.set("originalIdsAndNames", JSON.valueToTree(group.getValue()));
            conflict.put("decision", "PENDING_REVIEW_KEEP_ALL_IDS");
            conflict.put("evidence", "仅去尾分隔等号后同名；待人工审定，不能直接撞唯一键或标REVIEWED");
            output.add(conflict); collisions++;
        }
        for (JsonNode row : snapshot) if ("digests".equals(row.path("kind").asText())) output.add(base("protectionDigests").set("snapshot", row));
        ObjectNode summary = base("summary");
        summary.put("recipes", recipes); summary.put("parseFailures", failed); summary.put("oldLines", oldLines); summary.put("newLines", newLines);
        summary.put("unchangedLines", unchanged); summary.put("changedExistingLines", changed); summary.put("addedCandidates", added); summary.put("removedCandidates", removed);
        summary.put("oldUnknownQuantityOrUnit", oldUnknown); summary.put("newUnknownQuantityOrUnit", newUnknown); summary.put("newMissingPeopleBasis", missingServings);
        summary.put("dictionaryCollisionGroups", collisions); summary.set("changedRecipeIds", JSON.valueToTree(changedRecipes));
        summary.put("applied", false); summary.put("modelRequests", 0); summary.put("embeddingRequests", 0);
        output.add(summary);
        return List.copyOf(output);
    }

    private static ObjectNode oldFields(JsonNode item) {
        ObjectNode result = JSON.createObjectNode();
        result.set("name", item.path("raw_name")); result.set("amountMin", decimal(item.path("amount_min"))); result.set("amountMax", decimal(item.path("amount_max")));
        result.set("unitCode", item.path("unit_code")); result.set("unitLabel", item.path("unit_label")); result.set("optional", item.path("optional")); result.set("sortOrder", item.path("sort_order"));
        return result;
    }
    private static ObjectNode fields(ParsedIngredient item, int order) {
        ObjectNode result = JSON.createObjectNode();
        result.set("name", JSON.valueToTree(item.rawName())); result.set("amountMin", decimal(item.amountMin())); result.set("amountMax", decimal(item.amountMax()));
        result.set("unitCode", JSON.valueToTree(item.unitCode())); result.set("unitLabel", JSON.valueToTree(item.unitLabel())); result.put("optional", item.optional()); result.put("sortOrder", order);
        return result;
    }
    private static JsonNode decimal(JsonNode n) { return n.isNull() || n.isMissingNode() ? NullNode.instance : decimal(new BigDecimal(n.asText())); }
    private static JsonNode decimal(BigDecimal n) { return n == null ? NullNode.instance : new TextNode(n.stripTrailingZeros().toPlainString()); }
    private static Integer nullableInt(JsonNode n) { return n.isNull() || n.isMissingNode() ? null : n.intValue(); }
    private static ObjectNode base(String kind) { return JSON.createObjectNode().put("kind", kind).put("ruleVersion", HowToCookMarkdownParser.RULE_VERSION).put("applied", false); }
    private static ObjectNode context(String kind, JsonNode recipe, String hash) {
        var result = base(kind);
        for (String field : List.of("sourceId", "sourceKey", "sourceVersion", "documentId", "recipeId")) result.set(field, recipe.path(field));
        result.put("contentHash", hash);
        return result;
    }
    private static Map<String,Deque<Integer>> physicalLines(String raw) {
        Map<String,Deque<Integer>> result = new HashMap<>();
        var lines = raw.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String value = lines[i].replaceFirst("^\\s*[-*+]\\s+", "").strip();
            result.computeIfAbsent(value, ignored -> new ArrayDeque<>()).add(i + 1);
        }
        return result;
    }
    private static int nextLine(Map<String,Deque<Integer>> lines, String raw) {
        var values = lines.get(raw);
        return values == null || values.isEmpty() ? 0 : values.removeFirst();
    }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前环境不支持SHA-256", ex); }
    }
}
