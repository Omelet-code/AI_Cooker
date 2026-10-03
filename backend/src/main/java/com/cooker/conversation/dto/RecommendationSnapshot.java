package com.cooker.conversation.dto;

/** JSONB 快照的服务边界表示，避免 conversation 依赖 agent 的业务类型。 */
public record RecommendationSnapshot(String constraintsJson, String resultJson) { }
