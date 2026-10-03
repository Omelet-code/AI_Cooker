package com.cooker.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.conversation.entity.ConversationEntity;
import com.cooker.conversation.vo.ConversationView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    ConversationEntity lockOwned(@Param("id") long id, @Param("userId") long userId);
    List<ConversationView> listOwned(@Param("userId") long userId, @Param("offset") long offset, @Param("size") int size);
    long countOwned(@Param("userId") long userId);
    ConversationView findOwned(@Param("id") long id, @Param("userId") long userId);
    int advanceTurn(@Param("id") long id, @Param("userId") long userId, @Param("turnNo") long turnNo);
    int touch(@Param("id") long id, @Param("userId") long userId);
}
