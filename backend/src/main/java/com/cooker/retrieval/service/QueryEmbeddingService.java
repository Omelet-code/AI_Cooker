package com.cooker.retrieval.service;

/** 查询向量临时生成，缺少 Key 或 Provider 失败时由检索服务执行词法降级。 */
public interface QueryEmbeddingService {
    float[] embed(String query);
}
