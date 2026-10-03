package com.cooker.conversation.dto;

/** 当前用户会话中一个完整成功轮次的消息，只用于受限模型上下文。 */
public record ConversationContextMessage(long turnNo, String role, String content) { }
