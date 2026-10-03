package com.cooker.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("conversation_turn")
public class ConversationTurnEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long conversationId;
    public Long userId;
    public UUID requestId;
    public String inputHash;
    public Long turnNo;
    public String status;
    public OffsetDateTime createdAt;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
    public OffsetDateTime deadlineAt;
    public String errorCode;
}
