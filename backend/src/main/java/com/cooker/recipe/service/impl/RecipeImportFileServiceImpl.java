package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.ParsedIngredient;
import com.cooker.recipe.dto.RecipeFileImportOutcome;
import com.cooker.recipe.dto.RecipeImportFileCommand;
import com.cooker.recipe.entity.CookingToolEntity;
import com.cooker.recipe.entity.IngredientAliasEntity;
import com.cooker.recipe.entity.IngredientEntity;
import com.cooker.recipe.entity.RecipeEntity;
import com.cooker.recipe.entity.RecipeIngredientEntity;
import com.cooker.recipe.entity.RecipeSourceDocumentEntity;
import com.cooker.recipe.entity.RecipeSourceEntity;
import com.cooker.recipe.entity.RecipeStepEntity;
import com.cooker.recipe.entity.RecipeToolEntity;
import com.cooker.recipe.mapper.CookingToolMapper;
import com.cooker.recipe.mapper.IngredientAliasMapper;
import com.cooker.recipe.mapper.IngredientMapper;
import com.cooker.recipe.mapper.RecipeIngredientMapper;
import com.cooker.recipe.mapper.RecipeMapper;
import com.cooker.recipe.mapper.RecipeSourceDocumentMapper;
import com.cooker.recipe.mapper.RecipeSourceMapper;
import com.cooker.recipe.mapper.RecipeStepMapper;
import com.cooker.recipe.mapper.RecipeToolMapper;
import com.cooker.recipe.service.RecipeImportFileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单文件落库实现。异常向外传播以回滚该文件的所有关联记录，调用方捕获后继续下一文件。
 */
@Service
public class RecipeImportFileServiceImpl implements RecipeImportFileService {
    private static final String SYSTEM_HOWTOCOOK = "SYSTEM_HOWTOCOOK";
    private final RecipeSourceMapper sourceMapper;
    private final RecipeSourceDocumentMapper documentMapper;
    private final RecipeMapper recipeMapper;
    private final IngredientMapper ingredientMapper;
    private final IngredientAliasMapper ingredientAliasMapper;
    private final RecipeIngredientMapper recipeIngredientMapper;
    private final CookingToolMapper cookingToolMapper;
    private final RecipeToolMapper recipeToolMapper;
    private final RecipeStepMapper recipeStepMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecipeImportFileServiceImpl(RecipeSourceMapper sourceMapper, RecipeSourceDocumentMapper documentMapper,
            RecipeMapper recipeMapper, IngredientMapper ingredientMapper, IngredientAliasMapper ingredientAliasMapper,
            RecipeIngredientMapper recipeIngredientMapper, CookingToolMapper cookingToolMapper,
            RecipeToolMapper recipeToolMapper, RecipeStepMapper recipeStepMapper) {
        this.sourceMapper = sourceMapper;
        this.documentMapper = documentMapper;
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.ingredientAliasMapper = ingredientAliasMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.cookingToolMapper = cookingToolMapper;
        this.recipeToolMapper = recipeToolMapper;
        this.recipeStepMapper = recipeStepMapper;
    }

    @Override
    @Transactional
    public RecipeFileImportOutcome importOne(RecipeImportFileCommand command) {
        RecipeSourceEntity source = sourceMapper.findSystemSource(SYSTEM_HOWTOCOOK, command.relativePath());
        if (source == null) {
            source = new RecipeSourceEntity();
            source.sourceType = SYSTEM_HOWTOCOOK;
            source.sourceKey = command.relativePath();
            source.sourceUri = command.relativePath();
            source.visibility = "PUBLIC";
            sourceMapper.insert(source);
        }

        RecipeSourceDocumentEntity document = documentMapper.findBySourceAndVersion(source.id, command.sourceVersion());
        if (document != null && "SUCCESS".equals(document.parseStatus)) {
            // 固定快照已完成后不追踪上游变化；同路径同版本再次运行只作为幂等跳过。
            return new RecipeFileImportOutcome(RecipeFileImportOutcome.Status.SKIPPED, 0, "已导入的固定快照");
        }

        List<String> warnings = new ArrayList<>(command.parsedRecipe().warnings());
        if (document == null) {
            document = new RecipeSourceDocumentEntity();
            document.sourceId = source.id;
            document.sourceVersion = command.sourceVersion();
        }
        document.contentHash = command.contentHash();
        document.rawMarkdown = command.rawMarkdown();
        document.importBatchId = command.batchId();
        document.parseWarnings = toJson(warnings);

        if (!command.parsedRecipe().successful()) {
            document.parseStatus = "FAILED";
            document.failureMessage = command.parsedRecipe().failureMessage();
            persistDocument(document);
            return new RecipeFileImportOutcome(RecipeFileImportOutcome.Status.FAILED,
                    warnings.size(), command.parsedRecipe().failureMessage());
        }

        document.parseStatus = "SUCCESS";
        document.failureMessage = null;
        persistDocument(document);
        RecipeEntity recipe = recipeMapper.findBySourceId(source.id);
        if (recipe == null) {
            recipe = new RecipeEntity();
            recipe.sourceId = source.id;
            recipe.sourceDocumentId = document.id;
            applyRecipeFields(recipe, command);
            recipeMapper.insert(recipe);
        } else {
            // 只用于前次失败后的恢复，先删除子表防止旧原料、工具或步骤残留。
            recipeIngredientMapper.deleteByRecipeId(recipe.id);
            recipeToolMapper.deleteByRecipeId(recipe.id);
            recipeStepMapper.deleteByRecipeId(recipe.id);
            recipe.sourceDocumentId = document.id;
            applyRecipeFields(recipe, command);
            recipeMapper.updateById(recipe);
        }
        persistFacts(recipe.id, command, warnings);
        // 原料标准化同样可能产生诊断，因此事实写入后再覆盖同一原文版本的 warning。
        document.parseWarnings = toJson(warnings);
        documentMapper.updateById(document);
        return new RecipeFileImportOutcome(RecipeFileImportOutcome.Status.SUCCEEDED,
                warnings.size(), "导入成功");
    }

    private void persistDocument(RecipeSourceDocumentEntity document) {
        if (document.id == null) documentMapper.insert(document);
        else documentMapper.updateById(document);
    }

    private void applyRecipeFields(RecipeEntity target, RecipeImportFileCommand command) {
        var parsed = command.parsedRecipe();
        target.name = parsed.name();
        target.categoryCode = parsed.categoryCode();
        target.summary = parsed.summary();
        target.difficulty = parsed.difficulty();
        target.estimatedMinutes = parsed.estimatedMinutes();
        target.servings = parsed.servings();
        target.caloriesKcal = parsed.caloriesKcal();
        target.additionalContent = parsed.additionalContent();
    }

    private void persistFacts(Long recipeId, RecipeImportFileCommand command, List<String> warnings) {
        for (ParsedIngredient parsedIngredient : command.parsedRecipe().ingredients()) {
            IngredientEntity ingredient = findOrCreateIngredient(parsedIngredient.rawName(), warnings);
            RecipeIngredientEntity row = new RecipeIngredientEntity();
            row.recipeId = recipeId;
            row.ingredientId = ingredient.id;
            row.rawName = parsedIngredient.rawName();
            row.amountMin = parsedIngredient.amountMin();
            row.amountMax = parsedIngredient.amountMax();
            row.unitCode = parsedIngredient.unitCode();
            row.unitLabel = parsedIngredient.unitLabel();
            row.optional = parsedIngredient.optional();
            row.rawLine = parsedIngredient.rawLine();
            row.sortOrder = parsedIngredient.sortOrder();
            recipeIngredientMapper.insert(row);
        }
        for (String toolName : command.parsedRecipe().tools()) {
            CookingToolEntity tool = cookingToolMapper.findByCanonicalName(toolName);
            if (tool == null) {
                tool = new CookingToolEntity();
                tool.canonicalName = toolName;
                cookingToolMapper.insert(tool);
            }
            RecipeToolEntity row = new RecipeToolEntity();
            row.recipeId = recipeId;
            row.toolId = tool.id;
            row.optional = false;
            recipeToolMapper.insert(row);
        }
        int stepNo = 1;
        for (String step : command.parsedRecipe().steps()) {
            RecipeStepEntity row = new RecipeStepEntity();
            row.recipeId = recipeId;
            row.stepNo = stepNo++;
            row.content = step;
            recipeStepMapper.insert(row);
        }
    }

    private IngredientEntity findOrCreateIngredient(String rawName, List<String> warnings) {
        IngredientEntity aliased = ingredientAliasMapper.findIngredientByAlias(rawName);
        if (aliased != null) return aliased;
        String canonical = rawName.replaceAll("[（(].*?[）)]", "").replaceAll("\\s+", " ").trim();
        if (canonical.isBlank()) canonical = rawName;
        IngredientEntity ingredient = ingredientMapper.findByCanonicalName(canonical);
        if (ingredient != null) return ingredient;
        ingredient = new IngredientEntity();
        ingredient.canonicalName = canonical;
        ingredient.normalizationStatus = "UNREVIEWED";
        ingredientMapper.insert(ingredient);
        warnings.add("原料未命中别名字典，已按待复核项保存：" + canonical);
        if (!canonical.equals(rawName)) {
            IngredientAliasEntity alias = new IngredientAliasEntity();
            alias.ingredientId = ingredient.id;
            alias.aliasName = rawName;
            ingredientAliasMapper.insert(alias);
        }
        return ingredient;
    }

    private String toJson(List<String> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化解析诊断", ex);
        }
    }
}
