package com.cooker.recipe.service;

import com.cooker.recipe.dto.RecipeIndexProjection;
import java.util.List;

/**
 * 为检索投影提供当前结构化菜谱快照的跨模块只读接口。
 *
 * <p>检索模块只依赖本接口，不直接访问菜谱 Mapper 或实体；当前实现为同步全量读取，
 * 适合数百条种子数据的规模。
 */
public interface RecipeIndexProjectionService {
    /** 返回全部当前生效的结构化菜谱快照，供索引任务逐一构建检索块。 */
    List<RecipeIndexProjection> listCurrentRecipes();
}
