package com.cooker.conversation.service;

import com.cooker.conversation.vo.ChatMessageView;
import com.cooker.conversation.vo.ConversationView;
import com.cooker.conversation.vo.PageView;

public interface ConversationService {
    ConversationView create(String title);
    PageView<ConversationView> list(int page, int size);
    ConversationView get(String id);
    PageView<ChatMessageView> messages(String id, int page, int size);
}
