package com.cooker.retrieval.dto;

/**
 * 显式索引任务的可审计统计，不包含外部服务响应或任何密钥。
 *
 * @param recipeCount   本次扫描到的结构化菜谱数
 * @param totalChunks   按当前模板计算出的检索块总数
 * @param indexedChunks 本次真正调用模型并写入向量的块数
 * @param skippedChunks 内容哈希与向量版本均未变化、直接复用旧向量的块数
 * @param failedChunks  向量化失败并被标记为可重试的块数
 */
public record RecipeIndexReport(int recipeCount, int totalChunks, int indexedChunks, int skippedChunks, int failedChunks) {
}
