package com.cooker.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("conversation_recommendation")
public class ConversationRecommendationEntity {
    @TableId(type = IdType.INPUT) public Long turnId;
    public Long conversationId;
    public Long userId;
    public Integer schemaVersion;
    public String constraintsJson;
    public String resultJson;
    public OffsetDateTime createdAt;
}
