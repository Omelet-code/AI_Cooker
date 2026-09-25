package com.cooker.recipe.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cooker.recipe.dto.RecipeFileImportOutcome;
import com.cooker.recipe.dto.RecipeImportReport;
import com.cooker.recipe.entity.RecipeImportBatchEntity;
import com.cooker.recipe.mapper.RecipeImportBatchMapper;
import com.cooker.recipe.parser.HowToCookMarkdownParser;
import com.cooker.recipe.service.RecipeImportFileService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecipeImportServiceImplTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldExcludeTemplateByPathAndContinueAfterSingleFileFailure() throws IOException {
        write("aquatic/good.md", recipe("可导入菜"));
        write("aquatic/broken.md", "# 缺步骤菜的做法\n\n## 操作\n无编号步骤");
        write("template/example/ignored.md", recipe("模板菜"));

        RecipeImportBatchMapper batchMapper = mock(RecipeImportBatchMapper.class);
        when(batchMapper.insert(any(RecipeImportBatchEntity.class))).thenAnswer(invocation -> {
            ((RecipeImportBatchEntity) invocation.getArgument(0)).id = 42L;
            return 1;
        });
        RecipeImportFileService fileService = mock(RecipeImportFileService.class);
        when(fileService.importOne(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0, com.cooker.recipe.dto.RecipeImportFileCommand.class);
            return command.parsedRecipe().successful()
                    ? new RecipeFileImportOutcome(RecipeFileImportOutcome.Status.SUCCEEDED, 2, "成功")
                    : new RecipeFileImportOutcome(RecipeFileImportOutcome.Status.FAILED, 1, "解析失败");
        });
        RecipeImportServiceImpl service = new RecipeImportServiceImpl(batchMapper, fileService,
                new HowToCookMarkdownParser());

        RecipeImportReport report = service.importHowToCookSnapshot(temporaryDirectory, "test-snapshot");

        assertEquals(2, report.candidateCount());
        assertEquals(1, report.succeededCount());
        assertEquals(1, report.failedCount());
        assertEquals(3, report.warningCount());
        assertFalse(report.failedPaths().isEmpty());
        verify(fileService, times(2)).importOne(any());
        verify(batchMapper).updateById(any(RecipeImportBatchEntity.class));
    }

    @Test
    void shouldExcludeEveryNestedTemplateDirectory() throws IOException {
        write("template/first.md", recipe("忽略一"));
        write("category/template/second.md", recipe("忽略二"));
        write("category/kept.md", recipe("保留"));
        RecipeImportServiceImpl service = new RecipeImportServiceImpl(null, null, new HowToCookMarkdownParser());
        assertEquals(1, service.scanCandidates(temporaryDirectory).size());
    }

    private void write(String relative, String content) throws IOException {
        Path path = temporaryDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String recipe(String name) {
        return "# " + name + "的做法\n\n## 计算\n- 米 100g\n\n## 操作\n1. 煮熟\n";
    }
}
