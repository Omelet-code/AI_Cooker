package com.cooker.retrieval.diagnostic;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.cooker.recipe.diagnostic.RecipeProjectionContext;
import com.cooker.recipe.service.RecipeIndexProjectionService;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.entity.RecipeSearchDocumentEntity;
import com.cooker.retrieval.mapper.RecipeSearchDocumentMapper;
import com.cooker.retrieval.service.impl.DashScopeEmbeddingModel;
import com.cooker.retrieval.service.impl.EmbeddingProviderException;
import com.cooker.retrieval.service.impl.IndexRunControl;
import com.cooker.retrieval.service.impl.IndexRunStoppedException;
import com.cooker.retrieval.service.impl.RecipeIndexServiceImpl;
import com.cooker.retrieval.service.impl.RecipeSearchDocumentBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/** D1-C唯一独立入口：不装配Boot/Web/Flyway/导入/Query/LLM，只写既有检索投影。 */
public final class DocumentIndexTool {
    public static final String DATABASE = "cooker_rebuild_d1r2_20261007";
    public static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final Path WORKSPACE = Path.of("C:/Users/Omelet/.codex/worktrees/5b09/Cooker");
    private static final Path RUN_DIRECTORY = WORKSPACE.resolve(".data/d1-c/d1c-20261008");
    private DocumentIndexTool() { }

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception exception) {
            System.err.println(exception instanceof IllegalArgumentException || exception instanceof IndexRunStoppedException
                    ? exception.getMessage() : "独立索引失败；费用账本与数据库现场保留，不得自动重置或重发");
            System.exit(1);
        }
    }
    public static void run(String[] args) throws Exception {
        IndexPlan.require(args.length == 4 && "--mode".equals(args[0]) && "--target".equals(args[2])
                && List.of("preflight", "execute", "recover").contains(args[1]), "必须显式指定preflight、execute或recover以及准确目标");
        String mode = args[1], target = args[3]; validateTarget(target);
        IndexPlan.require(target.equals(System.getenv("COOKER_D1C_JDBC_URL")) && "root".equals(System.getenv("COOKER_D1C_USER")), "目标与本次专用进程环境不一致");
        String password = System.getenv("COOKER_D1C_PASSWORD");
        IndexPlan.require(password != null && !password.isBlank(), "缺少本次进程数据库凭证");
        var source = new DriverManagerDataSource(target, "root", password);
        try (var connection = source.getConnection()) {
            IndexPlan.require(DATABASE.equals(connection.getCatalog()) && target.equals(connection.getMetaData().getURL())
                    && "root".equals(connection.getMetaData().getUserName()), "实际连接目标或角色不一致，停止");
        }
        var jdbc = new JdbcTemplate(source); Map<String, Object> before = facts(jdbc);
        validateFacts(jdbc, before);
        var properties = new RetrievalIndexProperties();
        String endpoint = System.getenv("COOKER_D1C_ENDPOINT");
        properties.setEndpoint(endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint); validateEndpoint(properties.getEndpoint());
        String key = System.getenv("DASHSCOPE_API_KEY");
        IndexPlan.require(key != null && !key.isBlank(), "DASHSCOPE_API_KEY未配置，停止而不生成替代Key");
        properties.setApiKey(key);
        var session = session(source);
        RecipeIndexProjectionService projection = RecipeProjectionContext.create(session);
        session.getConfiguration().addMapper(RecipeSearchDocumentMapper.class);
        var mapper = session.getMapper(RecipeSearchDocumentMapper.class);
        IndexPlan plan = IndexPlan.build(DATABASE, properties, projection.listCurrentRecipes());
        IndexPlan.require(plan.recipes() == 372, "事实菜谱集合与限定签收数量不一致");
        Instant start = Instant.now();
        try (var ledger = new BoundedIndexLedger(RUN_DIRECTORY, plan, 1100)) {
            Map<String, Object> auditBefore = audit(plan, mapper, jdbc);
            Map<String, Object> result = new LinkedHashMap<>(); result.put("mode", mode); result.put("start", start.toString());
            result.put("database", DATABASE); result.put("keyPresent", true); result.put("expectedChunks", plan.chunks());
            result.put("firstHttpBound", plan.firstHttpBound()); result.put("totalHttpBound", 2 * plan.firstHttpBound());
            result.put("totalCharacters", plan.totalCharacters()); result.put("longestCharacters", plan.longestCharacters());
            result.put("largestTokenUpperBound", plan.largestTokenUpperBound()); result.put("tokenMethod", "UTF-8字节数+32保守上界，非精确Token");
            result.put("chunksByType", plan.chunksByType()); result.put("factsBefore", before); result.put("auditBefore", auditBefore);
            RuntimeException failure = null;
            try {
                if ("preflight".equals(mode)) {
                    if (((Number) ledger.summary().get("httpAttempts")).intValue() == 0) IndexPlan.require(((Number) auditBefore.get("actual")).intValue() == 0, "初始投影不为空且没有本次发送账本，停止核实");
                    if (((Number) auditBefore.get("ready")).intValue() == plan.chunks()) {
                        // 使用禁止出站替身验证现有READY复用，替身不能产生向量或补写生产READY。
                        result.put("zeroCallReuse", new RecipeIndexServiceImpl(projection, new RecipeSearchDocumentBuilder(properties), mapper,
                                forbiddenModel(), properties, new IndexRunControl() { @Override public boolean allowCleanup() { return false; } }).rebuildAll());
                    }
                } else {
                    IndexPlan.require(((Number) auditBefore.get("unexpected")).intValue() == 0 && ((Number) auditBefore.get("mismatch")).intValue() == 0, "投影集合或版本来源异常，禁止覆盖");
                    if ("execute".equals(mode)) IndexPlan.require(((Number) auditBefore.get("actual")).intValue() == 0, "首轮投影必须为空，禁止盲目覆盖或重复收费");
                    if ("recover".equals(mode)) IndexPlan.require(((Number) auditBefore.get("ready")).intValue() < plan.chunks(), "全部READY，不需要使用恢复收费轮次");
                    ledger.begin(mode);
                    var model = new DashScopeEmbeddingModel(httpClient(), new ObjectMapper(), properties, ledger);
                    result.put("indexReport", new RecipeIndexServiceImpl(projection, new RecipeSearchDocumentBuilder(properties), mapper, model, properties, ledger).rebuildAll());
                    ledger.finish();
                }
            } catch (RuntimeException exception) {
                failure = exception;
                result.put("failure", exception instanceof IndexRunStoppedException || exception instanceof EmbeddingProviderException
                        || exception instanceof IllegalArgumentException ? exception.getMessage() : "索引本地失败，现场保留");
            } finally {
                result.put("ledger", ledger.summary()); result.put("auditAfter", audit(plan, mapper, jdbc));
                result.put("factsAfter", facts(jdbc)); result.put("end", Instant.now().toString());
                result.put("elapsedMillis", Duration.between(start, Instant.now()).toMillis());
                Path report = RUN_DIRECTORY.resolve(mode + "-" + UUID.randomUUID() + ".json");
                Files.writeString(report, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardOpenOption.CREATE_NEW);
                System.out.println("D1C_RESULT=" + new ObjectMapper().writeValueAsString(result));
            }
            if (failure != null) throw failure;
        }
    }
    public static void validateTarget(String target) {
        IndexPlan.require(List.of("jdbc:postgresql://localhost:5433/" + DATABASE, "jdbc:postgresql://127.0.0.1:5433/" + DATABASE).contains(target), "本次只允许localhost/127.0.0.1:5433/root精确最终库，拒绝旧库及附加参数");
    }
    public static void validateEndpoint(String endpoint) {
        URI uri;
        try { uri = URI.create(endpoint); } catch (Exception exception) { throw new IllegalArgumentException("Embedding目的地址格式非法"); }
        String host = uri.getHost();
        IndexPlan.require("https".equals(uri.getScheme()) && host != null && (List.of("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com").contains(host)
                || host.matches("[a-zA-Z0-9-]+\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com"))
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && "/api/v1/services/embeddings/text-embedding/text-embedding".equals(uri.getPath()), "只能使用现有阿里官方HTTPS原生Embedding地址，拒绝鉴权跳转或接口制式变化");
    }
    public static RestClient httpClient() {
        return httpClient(10_000, 30_000);
    }
    /** 测试可缩短本地替身超时；正式入口固定使用10秒连接、30秒读取。 */
    public static RestClient httpClient(int connectMillis, int readMillis) {
        // JDK HttpURLConnection的POST连接重试显式关闭；每个账本SEND最多对应一次POST，禁重定向携带Key。
        System.setProperty("sun.net.http.retryPost", "false"); System.setProperty("http.keepAlive", "false");
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectMillis); factory.setReadTimeout(readMillis);
        return RestClient.builder().requestFactory(factory).defaultStatusHandler(status -> status.value() >= 300 && status.value() < 400,
                (request, response) -> { throw new EmbeddingProviderException("百炼返回重定向，已禁止携带鉴权继续发送", EmbeddingProviderException.Kind.REDIRECT); }).build();
    }
    public static SqlSessionTemplate session(DataSource source) throws Exception {
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); configuration.setDefaultStatementTimeout(30);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory()); return new SqlSessionTemplate(factory.getObject());
    }
    public static Map<String, Object> audit(IndexPlan plan, RecipeSearchDocumentMapper mapper, JdbcTemplate jdbc) {
        int ready = 0, failed = 0, pending = 0, missing = 0, unexpected = 0, mismatch = 0, actual = 0;
        for (Long recipeId : plan.httpByRecipe().keySet()) {
            Map<String, IndexPlan.Item> expected = new java.util.HashMap<>();
            for (IndexPlan.Item item : plan.items()) if (item.recipeId() == recipeId) expected.put(item.key(), item);
            for (RecipeSearchDocumentEntity row : mapper.findByRecipeId(recipeId)) {
                actual++; IndexPlan.Item item = expected.remove(row.chunkType + ":" + row.chunkNo);
                if (item == null) { unexpected++; continue; }
                if (row.sourceDocumentId != item.sourceDocumentId() || !item.contentHash().equals(row.contentHash)
                        || !item.sourceType().equals(row.sourceType) || !java.util.Objects.equals(item.ownerUserId(), row.ownerUserId)
                        || !item.visibility().equals(row.visibility) || !plan.model().equals(row.embeddingModel)
                        || row.embeddingDimension != 1024 || row.documentTemplateVersion != 1) mismatch++;
                if ("READY".equals(row.embeddingStatus)) ready++; else if ("FAILED".equals(row.embeddingStatus)) failed++; else pending++;
            }
            missing += expected.size();
        }
        Map<String, Object> audit = new LinkedHashMap<>(); audit.put("expected", plan.chunks()); audit.put("actual", actual);
        audit.put("ready", ready); audit.put("failed", failed); audit.put("pending", pending); audit.put("missing", missing);
        audit.put("unexpected", unexpected); audit.put("mismatch", mismatch);
        audit.put("badReadyVectors", jdbc.queryForObject("SELECT count(*) FROM recipe_search_document WHERE embedding_status='READY' AND (embedding IS NULL OR vector_dims(embedding)<>1024 OR embedding::text ~ 'NaN|Infinity' OR indexed_at IS NULL)", Long.class));
        audit.put("nonemptyTsv", jdbc.queryForObject("SELECT count(*) FROM recipe_search_document WHERE length(tsv)>0", Long.class));
        audit.put("lexicalEggMatches", jdbc.queryForObject("SELECT count(*) FROM recipe_search_document WHERE tsv @@ plainto_tsquery('cooker_zh','鸡蛋')", Long.class));
        audit.put("coveredRecipes", jdbc.queryForObject("SELECT count(DISTINCT recipe_id) FROM recipe_search_document WHERE embedding_status='READY'", Long.class));
        return audit;
    }
    private static Map<String, Object> facts(JdbcTemplate jdbc) {
        return jdbc.queryForMap("SELECT (SELECT count(*) FROM recipe) recipes,(SELECT count(*) FROM recipe_source_document) documents,(SELECT count(*) FROM recipe_ingredient) materials,(SELECT count(*) FROM ingredient) ingredients,(SELECT count(*) FROM ingredient WHERE normalization_status='REVIEWED') reviewed,(SELECT count(*) FROM app_user) users,(SELECT count(*) FROM recipe_import_batch) batches");
    }
    private static void validateFacts(JdbcTemplate jdbc, Map<String, Object> facts) {
        for (Map.Entry<String, Integer> expected : Map.of("recipes",372,"documents",372,"materials",3102,"ingredients",976,"reviewed",12,"users",0,"batches",2).entrySet())
            IndexPlan.require(((Number) facts.get(expected.getKey())).intValue() == expected.getValue(), "最终库事实计数偏离D1-R2签收，停止");
        IndexPlan.require(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class) == 15, "本轮只复用V15，不执行迁移");
        IndexPlan.require(jdbc.queryForObject("SELECT count(*) FROM recipe r JOIN recipe_source_document d ON d.id=r.source_document_id JOIN recipe_source s ON s.id=r.source_id WHERE d.source_id=r.source_id AND d.source_version='1.6.0-a0b14bd' AND d.parse_status='SUCCESS' AND s.source_type='SYSTEM_HOWTOCOOK' AND s.visibility='PUBLIC' AND s.owner_user_id IS NULL", Long.class) == 372, "来源版本、原文关联或权限不符合本轮快照");
        IndexPlan.require(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE normalization_status='REVIEWED' AND id IN (4,10,11,12,35,56,63,105,138,432,486,614)", Long.class) == 12, "12审定ID发生漂移，停止");
    }
    private static EmbeddingModel forbiddenModel() {
        return new EmbeddingModel() {
            @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new IndexRunStoppedException("零费用复用核验出现出站企图，已阻断", false); }
            @Override public float[] embed(Document document) { throw new IndexRunStoppedException("零费用核验禁止生成向量", false); }
            @Override public int dimensions() { return 1024; }
        };
    }
}
