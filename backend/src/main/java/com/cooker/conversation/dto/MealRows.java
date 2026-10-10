package com.cooker.conversation.dto;

import java.time.OffsetDateTime;

/** 仅持久层使用的行映射；公共服务返回有限业务快照。 */
public final class MealRows {
    private MealRows() {}
    public static class Session {
        public long id, conversationId, userId;
        public Integer profileDiningCount, diningCount;
        public int currentVersion;
        public String diningCountSource, status, viewJson;
        public Long selectedOptionId;
        public OffsetDateTime createdAt, updatedAt;
    }
    public static class Action {
        public long id, mealId, conversationId, userId;
        public int version;
        public Long seed;
        public String requestId, type, status, inputText, parameters, inputHash, result, metrics, errorCode;
        public OffsetDateTime deadlineAt, createdAt, startedAt, finishedAt;
    }
}
