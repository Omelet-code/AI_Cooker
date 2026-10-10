package com.cooker.conversation.service.impl;

import com.cooker.common.ApiException;
import com.cooker.conversation.ConversationIdParser;
import com.cooker.conversation.entity.ConversationEntity;
import com.cooker.conversation.entity.ConversationTurnEntity;
import com.cooker.conversation.mapper.ChatMessageMapper;
import com.cooker.conversation.mapper.ConversationMapper;
import com.cooker.conversation.mapper.ConversationTurnMapper;
import com.cooker.conversation.service.ConversationTurnService;
import com.cooker.conversation.vo.TurnRegistration;
import com.cooker.conversation.vo.TurnRow;
import com.cooker.conversation.vo.TurnStatus;
import com.cooker.conversation.vo.TurnView;
import com.cooker.identity.service.CurrentUserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationTurnServiceImpl implements ConversationTurnService {
    private static final int USER_MAX = 4000;
    private static final int ANSWER_MAX = 8000;
    private final ConversationMapper conversations;
    private final ConversationTurnMapper turns;
    private final ChatMessageMapper messages;
    private final CurrentUserService currentUser;

    public ConversationTurnServiceImpl(ConversationMapper conversations, ConversationTurnMapper turns,
            ChatMessageMapper messages, CurrentUserService currentUser) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.currentUser = currentUser;
    }

    @Override @Transactional
    public TurnRegistration registerTurn(String conversationId, UUID requestId, String content) {
        long uid = currentUser.requireUserId();
        long cid = ConversationIdParser.parse(conversationId);
        validateText(content, USER_MAX, "用户内容不能为空且最多 4000 个字符");
        if (requestId == null) throw validation("requestId 不能为空");
        ConversationEntity conversation = lockConversation(cid, uid);

        // 父会话锁串行化同会话写入；锁顺序固定为会话、轮次、消息，只覆盖本地数据库事务。
        List<ConversationTurnEntity> expired = turns.lockExpired(cid);
        int recovered = 0;
        for (ConversationTurnEntity turn : expired) recovered += turns.expire(turn.id);
        // 必须在 requestId 重放的提前返回之前触碰父会话；仅实际回收到行时更新时间。
        if (recovered > 0 && conversations.touch(cid, uid) != 1)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "回收过期轮次时无法更新会话时间");

        ConversationTurnEntity replay = turns.lockByRequest(uid, cid, requestId);
        if (replay != null) {
            String stored = messages.userContent(replay.id);
            if (!replay.inputHash.equals(sha256(content)) || !content.equals(stored))
                throw conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 已用于不同内容");
            return new TurnRegistration(findTurn(uid, cid, replay.id), false);
        }
        if (turns.lockActive(cid) != null) throw conflict("CONVERSATION_BUSY", "会话已有进行中的轮次");

        long turnNo = conversation.nextTurnNo;
        if (turnNo == Long.MAX_VALUE || turnNo > Long.MAX_VALUE / 2)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "会话轮次编号已耗尽");
        ConversationTurnEntity turn = new ConversationTurnEntity();
        turn.conversationId = cid;
        turn.userId = uid;
        turn.requestId = requestId;
        turn.inputHash = sha256(content);
        turn.turnNo = turnNo;
        turn.status = TurnStatus.PENDING.name();
        // 截止时间使用数据库时钟，避免应用机器时钟偏差。
        turns.insertWithDeadline(turn);
        long userSequence = turnNo * 2 - 1;
        messages.insertMessage(cid, uid, turn.id, "USER", content, userSequence);
        if (conversations.advanceTurn(cid, uid, turnNo) != 1)
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "无法分配下一轮编号");
        return new TurnRegistration(findTurn(uid, cid, turn.id), true);
    }

    @Override @Transactional
    public TurnView startTurn(String conversationId, String turnId) {
        long uid = currentUser.requireUserId(), cid = ConversationIdParser.parse(conversationId), tid = ConversationIdParser.parse(turnId);
        lockConversation(cid, uid);
        ConversationTurnEntity turn = lockTurn(uid, cid, tid);
        if (turn.status.equals("PENDING") && turn.deadlineAt.isAfter(databaseNow(turn.id))) {
            if (turns.start(tid) == 1) {
                conversations.touch(cid, uid);
                return findTurn(uid, cid, tid);
            }
        }
        throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "轮次已过期或不处于待执行状态");
    }

    @Override @Transactional
    public TurnView succeedTurn(String conversationId, String turnId, String answer) {
        long uid = currentUser.requireUserId(), cid = ConversationIdParser.parse(conversationId), tid = ConversationIdParser.parse(turnId);
        validateText(answer, ANSWER_MAX, "助手内容不能为空且最多 8000 个字符");
        lockConversation(cid, uid);
        ConversationTurnEntity turn = lockTurn(uid, cid, tid);
        if (!turn.status.equals("RUNNING") || !turn.deadlineAt.isAfter(databaseNow(tid)))
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "轮次已过期或不处于执行状态");
        long sequence = turn.turnNo * 2;
        messages.insertMessage(cid, uid, tid, "ASSISTANT", answer, sequence);
        // 状态更新与助手消息同事务；任一写入失败都会整体回滚。
        if (turns.succeed(tid) != 1) throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "轮次已过期或不处于执行状态");
        conversations.touch(cid, uid);
        return findTurn(uid, cid, tid);
    }

    @Override @Transactional
    public TurnView failTurn(String conversationId, String turnId, String errorCode) {
        long uid = currentUser.requireUserId(), cid = ConversationIdParser.parse(conversationId), tid = ConversationIdParser.parse(turnId);
        if (!ConversationErrors.allowed(errorCode)) throw validation("错误代码无效");
        lockConversation(cid, uid);
        ConversationTurnEntity turn = lockTurn(uid, cid, tid);
        if (!turn.status.equals("PENDING") && !turn.status.equals("RUNNING"))
            throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "终态轮次不可修改");
        if (turns.fail(tid, errorCode) != 1) throw conflict("CONVERSATION_TURN_STATE_CONFLICT", "轮次状态已改变");
        conversations.touch(cid, uid);
        return findTurn(uid, cid, tid);
    }

    @Override @Transactional(readOnly = true)
    public TurnView getTurn(String conversationId, String turnId) {
        long uid = currentUser.requireUserId(), cid = ConversationIdParser.parse(conversationId), tid = ConversationIdParser.parse(turnId);
        TurnView view = findTurn(uid, cid, tid);
        if (view == null) throw ConversationServiceImpl.notFound("CONVERSATION_TURN_NOT_FOUND", "轮次不存在");
        return view;
    }

    @Override @Transactional
    public int recoverExpiredTurns(String conversationId) {
        long uid = currentUser.requireUserId(), cid = ConversationIdParser.parse(conversationId);
        lockConversation(cid, uid);
        List<ConversationTurnEntity> expired = turns.lockExpired(cid);
        int count = 0;
        for (ConversationTurnEntity turn : expired) count += turns.expire(turn.id);
        if (count > 0) conversations.touch(cid, uid);
        return count;
    }

    private ConversationEntity lockConversation(long cid, long uid) {
        ConversationEntity entity = conversations.lockOwned(cid, uid);
        if (entity == null) throw ConversationServiceImpl.notFound("CONVERSATION_NOT_FOUND", "会话不存在");
        return entity;
    }
    private ConversationTurnEntity lockTurn(long uid, long cid, long tid) {
        ConversationTurnEntity turn = turns.lockTurn(uid, cid, tid);
        if (turn == null) throw ConversationServiceImpl.notFound("CONVERSATION_TURN_NOT_FOUND", "轮次不存在");
        return turn;
    }
    private TurnView findTurn(long uid, long cid, long tid) {
        TurnRow row = turns.findOwned(uid, cid, tid);
        return row == null ? null : row.toView();
    }
    private java.time.OffsetDateTime databaseNow(long turnId) { return turns.databaseNow(turnId); }
    private static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前运行时不支持 SHA-256", ex); }
    }
    private static void validateText(String value, int max, String message) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) throw validation(message);
    }
    private static ApiException validation(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
