package com.cooker.agent.service;

import com.cooker.agent.dto.MealHttpRequest;
import com.cooker.conversation.vo.PageView;
import java.util.Map;

public interface MealPlanningService {
    record Submission(int status,String location,Map<String,Object> body) {}
    Submission submit(String conversationId,String mealId,String type,MealHttpRequest request);
    Map<String,Object> get(String conversationId,String mealId);
    Map<String,Object> action(String conversationId,String mealId,String actionId);
    PageView<Map<String,Object>> list(String conversationId,int page,int size);
}
