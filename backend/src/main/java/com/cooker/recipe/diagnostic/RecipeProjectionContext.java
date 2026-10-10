package com.cooker.recipe.diagnostic;

import com.cooker.recipe.mapper.RecipeIngredientMapper;
import com.cooker.recipe.mapper.RecipeMapper;
import com.cooker.recipe.mapper.RecipeSourceMapper;
import com.cooker.recipe.mapper.RecipeStepMapper;
import com.cooker.recipe.mapper.RecipeToolMapper;
import com.cooker.recipe.service.RecipeIndexProjectionService;
import com.cooker.recipe.service.impl.RecipeIndexProjectionServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;

/** 独立索引的recipe内部装配帮助类；只复用既有投影Service和五个只读查询。 */
public final class RecipeProjectionContext {
    private RecipeProjectionContext() { }
    public static RecipeIndexProjectionService create(SqlSessionTemplate session) {
        for (Class<?> type : java.util.List.of(RecipeMapper.class, RecipeSourceMapper.class, RecipeIngredientMapper.class,
                RecipeToolMapper.class, RecipeStepMapper.class)) session.getConfiguration().addMapper(type);
        return new RecipeIndexProjectionServiceImpl(session.getMapper(RecipeMapper.class), session.getMapper(RecipeSourceMapper.class),
                session.getMapper(RecipeIngredientMapper.class), session.getMapper(RecipeToolMapper.class), session.getMapper(RecipeStepMapper.class));
    }
}
