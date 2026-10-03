package com.cooker.retrieval.dto;

import com.cooker.recipe.dto.MealRole;
import com.cooker.recipe.dto.RecipeEligibilityFilter;
import java.util.List;

/** 可信后台参数；viewer必须由调用方认证并核验任务归属，不提供HTTP任意用户入口。 */
public record MealCandidateRequest(long viewerUserId, Mode mode, RecipeEligibilityFilter filter,
        List<RoleRequest> roles, Long seed) {
    public enum Mode { RANDOM, SEMANTIC }
    public record RoleRequest(MealRole role, int candidateCount) {}
}
