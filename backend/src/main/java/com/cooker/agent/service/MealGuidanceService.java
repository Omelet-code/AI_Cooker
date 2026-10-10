package com.cooker.agent.service;

import com.cooker.agent.dto.MealHttpRequest;
import java.util.Map;

/** 指导生成与零模型回看独立于套餐选择。 */
public interface MealGuidanceService {
    MealPlanningService.Submission generate(String conversation,String meal,MealHttpRequest request);
    Map<String,Object> get(String conversation,String meal,String guide);
}
