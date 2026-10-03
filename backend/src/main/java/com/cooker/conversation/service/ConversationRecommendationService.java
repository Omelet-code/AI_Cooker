package com.cooker.conversation.service;

import com.cooker.conversation.dto.RecommendationContext;
import com.cooker.conversation.dto.RecommendationSnapshot;
import com.cooker.conversation.vo.TurnView;

public interface ConversationRecommendationService {
    RecommendationContext context(String conversationId, String turnId);
    RecommendationSnapshot get(String conversationId, String turnId);

    /** 助手正文、快照、成功状态与父会话时间在同一个短事务提交。 */
    TurnView complete(String conversationId, String turnId, String answer,
            String constraintsJson, String resultJson);
}
