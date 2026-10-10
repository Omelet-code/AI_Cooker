package com.cooker.agent.dto;

import com.cooker.agent.dto.ModelUsage;
import java.util.List;

public record ExecutionMetrics(int modelGatewayAttempts, int retrievalCalls,
        Integer queryEmbeddingRequests, long elapsedMs, List<ModelUsageEntry> modelUsages) {
    public record ModelUsageEntry(int attempt, Integer promptTokens, Integer completionTokens, Integer totalTokens) { }
}
