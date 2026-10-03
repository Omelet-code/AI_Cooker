package com.cooker.agent.service;

import com.cooker.agent.dto.RecommendationSubmission;
import com.cooker.agent.dto.RecommendationTurnView;
import com.cooker.agent.dto.SendRecommendationRequest;

public interface RecommendationService {
    RecommendationSubmission send(String conversationId, SendRecommendationRequest request);
    RecommendationTurnView get(String conversationId, String turnId);
}
