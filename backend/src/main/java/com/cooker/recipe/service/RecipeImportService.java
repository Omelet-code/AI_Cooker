package com.cooker.recipe.service;

import com.cooker.recipe.dto.RecipeImportReport;
import java.nio.file.Path;

/** 系统 HowToCook 固定快照的一次性导入能力。 */
public interface RecipeImportService {
    RecipeImportReport importHowToCookSnapshot(Path datasetPath, String sourceVersion);
}
