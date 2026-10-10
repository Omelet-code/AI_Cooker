package com.cooker.retrieval.service.impl;

import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import java.util.List;

/** 显式索引运行的可选保护；普通装配保持无账本，查询侧不接入本接口。 */
public interface IndexRunControl {
    IndexRunControl NONE = new IndexRunControl() { };
    default void checkRecipe(List<RecipeSearchDocumentChunk> chunks) { }
    default void prepareBatch(List<RecipeSearchDocumentChunk> chunks) { }
    default void beforeSend(List<String> texts) { }
    default void succeeded(Long inputTokens) { }
    default void failed(EmbeddingProviderException failure) { }
    default void writeFailed() { }
    default boolean allowCleanup() { return true; }
}
