package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.ParsedRecipe;
import com.cooker.recipe.dto.RecipeFileImportOutcome;
import com.cooker.recipe.dto.RecipeImportFileCommand;
import com.cooker.recipe.dto.RecipeImportReport;
import com.cooker.recipe.entity.RecipeImportBatchEntity;
import com.cooker.recipe.mapper.RecipeImportBatchMapper;
import com.cooker.recipe.parser.HowToCookMarkdownParser;
import com.cooker.recipe.service.RecipeImportFileService;
import com.cooker.recipe.service.RecipeImportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/** HowToCook 固定快照的批次编排；不包含任何网络下载或上游同步逻辑。 */
@Service
public class RecipeImportServiceImpl implements RecipeImportService {
    private final RecipeImportBatchMapper batchMapper;
    private final RecipeImportFileService fileService;
    private final HowToCookMarkdownParser parser;

    public RecipeImportServiceImpl(RecipeImportBatchMapper batchMapper, RecipeImportFileService fileService,
            HowToCookMarkdownParser parser) {
        this.batchMapper = batchMapper;
        this.fileService = fileService;
        this.parser = parser;
    }

    @Override
    public RecipeImportReport importHowToCookSnapshot(Path datasetPath, String sourceVersion) {
        validateDatasetPath(datasetPath);
        List<Path> candidates = scanCandidates(datasetPath);
        RecipeImportBatchEntity batch = new RecipeImportBatchEntity();
        batch.sourceType = "SYSTEM_HOWTOCOOK";
        batch.sourceVersion = sourceVersion;
        batch.status = "RUNNING";
        batch.candidateCount = candidates.size();
        batch.succeededCount = 0;
        batch.skippedCount = 0;
        batch.warningCount = 0;
        batch.failedCount = 0;
        batchMapper.insert(batch);

        int succeeded = 0;
        int skipped = 0;
        int warnings = 0;
        int failed = 0;
        List<String> failedPaths = new ArrayList<>();
        for (Path file : candidates) {
            String relativePath = datasetPath.relativize(file).toString().replace('\\', '/');
            try {
                // 文件 I/O 与解析均在单文件事务之外，数据库连接不会被慢磁盘占用。
                String rawMarkdown = Files.readString(file, StandardCharsets.UTF_8);
                ParsedRecipe parsed = parser.parse(rawMarkdown, relativePath);
                RecipeFileImportOutcome outcome = fileService.importOne(new RecipeImportFileCommand(
                        batch.id, sourceVersion, relativePath, rawMarkdown, sha256(rawMarkdown), parsed));
                warnings += outcome.warningCount();
                switch (outcome.status()) {
                    case SUCCEEDED -> succeeded++;
                    case SKIPPED -> skipped++;
                    case FAILED -> {
                        failed++;
                        failedPaths.add(relativePath + "：" + outcome.message());
                    }
                }
            } catch (Exception ex) {
                // 事务异常只影响当前文件；不要把原文或数据库配置写进诊断信息。
                failed++;
                failedPaths.add(relativePath + "：" + conciseMessage(ex));
            }
        }
        batch.status = failed == 0 ? "COMPLETED" : "COMPLETED_WITH_FAILURES";
        batch.succeededCount = succeeded;
        batch.skippedCount = skipped;
        batch.warningCount = warnings;
        batch.failedCount = failed;
        batch.finishedAt = OffsetDateTime.now();
        batchMapper.updateById(batch);
        return new RecipeImportReport(batch.id, candidates.size(), succeeded, skipped, warnings, failed,
                List.copyOf(failedPaths));
    }

    List<Path> scanCandidates(Path datasetPath) {
        try (var paths = Files.walk(datasetPath)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    // template 是路径分段语义，而非依赖一个固定文件名或数量。
                    .filter(path -> !containsTemplateSegment(datasetPath.relativize(path)))
                    .sorted(Comparator.comparing(path -> datasetPath.relativize(path).toString()))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("无法扫描菜谱数据目录", ex);
        }
    }

    private boolean containsTemplateSegment(Path relativePath) {
        for (Path segment : relativePath) {
            if ("template".equalsIgnoreCase(segment.toString())) return true;
        }
        return false;
    }

    private void validateDatasetPath(Path datasetPath) {
        if (datasetPath == null || !Files.isDirectory(datasetPath)) {
            throw new IllegalArgumentException("菜谱数据目录不存在或不是目录");
        }
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    private String conciseMessage(Exception ex) {
        return ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : "：" + ex.getMessage());
    }
}
