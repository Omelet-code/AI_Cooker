package com.cooker.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("chat_message")
public class ChatMessageEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long conversationId;
    public Long userId;
    public Long turnId;
    public String role;
    public String content;
    public Long sequence;
    public OffsetDateTime createdAt;
}
