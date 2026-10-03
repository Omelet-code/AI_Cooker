package com.cooker.conversation.vo;

/** Mapper 专用投影，避免把 Entity 泄漏到 agent 模块。 */
public record RecommendationContextMessageRow(Long turnNo, String role, String content) { }
