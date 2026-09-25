package com.cooker.recipe.service;

import com.cooker.recipe.dto.RecipeFileImportOutcome;
import com.cooker.recipe.dto.RecipeImportFileCommand;

/** 每个来源文件的独立事务边界。 */
public interface RecipeImportFileService {
    RecipeFileImportOutcome importOne(RecipeImportFileCommand command);
}
