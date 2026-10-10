package com.cooker.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.conversation.entity.ConversationTurnEntity;
import com.cooker.conversation.vo.TurnRow;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationTurnMapper extends BaseMapper<ConversationTurnEntity> {
    List<ConversationTurnEntity> lockExpired(@Param("conversationId") long conversationId);
    ConversationTurnEntity lockByRequest(@Param("userId") long userId, @Param("conversationId") long conversationId, @Param("requestId") UUID requestId);
    ConversationTurnEntity lockActive(@Param("conversationId") long conversationId);
    ConversationTurnEntity lockTurn(@Param("userId") long userId, @Param("conversationId") long conversationId, @Param("turnId") long turnId);
    int expire(@Param("turnId") long turnId);
    int start(@Param("turnId") long turnId);
    int succeed(@Param("turnId") long turnId);
    int fail(@Param("turnId") long turnId, @Param("errorCode") String errorCode);
    int insertWithDeadline(ConversationTurnEntity turn);
    OffsetDateTime databaseNow(@Param("turnId") long turnId);
    TurnRow findOwned(@Param("userId") long userId, @Param("conversationId") long conversationId, @Param("turnId") long turnId);
}
