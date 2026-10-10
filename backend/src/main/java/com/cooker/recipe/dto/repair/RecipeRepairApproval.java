package com.cooker.recipe.dto.repair;

import java.util.List;
import java.util.UUID;

/** 审批只选择固定候选与身份处理；没有可任意填写的事实字段。 */
public record RecipeRepairApproval(String schemaVersion, String ruleVersion, String inputHash,
        String manifestHash, String reviewHash, UUID requestKey, String targetDatabase,
        String targetIdentifier, String reviewer, List<String> allowedActions, List<Decision> decisions, String approvalHash) {
    public record Decision(String candidateKey, String candidateHash, String decision, String reason,
            String identityMode, Long ingredientId, String canonicalName, Integer addedSortOrder,
            boolean confirmAdded, boolean confirmNonIngredient) { }
}
