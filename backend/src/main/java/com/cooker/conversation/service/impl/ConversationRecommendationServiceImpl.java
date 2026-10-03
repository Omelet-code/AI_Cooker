package com.cooker.conversation.service.impl;

import com.cooker.common.ApiException;
import com.cooker.conversation.ConversationIdParser;
import com.cooker.conversation.dto.ConversationContextMessage;
import com.cooker.conversation.dto.RecommendationContext;
import com.cooker.conversation.dto.RecommendationSnapshot;
import com.cooker.conversation.mapper.ChatMessageMapper;
import com.cooker.conversation.mapper.ConversationMapper;
import com.cooker.conversation.mapper.ConversationRecommendationMapper;
import com.cooker.conversation.mapper.ConversationTurnMapper;
import com.cooker.conversation.service.ConversationRecommendationService;
import com.cooker.conversation.vo.RecommendationContextMessageRow;
import com.cooker.conversation.vo.TurnRow;
import com.cooker.conversation.vo.TurnView;
import com.cooker.identity.service.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationRecommendationServiceImpl implements ConversationRecommendationService {
    private static final int MAX_SNAPSHOT_BYTES = 64 * 1024;
    private static final int MAX_ANSWER_CODE_POINTS = 8000;

    private final ConversationMapper conversations;
    private final ConversationTurnMapper turns;
    private final ChatMessageMapper messages;
    private final ConversationRecommendationMapper recommendations;
    private final CurrentUserService currentUser;
    private final ObjectMapper json;

    public ConversationRecommendationServiceImpl(ConversationMapper conversations, ConversationTurnMapper turns,
            ChatMessageMapper messages, ConversationRecommendationMapper recommendations,
            CurrentUserService currentUser, ObjectMapper json) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.recommendations = recommendations;
        this.currentUser = currentUser;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public RecommendationContext context(String conversationId, String turnId) {
        long userId = currentUser.requireUserId();
        long cid = ConversationIdParser.parse(conversationId);
        long tid = ConversationIdParser.parse(turnId);
        TurnRow current = requireOwned(userId, cid, tid);
        String effective = recommendations.latestEffectiveConstraints(userId, cid, current.turnNo());
        List<ConversationContextMessage> history = recommendations
                .recentSuccessfulMessages(userId, cid, current.turnNo()).stream()
                .map(ConversationRecommendationServiceImpl::toMessage).toList();
        return new RecommendationContext(effective, history);
    }

    @Override
    @Transactional(readOnly = true)
    public RecommendationSnapshot get(String conversationId, String turnId) {
        long userId = currentUser.requireUserId();
        long cid = ConversationIdParser.parse(conversationId);
        long tid = ConversationIdParser.parse(turnId);
        requireOwned(userId, cid, tid);
        return recommendations.findOwned(userId, cid, tid);
    }

    @Override
    @Transactional
    public TurnView complete(String conversationId, String turnId, String answer,
            String constraintsJson, String resultJson) {
        long userId = currentUser.requireUserId();
        long cid = ConversationIdParser.parse(conversationId);
        long tid = ConversationIdParser.parse(turnId);
        validateSnapshot(answer, constraintsJson, resultJson);

        // 锁序复用 T13：父会话 → 轮次 → 消息/快照写入，数据库调用之外没有外层事务。
        if (conversations.lockOwned(cid, userId) == null) throw notFound("CONVERSATION_NOT_FOUND");
        var turn = turns.lockTurn(userId, cid, tid);
        if (turn == null) throw notFound("CONVERSATION_TURN_NOT_FOUND");
        if (!"RUNNING".equals(turn.status) || !turn.deadlineAt.isAfter(turns.databaseNow(tid)))
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "轮次已过期或不处于执行状态");

        long sequence = turn.turnNo * 2;
        if (messages.insertMessage(cid, userId, tid, "ASSISTANT", answer, sequence) != 1)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "无法保存助手消息");
        if (recommendations.insertSnapshot(tid, cid, userId, constraintsJson, resultJson) != 1)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "无法保存推荐结果");
        if (turns.succeed(tid) != 1)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "轮次已过期或不处于执行状态");
        if (conversations.touch(cid, userId) != 1)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "无法更新会话时间");
        return toView(turns.findOwned(userId, cid, tid));
    }

    private TurnRow requireOwned(long userId, long cid, long tid) {
        TurnRow row = turns.findOwned(userId, cid, tid);
        if (row == null) throw notFound("CONVERSATION_TURN_NOT_FOUND");
        return row;
    }

    private void validateSnapshot(String answer, String constraintsJson, String resultJson) {
        if (answer == null || answer.isBlank() || answer.codePointCount(0, answer.length()) > MAX_ANSWER_CODE_POINTS
                || constraintsJson == null || resultJson == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "推荐结果无效");
        }
        try {
            JsonNode constraints = json.readTree(constraintsJson);
            JsonNode result = json.readTree(resultJson);
            int bytes = constraintsJson.getBytes(StandardCharsets.UTF_8).length
                    + resultJson.getBytes(StandardCharsets.UTF_8).length;
            if (constraints == null || !constraints.isObject() || result == null || !result.isObject()
                    || !answer.equals(result.path("answer").asText(null)) || bytes > MAX_SNAPSHOT_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, bytes > MAX_SNAPSHOT_BYTES
                        ? "RESOURCE_LIMIT" : "VALIDATION_ERROR", "推荐结果无效");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "推荐结果无效");
        }
    }

    private static ConversationContextMessage toMessage(RecommendationContextMessageRow row) {
        return new ConversationContextMessage(row.turnNo(), row.role(), row.content());
    }

    private static TurnView toView(TurnRow row) { return row == null ? null : row.toView(); }
    private static ApiException notFound(String code) { return new ApiException(HttpStatus.NOT_FOUND, code, "轮次不存在"); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
