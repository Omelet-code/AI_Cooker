package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchCriteria;
import com.cooker.retrieval.dto.RecipeSearchResult;
import com.cooker.retrieval.service.QueryEmbeddingService;
import com.cooker.retrieval.service.RecipeSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 只在显式指定 cooker.t6.enabled=true 时启动。评测数据源应为只读账号，Flyway 明确关闭。
 * 普通 mvn test 不加载 Spring 上下文、不连真实数据，也不调用 Embedding API。
 */
@EnabledIfSystemProperty(named = "cooker.t6.enabled", matches = "true")
@SpringBootTest
@Import(T6RecipeSearchEvaluationTest.T6EvaluationConfiguration.class)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.datasource.hikari.read-only=true"})
class T6RecipeSearchEvaluationTest {
    private static final int WARMUPS = Integer.getInteger("cooker.t6.warmups", 3);
    private static final int REPETITIONS = Integer.getInteger("cooker.t6.repetitions", 5);
    private static final int LIMIT = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired RecipeSearchService searchService;
    @Autowired T6EmbeddingProbe embeddingProbe;
    @Autowired RetrievalIndexProperties indexProperties;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 运行固定评测集并输出机器结果与中文报告() throws Exception {
        Dataset dataset;
        try (InputStream stream = getClass().getResourceAsStream("/retrieval-evaluation/t6-dataset-v1.json")) {
            assertThat(stream).as("固定 T6 评测集").isNotNull();
            dataset = objectMapper.readValue(stream, Dataset.class);
        }
        // 只读探测先验证连接，避免 JDBC 配置错误导致每条查询重复等待连接超时。
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        String mode = System.getProperty("cooker.t6.mode", "lexical");
        if (!mode.equals("lexical") && !mode.equals("mixed")) fail("cooker.t6.mode 只能是 lexical 或 mixed");
        if (mode.equals("mixed") && !"YES".equals(System.getenv("COOKER_T6_ALLOW_PAID"))) {
            fail("混合模式会调用收费 Embedding；必须先取得本次手动运行批准并设置 COOKER_T6_ALLOW_PAID=YES");
        }
        if (mode.equals("mixed") && (indexProperties.getApiKey() == null || indexProperties.getApiKey().isBlank())) {
            fail("混合模式需要通过环境变量提供服务端 Embedding Key");
        }

        assertThat(dataset.labelApproval()).as("标签整体审定元数据").isNotNull();
        embeddingProbe.expectedDimension(indexProperties.getEmbeddingDimension());
        Instant started = Instant.now();
        List<QueryResult> queryResults = new ArrayList<>();
        List<Long> localMicros = new ArrayList<>();
        List<Long> embeddingMicros = new ArrayList<>();
        List<Long> endToEndMicros = new ArrayList<>();
        Set<T6RecipeHardConditionAuditor.Violation> hardViolations = new LinkedHashSet<>();
        T6RecipeHardConditionAuditor auditor = new T6RecipeHardConditionAuditor(jdbc);
        int embeddingCalls = 0;
        int failures = 0;
        int timeouts = 0;
        for (EvalQuery query : dataset.queries()) {
            QueryResult last = null;
            RecipeSearchCriteria criteria = criteria(query);
            for (int iteration = -WARMUPS; iteration < REPETITIONS; iteration++) {
                try {
                    embeddingProbe.reset();
                    long searchStart = System.nanoTime();
                    List<RecipeSearchResult> results = mode.equals("mixed")
                            ? searchService.search(criteria)
                            : embeddingProbe.withoutRemoteCall(() -> searchService.search(criteria));
                    long totalMicros = elapsedMicros(searchStart);
                    T6EmbeddingProbe.Observation embedding = embeddingProbe.observation();
                    embeddingCalls += embedding.providerCalls();
                    long embeddingTimeMicros = 0;
                    if (mode.equals("mixed")) {
                        if (embedding.providerCalls() != 1 || embedding.failureType() != null
                                || !embedding.usableVector()) {
                            throw new IllegalStateException("query embedding 失败或不可用，可能已发生静默词法降级");
                        }
                        embeddingTimeMicros = embedding.elapsedMicros();
                    } else if (embedding.providerCalls() != 0) {
                        throw new IllegalStateException("词法模式不允许调用远程 Embedding");
                    }
                    long localTimeMicros = Math.max(0, totalMicros - embeddingTimeMicros);
                    hardViolations.addAll(auditor.audit(query.id(),
                            results.stream().map(RecipeSearchResult::recipeId).toList(), criteria));
                    if (iteration >= 0) {
                        localMicros.add(localTimeMicros);
                        endToEndMicros.add(totalMicros);
                        if (mode.equals("mixed")) embeddingMicros.add(embeddingTimeMicros);
                    }
                    last = new QueryResult(query, results, null, localTimeMicros,
                            embeddingTimeMicros, totalMicros, results.size());
                } catch (Exception exception) {
                    failures++;
                    if (exception.getClass().getSimpleName().toLowerCase().contains("timeout")) timeouts++;
                    if (iteration >= 0) last = new QueryResult(query, List.of(), safeMessage(exception), null, null, null, 0);
                }
            }
            if (last == null) last = new QueryResult(query, List.of(), "全部运行轮次失败", null, null, null, 0);
            queryResults.add(last);
        }

        List<BoundaryResult> boundaries = runBoundaries(dataset.boundaryCases(), auditor, hardViolations);
        Map<String, Object> metrics = computeMetrics(queryResults, hardViolations.size());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "用户整体审定标签；结果仅代表当前运行模式");
        output.put("mode", mode.equals("mixed") ? "真实混合（真实 query embedding）" : "生产 Service 词法路径（测试层屏蔽远程调用）");
        output.put("datasetVersion", dataset.datasetVersion());
        output.put("indexVersion", dataset.indexVersion());
        output.put("labelVersion", dataset.labelVersion());
        output.put("labelStatus", dataset.labelStatus());
        output.put("labelApproval", dataset.labelApproval());
        output.put("activeIndex", indexProperties.getEmbeddingModel() + ":" + indexProperties.getEmbeddingDimension()
                + ":template-" + indexProperties.getDocumentTemplateVersion());
        output.put("queryCount", queryResults.size());
        output.put("boundaryCount", boundaries.size());
        output.put("warmupsPerQuery", WARMUPS);
        output.put("repetitionsPerQuery", REPETITIONS);
        output.put("embeddingCalls", embeddingCalls);
        output.put("embeddingCost", "未知；未记录或输出密钥");
        output.put("durationSeconds", Duration.between(started, Instant.now()).toMillis() / 1000.0);
        output.put("failureCount", failures);
        output.put("timeoutCount", timeouts);
        output.put("zeroResultCount", queryResults.stream().filter(item -> item.results().isEmpty()).count());
        output.put("environment", environment());
        output.put("metrics", metrics);
        output.put("hardConditionViolationDetails", List.copyOf(hardViolations));
        output.put("latencyMs", Map.of("localRetrieval", summary(localMicros),
                "queryEmbedding", summary(embeddingMicros), "endToEnd", summary(endToEndMicros)));
        output.put("boundaryCases", boundaries);
        output.put("queries", queryResults.stream().map(item -> analyze(item, dataset)).toList());

        Path directory = Path.of("target", "t6-evaluation");
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("evaluation.json").toFile(), output);
        Files.writeString(directory.resolve("report.md"), markdown(output, queryResults, boundaries));
        assertThat(queryResults).hasSize(dataset.queries().size());
        assertThat(queryResults).allSatisfy(item -> assertThat(item.failure()).as(item.query().id()).isNull());
        assertThat(failures).as("所有失败都必须使评测失败，不能被后续成功掩盖").isZero();
        assertThat(timeouts).isZero();
        assertThat(hardViolations).isEmpty();
        assertThat(boundaries).filteredOn(BoundaryResult::applicable)
                .allSatisfy(item -> assertThat(item.passed()).as(item.id()).isTrue());
        assertThat(boundaries).filteredOn(item -> !item.applicable())
                .allSatisfy(item -> assertThat(item.group()).isEqualTo("权限边界"));
        assertThat(embeddingCalls).isEqualTo(mode.equals("mixed")
                ? dataset.queries().size() * (WARMUPS + REPETITIONS) : 0);
    }

    private List<BoundaryResult> runBoundaries(List<BoundaryCase> cases,
            T6RecipeHardConditionAuditor auditor, Set<T6RecipeHardConditionAuditor.Violation> violations) {
        Integer privateCount = jdbc.queryForObject(
                "SELECT count(*) FROM recipe_source WHERE source_type = 'USER_CUSTOM'", Integer.class);
        return cases.stream().map(boundary -> {
            RecipeSearchCriteria criteria = criteria(boundary);
            embeddingProbe.reset();
            List<RecipeSearchResult> results = embeddingProbe.withoutRemoteCall(() -> searchService.search(criteria));
            if (embeddingProbe.observation().providerCalls() != 0) {
                throw new IllegalStateException("边界评测意外调用了远程 Embedding");
            }
            List<T6RecipeHardConditionAuditor.Violation> audit = auditor.audit("boundary-" + boundary.id(),
                    results.stream().map(RecipeSearchResult::recipeId).toList(), criteria);
            violations.addAll(audit);
            boolean applicable = !boundary.group().equals("权限边界") || privateCount != null && privateCount > 0;
            boolean passed = boundary.expectedEmpty() ? results.isEmpty() : audit.isEmpty();
            return new BoundaryResult(boundary.id(), boundary.group(), boundary.expectedEmpty(),
                    results.size(), applicable, passed, boundary.reason(), audit);
        }).toList();
    }

    private Map<String, Object> computeMetrics(List<QueryResult> results, int hardViolations) {
        List<QueryResult> relevant = results.stream().filter(item -> !item.query().relevantRecipes().isEmpty()).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("validSamples", relevant.size());
        out.put("hitAt3", mean(relevant, 3, Metric.HIT));
        out.put("hitAt5", mean(relevant, 5, Metric.HIT));
        out.put("recallAt5", mean(relevant, 5, Metric.RECALL));
        out.put("mrrAt10", mean(relevant, 10, Metric.MRR));
        out.put("hitAt3Threshold", 0.75);
        out.put("hitAt5Threshold", 0.80);
        Map<String, List<QueryResult>> groups = relevant.stream().collect(Collectors.groupingBy(
                item -> item.query().group(), LinkedHashMap::new, Collectors.toList()));
        Map<String, Object> groupMetrics = new LinkedHashMap<>();
        groups.forEach((group, items) -> groupMetrics.put(group, Map.of("samples", items.size(),
                "hitAt3", mean(items, 3, Metric.HIT), "hitAt5", mean(items, 5, Metric.HIT),
                "recallAt5", mean(items, 5, Metric.RECALL), "mrrAt10", mean(items, 10, Metric.MRR))));
        out.put("groups", groupMetrics);
        out.put("hardConditionViolations", hardViolations);
        out.put("thresholdStatus", "用户整体审定标签；结果仅代表当前运行模式");
        return out;
    }

    private double mean(List<QueryResult> items, int k, Metric metric) {
        return T6EvaluationMetrics.mean(items.stream().map(item -> {
            List<Long> ids = item.results().stream().map(RecipeSearchResult::recipeId).toList();
            Set<Long> relevantIds = item.query().relevantRecipes().stream().map(RelevantRecipe::id).collect(Collectors.toSet());
            return switch (metric) {
                case HIT -> T6EvaluationMetrics.hitAt(ids, relevantIds, k);
                case RECALL -> T6EvaluationMetrics.recallAt(ids, relevantIds, k);
                case MRR -> T6EvaluationMetrics.reciprocalRankAt(ids, relevantIds, k);
            };
        }).toList());
    }

    private Map<String, Object> analyze(QueryResult result, Dataset dataset) {
        Map<Long, RelevantRecipe> relevant = result.query().relevantRecipes().stream()
                .collect(Collectors.toMap(RelevantRecipe::id, item -> item));
        List<Map<String, Object>> top10 = result.results().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recipeId", item.recipeId()); row.put("rank", item.rank());
            row.put("lexicalRank", item.lexicalRank()); row.put("vectorRank", item.vectorRank());
            row.put("rrfScore", item.rrfScore()); row.put("matchedIngredients", item.matchedIngredients());
            row.put("matchedReasonCodes", item.matchedReasonCodes());
            row.put("relevant", relevant.containsKey(item.recipeId()));
            row.put("labelReason", relevant.containsKey(item.recipeId()) ? relevant.get(item.recipeId()).reason() : null);
            return row;
        }).toList();
        String failure = result.failure() != null ? result.failure()
                : top10.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("relevant"))) ? null
                : result.results().isEmpty() ? "检索无结果" : "Top10 未命中整体审定标签";
        return Map.of("id", result.query().id(), "query", result.query().query(), "group", result.query().group(),
                "constraints", result.query().constraints(), "resultCount", result.resultCount(),
                "top10", top10, "failureAnalysis", failure == null ? "命中至少一条整体审定标签" : failure,
                "labelVersion", dataset.labelVersion(), "labelStatus", dataset.labelStatus());
    }

    private Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java", System.getProperty("java.version"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxJvmMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        env.put("database", jdbc.queryForObject("SELECT version()", String.class));
        env.put("databaseReadOnly", true);
        env.put("flyway", "disabled");
        return env;
    }

    private Map<String, Object> summary(List<Long> samples) {
        if (samples.isEmpty()) return Map.of("samples", 0, "p50", "未知", "p95", "未知");
        List<Long> sorted = samples.stream().sorted().toList();
        return Map.of("samples", sorted.size(), "unit", "ms", "p50", percentile(sorted, 0.50) / 1000.0,
                "p95", percentile(sorted, 0.95) / 1000.0);
    }

    private Map<String, Object> combinedLatency(List<Long> local, List<Long> embedding) {
        if (embedding.isEmpty()) return Map.of("samples", local.size(), "p50", "同本地检索", "p95", "同本地检索");
        int count = Math.min(local.size(), embedding.size());
        List<Long> sum = new ArrayList<>();
        for (int i = 0; i < count; i++) sum.add(local.get(i) + embedding.get(i));
        return summary(sum);
    }

    private String markdown(Map<String, Object> output, List<QueryResult> results, List<BoundaryResult> boundaries) {
        @SuppressWarnings("unchecked") Map<String, Object> metrics = (Map<String, Object>) output.get("metrics");
        StringBuilder md = new StringBuilder("# T6 检索评测报告\n\n");
        md.append("标签版本：").append(output.get("labelVersion"))
                .append("；用户整体审定日期：").append(((LabelApproval) output.get("labelApproval")).approvedAt())
                .append("；模式：").append(output.get("mode"))
                .append("；数据集：").append(output.get("datasetVersion")).append("；索引：").append(output.get("activeIndex")).append("。\n\n");
        md.append("审定范围：").append(((LabelApproval) output.get("labelApproval")).scope())
                .append(" 不表示存在逐条审定记录。\n\n");
        md.append("- 有效相关性样本：").append(metrics.get("validSamples"))
                .append("；Hit@3：").append(metrics.get("hitAt3"))
                .append("（门槛 ≥75%）；Hit@5：").append(metrics.get("hitAt5"))
                .append("（门槛 ≥80%）。\n- Recall@5：").append(metrics.get("recallAt5"))
                .append("；MRR@10：").append(metrics.get("mrrAt10"))
                .append("。以上指标定义不同，不合并解释。\n- 独立事实硬条件违规：").append(metrics.get("hardConditionViolations"))
                .append("；Embedding 调用：").append(output.get("embeddingCalls"))
                .append("；费用：未知。预算和库存覆盖率不属于 T5/T6 指标。\n\n");
        md.append("## 分组指标\n\n").append("```json\n").append(json(metrics.get("groups"))).append("\n```\n\n");
        md.append("## 延迟\n\n")
                .append("本地检索由生产 RecipeSearchService.search 总耗时扣除测试层装饰的 QueryEmbeddingService 实测耗时；端到端为 Service.search 实测总耗时。测试层以同一次调用分别计时，不触碰生产代码。\n\n");
        md.append("```json\n").append(json(output.get("latencyMs"))).append("\n```\n\n");
        md.append("运行环境：\n\n```json\n").append(json(output.get("environment"))).append("\n```\n\n");
        md.append("本次零结果：").append(output.get("zeroResultCount")).append(" 条；Embedding 调用：")
                .append(output.get("embeddingCalls")).append("；失败/超时：")
                .append(output.get("failureCount")).append("/").append(output.get("timeoutCount")).append("。\n\n");
        md.append("## 独立硬条件违规明细\n\n```json\n")
                .append(json(output.get("hardConditionViolationDetails"))).append("\n```\n\n");
        md.append("## 边界用例\n\n");
        boundaries.forEach(item -> md.append("- ").append(item.id()).append("（").append(item.group()).append("）：")
                .append(item.applicable() ? item.passed() ? "通过" : "失败" : "不适用，缺少私有数据样本")
                .append("；返回结果数 ").append(item.resultCount()).append("；")
                .append(item.reason()).append("\n"));
        md.append("\n## 逐条结果\n\n");
        results.forEach(item -> {
            md.append("### ").append(item.query().id()).append(" — ").append(item.query().query()).append("\n\n");
            md.append("分组：").append(item.query().group()).append("；结果数：").append(item.resultCount())
                    .append("；失败分析：").append(item.failure() == null ? "无执行错误" : item.failure()).append("。\n\n");
            for (RecipeSearchResult hit : item.results()) {
                RelevantRecipe label = item.query().relevantRecipes().stream()
                        .filter(row -> row.id() == hit.recipeId()).findFirst().orElse(null);
                md.append("- #").append(hit.rank()).append(" 菜谱 ID ").append(hit.recipeId())
                        .append("；整体审定相关：").append(label == null ? "否" : "是（" + label.reason() + "）")
                        .append("；命中原因：").append(hit.matchedReasonCodes()).append("\n");
            }
            if (item.results().isEmpty()) md.append("- Top10：无结果\n");
            md.append("\n");
        });
        md.append("\n## 标签与成本说明\n\n报告头记录用户对整套候选标签的总体认可日期与范围，不虚构逐条审定记录。费用未知。真实混合评测仍须逐次取得付费运行授权；报告不保存密钥。\n");
        return md.toString();
    }

    private String json(Object value) {
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception exception) { return "{}"; }
    }

    private RecipeSearchCriteria criteria(EvalQuery query) { return criteria(query.constraints(), query.query()); }
    private RecipeSearchCriteria criteria(BoundaryCase query) { return criteria(query.constraints(), query.query()); }
    private RecipeSearchCriteria criteria(Constraints c, String query) {
        return new RecipeSearchCriteria(query, LIMIT, c.categoryCodes(), c.maxCookingMinutes(),
                c.includeUnknownCookingTime(), c.availableToolNames(), c.excludedIngredientNames(), c.viewerUserId());
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().toList();
    }
    private static long elapsedMicros(long start) { return (System.nanoTime() - start) / 1_000; }
    private static int percentile(List<Long> sorted, double p) { return sorted.get(Math.max(0, (int) Math.ceil(p * sorted.size()) - 1)).intValue(); }
    private static String safeMessage(Exception e) { return e.getClass().getSimpleName(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class T6EvaluationConfiguration {
        @Bean
        static T6EmbeddingProbe t6EmbeddingProbe() {
            return new T6EmbeddingProbe();
        }

        @Bean
        static BeanPostProcessor t6QueryEmbeddingDecorator(T6EmbeddingProbe probe) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof QueryEmbeddingService service ? probe.decorate(service) : bean;
                }
            };
        }
    }

    private enum Metric { HIT, RECALL, MRR }
    record Dataset(String datasetVersion, String indexVersion, String labelVersion, String labelSetSha256, String labelStatus,
            LabelApproval labelApproval, List<EvalQuery> queries, List<BoundaryCase> boundaryCases) { }
    record LabelApproval(String approvedAt, String scope) { }
    record EvalQuery(String id, String datasetVersion, String indexVersion, String query, String group,
            Constraints constraints, List<RelevantRecipe> relevantRecipes) { }
    record BoundaryCase(String id, String datasetVersion, String indexVersion, String query, String group,
            Constraints constraints, boolean expectedEmpty, String reason) { }
    record Constraints(List<String> categoryCodes, Integer maxCookingMinutes, boolean includeUnknownCookingTime,
            List<String> availableToolNames, List<String> excludedIngredientNames, Long viewerUserId) { }
    record RelevantRecipe(long id, String reason) { }
    record QueryResult(EvalQuery query, List<RecipeSearchResult> results, String failure,
            Long localMicros, Long embeddingMicros, Long endToEndMicros, int resultCount) { }
    record BoundaryResult(String id, String group, boolean expectedEmpty, int resultCount,
            boolean applicable, boolean passed, String reason,
            List<T6RecipeHardConditionAuditor.Violation> violations) { }
}
