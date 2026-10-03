package com.cooker.conversation.vo;

import java.time.OffsetDateTime;

public record TurnView(String id, String conversationId, String requestId, long turnNo, TurnStatus status,
        OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt, TurnError error) { }
