package com.cooker.conversation.vo;

import java.time.OffsetDateTime;

public record ConversationView(String id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
