package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.recipe.entity.RecipeEntity;
import com.cooker.recipe.entity.RecipeSourceEntity;
import com.cooker.recipe.mapper.RecipeIngredientMapper;
import com.cooker.recipe.mapper.RecipeMapper;
import com.cooker.recipe.mapper.RecipeSourceMapper;
import com.cooker.recipe.mapper.RecipeStepMapper;
import com.cooker.recipe.mapper.RecipeToolMapper;
import com.cooker.recipe.service.RecipeIndexProjectionService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 集中完成事实表回查，避免检索模块因方便而跨模块依赖菜谱持久层。
 * 目前种子数据仅数百条，采用清晰的按菜谱聚合读取；规模增长后可在本模块内部改为批量查询。
 */
@Service
public class RecipeIndexProjectionServiceImpl implements RecipeIndexProjectionService {
    private final RecipeMapper recipeMapper;
    private final RecipeSourceMapper sourceMapper;
    private final RecipeIngredientMapper ingredientMapper;
    private final RecipeToolMapper toolMapper;
    private final RecipeStepMapper stepMapper;

    public RecipeIndexProjectionServiceImpl(RecipeMapper recipeMapper, RecipeSourceMapper sourceMapper,
            RecipeIngredientMapper ingredientMapper, RecipeToolMapper toolMapper, RecipeStepMapper stepMapper) {
        this.recipeMapper = recipeMapper;
        this.sourceMapper = sourceMapper;
        this.ingredientMapper = ingredientMapper;
        this.toolMapper = toolMapper;
        this.stepMapper = stepMapper;
    }

    /** 全量读取当前菜谱；数据量较小，优先保证实现直观且聚合结果完整。 */
    @Override
    public List<RecipeIndexProjection> listCurrentRecipes() {
        return recipeMapper.selectList(null).stream().map(this::toProjection).toList();
    }

    /**
     * 按菜谱聚合来源、原料、厨具和步骤。
     * 缺少来源记录属于数据不一致，直接抛错让索引任务暴露问题，而不是生成不完整的块。
     */
    private RecipeIndexProjection toProjection(RecipeEntity recipe) {
        RecipeSourceEntity source = sourceMapper.selectById(recipe.sourceId);
        if (source == null) {
            throw new IllegalStateException("菜谱缺少来源记录，无法构建检索投影：recipeId=" + recipe.id);
        }
        return new RecipeIndexProjection(recipe.id, recipe.sourceDocumentId, source.sourceType, source.ownerUserId,
                source.visibility, recipe.name, recipe.categoryCode, recipe.summary, recipe.difficulty,
                recipe.estimatedMinutes, recipe.servings, recipe.caloriesKcal,
                ingredientMapper.findIndexIngredientNames(recipe.id), toolMapper.findIndexToolNames(recipe.id),
                stepMapper.findIndexStepContents(recipe.id));
    }
}
