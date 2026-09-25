package com.cooker.recipe.dto;

/** 已在事务外读入和解析的单文件导入命令。 */
public record RecipeImportFileCommand(
        Long batchId,
        String sourceVersion,
        String relativePath,
        String rawMarkdown,
        String contentHash,
        ParsedRecipe parsedRecipe) {
}
