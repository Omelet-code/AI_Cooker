package com.cooker.conversation.vo;

import java.time.OffsetDateTime;

public record ChatMessageView(String id, String turnId, MessageRole role, String content, Long sequence, OffsetDateTime createdAt) { }
