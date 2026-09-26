package com.cooker.retrieval.service;

import com.cooker.retrieval.dto.RecipeIndexReport;

/** 显式触发的检索投影全量重建能力。 */
public interface RecipeIndexService {
    RecipeIndexReport rebuildAll();
}
