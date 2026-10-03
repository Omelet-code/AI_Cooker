package com.cooker.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("conversation")
public class ConversationEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public String title;
    public Long nextTurnNo;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
