package com.cooker.recipe.mapper;

import com.cooker.recipe.dto.RecipeEligibilityFilter;
import com.cooker.recipe.dto.RecipeSearchEligibility;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeSearchEligibilityMapper {
    List<RecipeSearchEligibility> findEligible(@Param("filter") RecipeEligibilityFilter filter);
}
