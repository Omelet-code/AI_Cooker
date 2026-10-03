package com.cooker.conversation.service;

import com.cooker.conversation.vo.TurnRegistration;
import com.cooker.conversation.vo.TurnView;
import java.util.UUID;

public interface ConversationTurnService {
    TurnRegistration registerTurn(String conversationId, UUID requestId, String content);
    TurnView startTurn(String conversationId, String turnId);
    TurnView succeedTurn(String conversationId, String turnId, String answer);
    TurnView failTurn(String conversationId, String turnId, String errorCode);
    TurnView getTurn(String conversationId, String turnId);
    int recoverExpiredTurns(String conversationId);
}
