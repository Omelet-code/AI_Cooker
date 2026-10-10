package com.cooker.retrieval.diagnostic;

import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import com.cooker.retrieval.service.impl.RecipeSearchDocumentBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 零费用计划：沿用Builder及业务内容标识，不保存全文，不添加事实哈希审计。 */
public record IndexPlan(String database, String endpoint, String model, int dimension, int template,
        int stepsPerChunk, int maxCharacters, int batchSize, int recipes, int chunks, int firstHttpBound,
        long totalCharacters, int longestCharacters, int largestTokenUpperBound,
        Map<String, Integer> chunksByType, Map<Long, Integer> httpByRecipe, List<Item> items) {
    public record Item(long recipeId, long sourceDocumentId, String key, String contentHash, String sourceType,
            Long ownerUserId, String visibility, int characters, int tokenUpperBound) {
        public String id() { return recipeId + "/" + key; }
        public boolean matches(RecipeSearchDocumentChunk chunk) {
            return recipeId == chunk.recipeId() && sourceDocumentId == chunk.sourceDocumentId() && key.equals(chunk.key())
                    && contentHash.equals(chunk.contentHash()) && sourceType.equals(chunk.sourceType())
                    && java.util.Objects.equals(ownerUserId, chunk.ownerUserId()) && visibility.equals(chunk.visibility());
        }
    }
    public static IndexPlan build(String database, RetrievalIndexProperties properties, List<RecipeIndexProjection> recipes) {
        require("text-embedding-v4".equals(properties.getEmbeddingModel()) && properties.getEmbeddingDimension() == 1024
                && properties.getDocumentTemplateVersion() == 1 && properties.getMaxStepChunkSteps() == 4
                && properties.getMaxChunkCharacters() == 4000 && properties.getBatchSize() == 10, "配置与D1-C冻结版本不一致");
        var builder = new RecipeSearchDocumentBuilder(properties);
        List<Item> items = new ArrayList<>();
        Map<String, Integer> types = new TreeMap<>(); Map<Long, Integer> http = new TreeMap<>();
        long characters = 0; int longest = 0; int maxTokens = 0;
        for (RecipeIndexProjection recipe : recipes.stream().sorted(java.util.Comparator.comparing(RecipeIndexProjection::recipeId)).toList()) {
            require(recipe.sourceDocumentId() != null && "SYSTEM_HOWTOCOOK".equals(recipe.sourceType())
                    && recipe.ownerUserId() == null && "PUBLIC".equals(recipe.visibility()), "来源权限或原文关联超出本轮系统快照");
            List<RecipeSearchDocumentChunk> chunks = builder.build(recipe);
            http.put(recipe.recipeId(), (chunks.size() + 9) / 10);
            for (RecipeSearchDocumentChunk chunk : chunks) {
                // 字节级BPE每个文本Token至少占一个UTF-8字节；另留32个封装余量，不称精确Token统计。
                int upper = tokenUpperBound(chunk.content());
                require(!chunk.content().isBlank() && upper <= 8192, "完整检索块无法保守排除8192 Token超限，停止而不截断");
                items.add(new Item(chunk.recipeId(), chunk.sourceDocumentId(), chunk.key(), chunk.contentHash(),
                        chunk.sourceType(), chunk.ownerUserId(), chunk.visibility(), chunk.content().length(), upper));
                types.merge(chunk.chunkType(), 1, Integer::sum);
                characters += chunk.content().length(); longest = Math.max(longest, chunk.content().length()); maxTokens = Math.max(maxTokens, upper);
            }
        }
        require(items.stream().map(Item::id).distinct().count() == items.size(), "计划包含重复菜谱或块标识");
        return new IndexPlan(database, properties.getEndpoint(), properties.getEmbeddingModel(), 1024, 1, 4, 4000, 10,
                recipes.size(), items.size(), http.values().stream().mapToInt(Integer::intValue).sum(), characters, longest, maxTokens,
                types, http, List.copyOf(items));
    }
    public static int tokenUpperBound(String content) { return Math.addExact(content.getBytes(StandardCharsets.UTF_8).length, 32); }
    static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
