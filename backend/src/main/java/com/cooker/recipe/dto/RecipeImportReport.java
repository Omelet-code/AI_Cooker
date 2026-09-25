package com.cooker.recipe.dto;

import java.util.List;

/** 对显式初始化命令输出的审计统计，不包含原始 Markdown 或数据库敏感配置。 */
public record RecipeImportReport(
        long batchId,
        int candidateCount,
        int succeededCount,
        int skippedCount,
        int warningCount,
        int failedCount,
        List<String> failedPaths) {
}
