package com.cooker.conversation.service.impl;

import com.cooker.common.ApiException;
import com.cooker.conversation.ConversationIdParser;
import com.cooker.conversation.entity.ConversationEntity;
import com.cooker.conversation.mapper.ChatMessageMapper;
import com.cooker.conversation.mapper.ConversationMapper;
import com.cooker.conversation.service.ConversationService;
import com.cooker.conversation.vo.ChatMessageView;
import com.cooker.conversation.vo.ConversationView;
import com.cooker.conversation.vo.PageView;
import com.cooker.identity.service.CurrentUserService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationServiceImpl implements ConversationService {
    private static final int MAX_PAGE_SIZE = 50;
    private final ConversationMapper conversations;
    private final ChatMessageMapper messages;
    private final CurrentUserService currentUser;

    public ConversationServiceImpl(ConversationMapper conversations, ChatMessageMapper messages, CurrentUserService currentUser) {
        this.conversations = conversations;
        this.messages = messages;
        this.currentUser = currentUser;
    }

    @Override @Transactional
    public ConversationView create(String title) {
        long userId = currentUser.requireUserId();
        String normalized = title == null ? "新对话" : title.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 100)
            throw validation("标题不能为空且最多 100 个字符");
        ConversationEntity entity = new ConversationEntity();
        entity.userId = userId;
        entity.title = normalized;
        entity.nextTurnNo = 1L;
        conversations.insert(entity);
        return conversations.findOwned(entity.id, entity.userId);
    }

    @Override @Transactional(readOnly = true)
    public PageView<ConversationView> list(int page, int size) {
        long userId = currentUser.requireUserId();
        validatePage(page, size);
        long offset = (long) (page - 1) * size;
        return new PageView<>(conversations.listOwned(userId, offset, size), page, size, conversations.countOwned(userId));
    }

    @Override @Transactional(readOnly = true)
    public ConversationView get(String id) {
        long userId = currentUser.requireUserId();
        long parsed = ConversationIdParser.parse(id);
        ConversationView view = conversations.findOwned(parsed, userId);
        if (view == null) throw notFound("CONVERSATION_NOT_FOUND", "会话不存在");
        return view;
    }

    @Override @Transactional(readOnly = true)
    public PageView<ChatMessageView> messages(String id, int page, int size) {
        long userId = currentUser.requireUserId();
        validatePage(page, size);
        long conversationId = ConversationIdParser.parse(id);
        if (conversations.findOwned(conversationId, userId) == null)
            throw notFound("CONVERSATION_NOT_FOUND", "会话不存在");
        long offset = (long) (page - 1) * size;
        List<ChatMessageView> items = messages.listOwned(userId, conversationId, offset, size);
        return new PageView<>(items, page, size, messages.countOwned(userId, conversationId));
    }

    static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) throw validation("分页参数无效");
    }
    static ApiException validation(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
}
