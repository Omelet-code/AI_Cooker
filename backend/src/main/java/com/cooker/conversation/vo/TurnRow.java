package com.cooker.conversation.vo;

import java.time.OffsetDateTime;

public record TurnRow(String id, String conversationId, String requestId, Long turnNo, TurnStatus status,
        OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
        String errorCode, String errorMessage) {
    public TurnView toView() {
        TurnError error = errorCode == null ? null : new TurnError(errorCode, errorMessage);
        return new TurnView(id, conversationId, requestId, turnNo, status, createdAt, startedAt, finishedAt, error);
    }
}
