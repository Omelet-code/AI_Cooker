package com.cooker.agent.dto;

import java.util.List;

/** 经过服务器合并并可用于T5映射的约束；null厨具表示未知，空列表表示明确没有厨具。 */
public record RecommendationConstraints(String query, Integer maxCookingMinutes,
        List<String> availableToolNames, List<String> excludedIngredientNames,
        List<String> availableIngredientNames, String budgetYuan) { }
