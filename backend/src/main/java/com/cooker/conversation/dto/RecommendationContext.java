package com.cooker.conversation.dto;

import java.util.List;

/** 最新有效约束与有界历史；JSON 文本仅在 Agent 内反序列化。 */
public record RecommendationContext(String effectiveConstraintsJson, List<ConversationContextMessage> messages) { }
