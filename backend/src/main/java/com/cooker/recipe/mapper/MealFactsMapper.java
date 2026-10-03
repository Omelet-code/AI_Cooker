package com.cooker.recipe.mapper;

import com.cooker.recipe.dto.MealFactRows.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MealFactsMapper {
    List<RecipeRow> recipes(@Param("viewerUserId") long viewerUserId, @Param("ids") List<Long> ids);
    List<IngredientRow> ingredients(@Param("viewerUserId") long viewerUserId, @Param("ids") List<Long> ids);
    List<StepRow> steps(@Param("viewerUserId") long viewerUserId, @Param("ids") List<Long> ids);
    List<ToolRow> tools(@Param("viewerUserId") long viewerUserId, @Param("ids") List<Long> ids);
}
