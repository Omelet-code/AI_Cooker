package com.cooker.recipe.diagnostic;

import com.cooker.recipe.dto.repair.RecipeRepairEvidence;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.cooker.recipe.dto.repair.RecipeRepairEvidence.*;

/** B1只生成待审材料，不连接数据库、不批准候选；原文转义后展示。 */
public final class RecipeRepairReview {
    private RecipeRepairReview() { }
    public static void main(String[] args) throws Exception {
        require(args.length == 3, "请提供固定公开输入、v2提案和.data/d1-b输出目录");
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        require(output.startsWith(Path.of(".data/d1-b").toAbsolutePath().normalize()), "审定包输出必须位于当前.data/d1-b");
        var evidence = RecipeRepairEvidence.load(Path.of(args[0]), Path.of(args[1]));
        write(evidence, output);
        System.out.println("审定包已生成：" + evidence.candidates().size() + "项，默认全部DEFER，数据库应用0");
    }
    public static void write(RecipeRepairEvidence evidence, Path output) throws Exception {
        Files.createDirectories(output);
        var review = JSON.createObjectNode().put("schemaVersion", "d1-b-review-v1").put("inputHash", INPUT_HASH)
                .put("manifestHash", MANIFEST_HASH).put("ruleVersion", RULE).put("reviewHash", evidence.reviewHash())
                .put("defaultDecision", "DEFER").put("applied", false);
        review.set("candidates", JSON.valueToTree(evidence.candidates().values()));
        Files.writeString(output.resolve("review.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(review), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("approval-template.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence.approvalTemplate()), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# D1-B逐ID审定包\n\n全部默认DEFER；未应用开发库。输入与提案hash绑定d1-a-parser-v2。\n\n约人数取值/范围取下限是折算政策，不能代替具体候选或身份映射审批。\n\n");
        md.append("输入：").append(INPUT_HASH).append("\n\n提案：").append(MANIFEST_HASH).append("\n\n审定包：").append(evidence.reviewHash()).append("\n\n");
        Map<String, Long> counts = new TreeMap<>();
        for (Candidate c : evidence.candidates().values()) counts.merge(c.category(), 1L, Long::sum);
        md.append("分类：").append(counts).append("。同名冲突不代表同商品，默认保留所有ID。\n\n");
        for (Candidate c : evidence.candidates().values()) {
            md.append("## ").append(c.key()).append("\n\n分类：").append(c.category()).append("；决定：DEFER。\n\n候选内容hash：").append(c.hash()).append("\n\n");
            md.append("```json\n").append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(c.evidence()).replace("```", "\\u0060\\u0060\\u0060")).append("\n```\n\n");
            if (c.category().equals("词典冲突")) {
                Set<Long> ids = new HashSet<>(); c.evidence().path("originalIdsAndNames").forEach(n -> ids.add(n.path("id").asLong()));
                md.append("关联当前材料行（菜谱ID/原行ID/食材ID）：\n\n");
                for (var recipe : evidence.recipes().values()) for (var line : recipe.path("ingredients"))
                    if (ids.contains(line.path("ingredient_id").asLong())) md.append("- ").append(recipe.path("recipeId")).append(" / ").append(line.path("id")).append(" / ").append(line.path("ingredient_id")).append("\n");
                md.append("\n必须逐组选择规范ID，再逐行批准REUSE；组批准不自动改关联行；没有可复用规范ID时，CREATE需单独批准固定规范名。\n\n");
            } else if (c.addedSortOrder() != null) md.append("新增仅追加到固定sort_order=").append(c.addedSortOrder()).append("，不移动未批准旧行；confirmAdded必须为true。\n\n");
            else if (c.category().equals("移除误解析")) md.append("仅原明确误解析候选；审定人确认不是实际材料后填写confirmNonIngredient，不能删除真实未知材料。\n\n");
            md.append("身份处理：KEEP原ID、REUSE明确已有ID、RENAME显式规范名或CREATE显式新规范身份；不猜同义词、不自动REVIEWED，不删除旧规范ID。\n\n");
        }
        md.append("## 审批操作格式\n\n复制approval-template.json；逐项选择APPROVE/REJECT/DEFER并写理由。APPROVE材料需明确identityMode：KEEP/REUSE/RENAME/CREATE；REUSE/RENAME填写ingredientId，CREATE/RENAME填写提案材料名canonicalName。新行必须confirmAdded，移除必须confirmNonIngredient；冲突组先批准规范ID并逐行映射。人数候选仅选决定，不改newFields。未知项只能DEFER/REJECT。\n\n填写请求UUID、targetDatabase、精确不含凭证的targetIdentifier（JDBC主机:端口/库）、reviewer和allowedActions（APPLY或ROLLBACK），用seal-approval生成内容hash。hash绑定内容，不是审批人数字签名；后续实际用户批准和精确目标库应用仍须另行允许。默认模板无批准、无目标、无动作，执行必须拒绝。\n");
        Files.writeString(output.resolve("review.md"), md, StandardCharsets.UTF_8);
        String safe = md.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        Files.writeString(output.resolve("review.html"), "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>D1-B逐ID审定</title><style>body{max-width:1100px;margin:2rem auto;background:#fafafa;color:#222}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.55 system-ui}</style><pre>" + safe + "</pre></html>", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("counts", counts, "candidates", evidence.candidates().size(), "approved", 0, "applied", false, "reviewHash", evidence.reviewHash())), StandardCharsets.UTF_8);
    }
}
