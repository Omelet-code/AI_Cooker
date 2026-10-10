package com.cooker.retrieval.diagnostic;

import static org.assertj.core.api.Assertions.*;
import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import com.cooker.retrieval.service.impl.EmbeddingProviderException;
import com.cooker.retrieval.service.impl.IndexRunStoppedException;
import com.cooker.retrieval.service.impl.RecipeSearchDocumentBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 纯本地账本验证，无平台Key或远程HTTP；每项使用独立临时目录。 */
class BoundedIndexLedgerTest {
    @TempDir Path directory;
    static RetrievalIndexProperties properties() { var props = new RetrievalIndexProperties(); props.setEndpoint(DocumentIndexTool.DEFAULT_ENDPOINT); return props; }
    static RecipeIndexProjection recipe(long id, int steps) {
        return new RecipeIndexProjection(id,id,"SYSTEM_HOWTOCOOK",null,"PUBLIC","鸡蛋","meat_dish",null,null,null,null,null,
                List.of("鸡蛋"),List.of("锅"),java.util.stream.IntStream.range(0,steps).mapToObj(i -> "完整步骤"+i).toList());
    }
    static IndexPlan plan(List<RecipeIndexProjection> recipes) { return IndexPlan.build(DocumentIndexTool.DATABASE, properties(), recipes); }
    static List<RecipeSearchDocumentChunk> chunks(RecipeIndexProjection recipe) { return new RecipeSearchDocumentBuilder(properties()).build(recipe); }
    static void send(BoundedIndexLedger ledger, List<RecipeSearchDocumentChunk> chunks) {
        ledger.checkRecipe(chunks); ledger.prepareBatch(chunks); ledger.beforeSend(chunks.stream().map(RecipeSearchDocumentChunk::content).toList());
    }
    @Test void 逐菜求和而不是总块数除十() {
        var plan=plan(List.of(recipe(1,1),recipe(2,1),recipe(3,33)));
        assertThat(plan.chunks()).isEqualTo(20); assertThat(plan.firstHttpBound()).isEqualTo(4);
        assertThat(plan.httpByRecipe()).containsEntry(1L,1).containsEntry(2L,1).containsEntry(3L,2);
    }
    @Test void 超长完整步骤预检停止而不截断() {
        var recipe=recipe(1,1);
        var longRecipe=new RecipeIndexProjection(1L,1L,recipe.sourceType(),null,"PUBLIC","菜","meat",null,null,null,null,null,List.of(),List.of(),List.of("水".repeat(3000)));
        assertThatThrownBy(() -> plan(List.of(longRecipe))).hasMessageContaining("8192");
        assertThat(IndexPlan.tokenUpperBound("水水")).isEqualTo(38);
    }
    @Test void 首轮失败一次跨进程恢复计数不清零每块最多两次() throws Exception {
        var recipe=recipe(1,1); var plan=plan(List.of(recipe)); var chunks=chunks(recipe);
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("execute"); send(ledger,chunks);
            ledger.failed(new EmbeddingProviderException("测试网络失败",EmbeddingProviderException.Kind.NETWORK)); ledger.finish(); }
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("recover"); send(ledger,chunks); ledger.failed(new EmbeddingProviderException("测试网络失败",EmbeddingProviderException.Kind.NETWORK));
            assertThat(ledger.summary()).containsEntry("httpAttempts",2).containsEntry("sentTexts",8).containsEntry("maxSendsPerChunk",2);
            assertThatThrownBy(() -> send(ledger,chunks)).isInstanceOf(IndexRunStoppedException.class); ledger.finish(); }
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { assertThatThrownBy(() -> ledger.begin("recover")).hasMessageContaining("一次"); }
    }
    @Test void 成功响应禁止缺失块再次生成且usage只存数值() throws Exception {
        var recipe=recipe(1,1); var plan=plan(List.of(recipe));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("execute"); send(ledger,chunks(recipe)); ledger.succeeded(19L); ledger.finish(); }
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("recover"); assertThatThrownBy(() -> send(ledger,chunks(recipe))).hasMessageContaining("已成功");
            assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("observedUsageTokens",19L); }
        assertThat(Files.readString(directory.resolve("attempts.jsonl"))).doesNotContain("完整步骤","Bearer");
    }
    @Test void 已记账但结果未知不能跨进程静默恢复() throws Exception {
        var recipe=recipe(1,1); var plan=plan(List.of(recipe));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("execute"); send(ledger,chunks(recipe)); }
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("unresolvedAttempt",true);
            assertThatThrownBy(() -> ledger.begin("recover")).hasMessageContaining("结果未知"); }
    }
    @Test void 互斥锁拒绝第二进程并在关闭后释放() throws Exception {
        var plan=plan(List.of(recipe(1,1)));
        try(var first=new BoundedIndexLedger(directory,plan,0)) { assertThatThrownBy(() -> new BoundedIndexLedger(directory,plan,0)).hasMessageContaining("互斥锁"); }
        try(var next=new BoundedIndexLedger(directory,plan,0)) { assertThat(next.summary()).containsEntry("httpAttempts",0); }
    }
    @Test void 配置事实及来源漂移不允许更换预检覆盖旧账本() throws Exception {
        var plan=plan(List.of(recipe(1,1)));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { }
        assertThatThrownBy(() -> new BoundedIndexLedger(directory,plan(List.of(recipe(1,2))),0)).hasMessageContaining("漂移");
        var props=properties(); props.setDocumentTemplateVersion(2);
        assertThatThrownBy(() -> IndexPlan.build(DocumentIndexTool.DATABASE,props,List.of(recipe(1,1)))).hasMessageContaining("冻结");
    }
    @Test void 总HTTP上界在发送前拒绝且首轮不能重复使用() throws Exception {
        var recipe=recipe(1,1); var plan=plan(List.of(recipe)); var chunks=chunks(recipe);
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("execute");
            ledger.prepareBatch(chunks.subList(0,1)); ledger.beforeSend(List.of(chunks.getFirst().content())); ledger.failed(new EmbeddingProviderException("瞬时",EmbeddingProviderException.Kind.SERVER));
            ledger.prepareBatch(chunks.subList(1,2)); assertThatThrownBy(() -> ledger.beforeSend(List.of(chunks.get(1).content()))).hasMessageContaining("HTTP授权上界"); ledger.finish();
            assertThatThrownBy(() -> ledger.begin("execute")).hasMessageContaining("首轮已使用"); }
    }
    @Test void 连续三批网络或限流失败停止并持久禁止恢复() throws Exception {
        var recipes=List.of(recipe(1,1),recipe(2,1),recipe(3,1),recipe(4,1)); var plan=plan(recipes);
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("execute");
            for(int i=0;i<2;i++) {send(ledger,chunks(recipes.get(i))); ledger.failed(new EmbeddingProviderException("瞬时",EmbeddingProviderException.Kind.RATE_LIMIT));}
            send(ledger,chunks(recipes.get(2))); assertThatThrownBy(() -> ledger.failed(new EmbeddingProviderException("瞬时",EmbeddingProviderException.Kind.NETWORK))).isInstanceOf(IndexRunStoppedException.class);
            assertThat(ledger.summary()).containsEntry("httpAttempts",3).containsEntry("stopped",true); }
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { assertThatThrownBy(() -> ledger.begin("recover")).hasMessageContaining("全局停止"); }
    }
    @Test void 数据库写失败不能重发已返回向量() throws Exception {
        var recipe=recipe(1,1); var plan=plan(List.of(recipe));
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { ledger.begin("execute"); send(ledger,chunks(recipe)); ledger.succeeded(null);
            assertThatThrownBy(ledger::writeFailed).hasMessageContaining("投影写入失败"); assertThat(ledger.summary()).containsEntry("usageUnknownResponses",1L); }
        try(var ledger=new BoundedIndexLedger(directory,plan,0)) { assertThatThrownBy(() -> ledger.begin("recover")).hasMessageContaining("全局停止"); }
    }
    @Test void 显式目标和官方地址连接前拒绝非法值() {
        for(String target:List.of("jdbc:postgresql://localhost:5433/cooker","jdbc:postgresql://localhost:5433/cooker_rebuild_20261007","jdbc:postgresql://localhost:5432/"+DocumentIndexTool.DATABASE,"jdbc:postgresql://root:secret@localhost:5433/"+DocumentIndexTool.DATABASE))
            assertThatThrownBy(() -> DocumentIndexTool.validateTarget(target)).hasMessageNotContaining("secret");
        DocumentIndexTool.validateTarget("jdbc:postgresql://localhost:5433/"+DocumentIndexTool.DATABASE);
        DocumentIndexTool.validateEndpoint(DocumentIndexTool.DEFAULT_ENDPOINT);
        for(String endpoint:List.of("http://dashscope.aliyuncs.com/embedding","https://dashscope.aliyuncs.com.evil.invalid/embedding","https://secret@dashscope.aliyuncs.com/embedding","https://dashscope.aliyuncs.com/embedding?key=secret"))
            assertThatThrownBy(() -> DocumentIndexTool.validateEndpoint(endpoint)).hasMessageNotContaining("secret");
    }
    @Test void 账本损坏保留现场而不清零() throws Exception {
        var plan=plan(List.of(recipe(1,1))); try(var ledger=new BoundedIndexLedger(directory,plan,0)) { }
        Files.writeString(directory.resolve("attempts.jsonl"),"{不完整");
        assertThatThrownBy(() -> new BoundedIndexLedger(directory,plan,0)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(directory.resolve("attempts.jsonl"))).isEqualTo("{不完整");
    }
}
