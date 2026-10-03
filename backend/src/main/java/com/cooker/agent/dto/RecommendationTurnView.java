package com.cooker.agent.dto;

import com.cooker.conversation.vo.TurnView;

public record RecommendationTurnView(TurnView turn, RecommendationResult result) { }
