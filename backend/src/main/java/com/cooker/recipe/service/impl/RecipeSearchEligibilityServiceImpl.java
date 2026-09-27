package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.RecipeEligibilityFilter;
import com.cooker.recipe.dto.RecipeSearchEligibility;
import com.cooker.recipe.mapper.RecipeSearchEligibilityMapper;
import com.cooker.recipe.service.RecipeSearchEligibilityService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeSearchEligibilityServiceImpl implements RecipeSearchEligibilityService {
    private final RecipeSearchEligibilityMapper mapper;

    public RecipeSearchEligibilityServiceImpl(RecipeSearchEligibilityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecipeSearchEligibility> eligibleRecipes(RecipeEligibilityFilter filter) {
        return mapper.findEligible(filter);
    }
}
