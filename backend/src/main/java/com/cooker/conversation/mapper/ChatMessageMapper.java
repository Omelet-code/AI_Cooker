package com.cooker.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.conversation.entity.ChatMessageEntity;
import com.cooker.conversation.vo.ChatMessageView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
    List<ChatMessageView> listOwned(@Param("userId") long userId, @Param("conversationId") long conversationId,
            @Param("offset") long offset, @Param("size") int size);
    long countOwned(@Param("userId") long userId, @Param("conversationId") long conversationId);
    String userContent(@Param("turnId") long turnId);
    int insertMessage(@Param("conversationId") long conversationId, @Param("userId") long userId,
            @Param("turnId") long turnId, @Param("role") String role, @Param("content") String content,
            @Param("sequence") long sequence);
}
