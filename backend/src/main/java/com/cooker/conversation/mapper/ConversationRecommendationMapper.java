package com.cooker.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.conversation.dto.RecommendationSnapshot;
import com.cooker.conversation.entity.ConversationRecommendationEntity;
import com.cooker.conversation.vo.RecommendationContextMessageRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationRecommendationMapper extends BaseMapper<ConversationRecommendationEntity> {
    List<RecommendationContextMessageRow> recentSuccessfulMessages(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("beforeTurnNo") long beforeTurnNo);

    String latestEffectiveConstraints(@Param("userId") long userId, @Param("conversationId") long conversationId,
            @Param("beforeTurnNo") long beforeTurnNo);

    RecommendationSnapshot findOwned(@Param("userId") long userId, @Param("conversationId") long conversationId,
            @Param("turnId") long turnId);

    int insertSnapshot(@Param("turnId") long turnId, @Param("conversationId") long conversationId,
            @Param("userId") long userId, @Param("constraintsJson") String constraintsJson,
            @Param("resultJson") String resultJson);
}
