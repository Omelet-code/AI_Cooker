package com.cooker.retrieval.service;

import com.cooker.retrieval.dto.RecipeSearchCriteria;
import com.cooker.retrieval.dto.RecipeSearchResult;
import java.util.List;

public interface RecipeSearchService {
    List<RecipeSearchResult> search(RecipeSearchCriteria criteria);
}
