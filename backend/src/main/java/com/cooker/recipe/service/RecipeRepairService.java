package com.cooker.recipe.service;

import com.cooker.recipe.dto.repair.RecipeRepairApproval;
import com.cooker.recipe.dto.repair.RecipeRepairResult;
import com.cooker.recipe.dto.repair.RecipeRepairEvidence;

/** 仅显式治理调用，没有普通用户HTTP、LLM工具或开机任务。 */
public interface RecipeRepairService {
    RecipeRepairResult dryRun(RecipeRepairEvidence evidence, RecipeRepairApproval approval);
    RecipeRepairResult apply(RecipeRepairEvidence evidence, RecipeRepairApproval approval);
    RecipeRepairResult rollback(RecipeRepairEvidence evidence, RecipeRepairApproval approval);
}
