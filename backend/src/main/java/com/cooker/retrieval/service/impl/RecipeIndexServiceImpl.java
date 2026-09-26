package com.cooker.retrieval.service.impl;

import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.recipe.service.RecipeIndexProjectionService;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeIndexReport;
import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import com.cooker.retrieval.entity.RecipeSearchDocumentEntity;
import com.cooker.retrieval.mapper.RecipeSearchDocumentMapper;
import com.cooker.retrieval.service.RecipeIndexService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 检索投影重建编排。事实表已经提交后才执行本服务，因此百炼失败不会回滚菜谱导入。
 * 同一菜谱中每种块仅以内容、模板和向量版本判断是否过期，避免重复付费调用。
 */
@Service
@Profile("recipe-index")
@ConditionalOnProperty(prefix = "cooker.retrieval.index", name = "enabled", havingValue = "true")
public class RecipeIndexServiceImpl implements RecipeIndexService {
    private final RecipeIndexProjectionService projectionService;
    private final RecipeSearchDocumentBuilder documentBuilder;
    private final RecipeSearchDocumentMapper documentMapper;
    private final EmbeddingModel documentEmbeddingModel;
    private final RetrievalIndexProperties properties;

    public RecipeIndexServiceImpl(RecipeIndexProjectionService projectionService,
            RecipeSearchDocumentBuilder documentBuilder, RecipeSearchDocumentMapper documentMapper,
            @Qualifier("documentEmbeddingModel") EmbeddingModel documentEmbeddingModel,
            RetrievalIndexProperties properties) {
        this.projectionService = projectionService;
        this.documentBuilder = documentBuilder;
        this.documentMapper = documentMapper;
        this.documentEmbeddingModel = documentEmbeddingModel;
        this.properties = properties;
    }

    /** 遍历全部结构化菜谱逐个重建；单道菜谱失败不会中断其余菜谱。 */
    @Override
    public RecipeIndexReport rebuildAll() {
        validateProperties();
        int totalChunks = 0;
        int indexedChunks = 0;
        int skippedChunks = 0;
        int failedChunks = 0;
        List<RecipeIndexProjection> projections = projectionService.listCurrentRecipes();
        for (RecipeIndexProjection projection : projections) {
            RecipeIndexOutcome outcome = rebuildRecipe(projection);
            totalChunks += outcome.totalChunks;
            indexedChunks += outcome.indexedChunks;
            skippedChunks += outcome.skippedChunks;
            failedChunks += outcome.failedChunks;
        }
        return new RecipeIndexReport(projections.size(), totalChunks, indexedChunks, skippedChunks, failedChunks);
    }

    /**
     * 重建单道菜谱的投影：先按模板生成块集合，再只对过期块分批调用模型。
     * 某个批次失败时只把该批标记为可重试，不影响同一菜谱中已经成功的块。
     */
    private RecipeIndexOutcome rebuildRecipe(RecipeIndexProjection projection) {
        List<RecipeSearchDocumentChunk> chunks = documentBuilder.build(projection);
        Map<String, RecipeSearchDocumentEntity> existing = existingByKey(projection.recipeId());
        List<RecipeSearchDocumentChunk> pending = chunks.stream()
                .filter(chunk -> !isCurrent(existing.get(chunk.key()), chunk)).toList();
        int indexed = 0;
        int failed = 0;
        for (int offset = 0; offset < pending.size(); offset += properties.getBatchSize()) {
            List<RecipeSearchDocumentChunk> batch = pending.subList(offset,
                    Math.min(offset + properties.getBatchSize(), pending.size()));
            try {
                List<float[]> vectors = documentEmbeddingModel.embed(batch.stream()
                        .map(RecipeSearchDocumentChunk::content).toList());
                if (vectors.size() != batch.size()) {
                    throw new EmbeddingProviderException("EmbeddingModel 返回的向量数量与输入不一致");
                }
                for (int index = 0; index < batch.size(); index++) {
                    float[] vector = vectors.get(index);
                    if (vector == null || vector.length != properties.getEmbeddingDimension()) {
                        throw new EmbeddingProviderException("EmbeddingModel 返回的向量维度与配置不一致");
                    }
                    documentMapper.upsertReady(toEntity(batch.get(index)), vectorLiteral(vector));
                    indexed++;
                }
            } catch (RuntimeException ex) {
                String failureMessage = failureMessage(ex);
                for (RecipeSearchDocumentChunk chunk : batch) {
                    documentMapper.upsertFailed(toEntity(chunk), failureMessage);
                    failed++;
                }
            }
        }
        if (failed == 0) {
            // 仅整道菜谱的所有新块都可用后删除过期块，避免一次失败使可检索文本无故缩减。
            documentMapper.deleteChunksNotIn(projection.recipeId(), chunks.stream().map(RecipeSearchDocumentChunk::key).toList());
        }
        return new RecipeIndexOutcome(chunks.size(), indexed, chunks.size() - pending.size(), failed);
    }

    /** 把现有投影按 {@code chunkType:chunkNo} 建索引，便于与本次生成的块逐一比对。 */
    private Map<String, RecipeSearchDocumentEntity> existingByKey(Long recipeId) {
        Map<String, RecipeSearchDocumentEntity> existing = new HashMap<>();
        for (RecipeSearchDocumentEntity document : documentMapper.findByRecipeId(recipeId)) {
            existing.put(document.chunkType + ':' + document.chunkNo, document);
        }
        return existing;
    }

    /**
     * 判断某个块是否已有可直接复用的向量。
     * 只有状态为 READY，且内容哈希、模板版本、模型与维度四项全部一致才算未过期。
     */
    private boolean isCurrent(RecipeSearchDocumentEntity existing, RecipeSearchDocumentChunk chunk) {
        return existing != null && "READY".equals(existing.embeddingStatus)
                && chunk.contentHash().equals(existing.contentHash)
                && properties.getDocumentTemplateVersion() == existing.documentTemplateVersion
                && properties.getEmbeddingModel().equals(existing.embeddingModel)
                && properties.getEmbeddingDimension() == existing.embeddingDimension;
    }

    /** 把块转换为投影实体；向量与状态由 Mapper 的显式 SQL 负责写入。 */
    private RecipeSearchDocumentEntity toEntity(RecipeSearchDocumentChunk chunk) {
        RecipeSearchDocumentEntity entity = new RecipeSearchDocumentEntity();
        entity.recipeId = chunk.recipeId();
        entity.sourceDocumentId = chunk.sourceDocumentId();
        entity.sourceType = chunk.sourceType();
        entity.ownerUserId = chunk.ownerUserId();
        entity.visibility = chunk.visibility();
        entity.chunkType = chunk.chunkType();
        entity.chunkNo = chunk.chunkNo();
        entity.content = chunk.content();
        entity.contentHash = chunk.contentHash();
        entity.documentTemplateVersion = properties.getDocumentTemplateVersion();
        entity.embeddingModel = properties.getEmbeddingModel();
        entity.embeddingDimension = properties.getEmbeddingDimension();
        return entity;
    }

    /** 转换为 pgvector 的文本字面量，例如 {@code [0.1,-0.2]}，由数据库 CAST 成 vector 类型。 */
    private String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) literal.append(',');
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    /** 在调用收费接口前校验本地配置，避免用必然失败的参数发起批量请求。 */
    private void validateProperties() {
        if (properties.getBatchSize() < 1 || properties.getBatchSize() > 10) {
            throw new IllegalArgumentException("百炼 text-embedding-v4 每批文本数必须在 1 到 10 之间");
        }
        if (properties.getEmbeddingDimension() != 1024) {
            throw new IllegalArgumentException("当前检索表固定为 vector(1024)，Embedding 维度必须为 1024");
        }
    }

    /** 生成可入库的失败摘要：只保留预定分类文本，绝不写入第三方异常原文。 */
    private String failureMessage(RuntimeException ex) {
        if (ex instanceof EmbeddingProviderException) {
            // 该异常仅由本模块生成固定的脱敏摘要，不能把第三方异常文本持久化到数据库。
            return ex.getMessage();
        }
        return "向量化本地处理异常：" + ex.getClass().getSimpleName();
    }

    /** 单道菜谱的重建结果，仅用于汇总统计。 */
    private static final class RecipeIndexOutcome {
        private final int totalChunks;
        private final int indexedChunks;
        private final int skippedChunks;
        private final int failedChunks;

        private RecipeIndexOutcome(int totalChunks, int indexedChunks, int skippedChunks, int failedChunks) {
            this.totalChunks = totalChunks;
            this.indexedChunks = indexedChunks;
            this.skippedChunks = skippedChunks;
            this.failedChunks = failedChunks;
        }
    }
}
