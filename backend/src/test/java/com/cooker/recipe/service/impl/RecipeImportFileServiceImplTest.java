package com.cooker.recipe.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cooker.recipe.dto.ParsedRecipe;
import com.cooker.recipe.dto.RecipeFileImportOutcome;
import com.cooker.recipe.dto.RecipeImportFileCommand;
import com.cooker.recipe.entity.RecipeSourceDocumentEntity;
import com.cooker.recipe.entity.RecipeSourceEntity;
import com.cooker.recipe.mapper.CookingToolMapper;
import com.cooker.recipe.mapper.IngredientAliasMapper;
import com.cooker.recipe.mapper.IngredientMapper;
import com.cooker.recipe.mapper.RecipeIngredientMapper;
import com.cooker.recipe.mapper.RecipeMapper;
import com.cooker.recipe.mapper.RecipeSourceDocumentMapper;
import com.cooker.recipe.mapper.RecipeSourceMapper;
import com.cooker.recipe.mapper.RecipeStepMapper;
import com.cooker.recipe.mapper.RecipeToolMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeImportFileServiceImplTest {
    @Test
    void shouldSkipCompletedSnapshotWithoutDuplicatingFacts() {
        RecipeSourceMapper sourceMapper = mock(RecipeSourceMapper.class);
        RecipeSourceDocumentMapper documentMapper = mock(RecipeSourceDocumentMapper.class);
        RecipeMapper recipeMapper = mock(RecipeMapper.class);
        RecipeSourceEntity source = new RecipeSourceEntity();
        source.id = 7L;
        when(sourceMapper.findSystemSource(any(), any())).thenReturn(source);
        RecipeSourceDocumentEntity document = new RecipeSourceDocumentEntity();
        document.parseStatus = "SUCCESS";
        when(documentMapper.findBySourceAndVersion(7L, "snapshot")).thenReturn(document);

        RecipeImportFileServiceImpl service = service(sourceMapper, documentMapper, recipeMapper);
        RecipeFileImportOutcome outcome = service.importOne(command(successfulParsedRecipe()));

        assertEquals(RecipeFileImportOutcome.Status.SKIPPED, outcome.status());
        verify(recipeMapper, never()).insert(any(com.cooker.recipe.entity.RecipeEntity.class));
    }

    @Test
    void shouldPersistFailedRawDocumentWithoutCreatingRecipeFacts() {
        RecipeSourceMapper sourceMapper = mock(RecipeSourceMapper.class);
        RecipeSourceDocumentMapper documentMapper = mock(RecipeSourceDocumentMapper.class);
        RecipeMapper recipeMapper = mock(RecipeMapper.class);
        RecipeSourceEntity source = new RecipeSourceEntity();
        source.id = 8L;
        when(sourceMapper.findSystemSource(any(), any())).thenReturn(source);
        when(documentMapper.findBySourceAndVersion(8L, "snapshot")).thenReturn(null);

        RecipeImportFileServiceImpl service = service(sourceMapper, documentMapper, recipeMapper);
        RecipeFileImportOutcome outcome = service.importOne(command(ParsedRecipe.failed("home", "缺少操作", List.of("缺少操作"))));

        assertEquals(RecipeFileImportOutcome.Status.FAILED, outcome.status());
        verify(documentMapper).insert(any(RecipeSourceDocumentEntity.class));
        verify(recipeMapper, never()).insert(any(com.cooker.recipe.entity.RecipeEntity.class));
    }

    private RecipeImportFileServiceImpl service(RecipeSourceMapper sourceMapper,
            RecipeSourceDocumentMapper documentMapper, RecipeMapper recipeMapper) {
        return new RecipeImportFileServiceImpl(sourceMapper, documentMapper, recipeMapper,
                mock(IngredientMapper.class), mock(IngredientAliasMapper.class), mock(RecipeIngredientMapper.class),
                mock(CookingToolMapper.class), mock(RecipeToolMapper.class), mock(RecipeStepMapper.class));
    }

    private RecipeImportFileCommand command(ParsedRecipe parsed) {
        return new RecipeImportFileCommand(1L, "snapshot", "home/test.md", "# 原文", "a".repeat(64), parsed);
    }

    private ParsedRecipe successfulParsedRecipe() {
        return new ParsedRecipe(true, null, "测试菜", "home", null, null, null, null, null, null,
                List.of(), List.of(), List.of("完成"), List.of());
    }
}
