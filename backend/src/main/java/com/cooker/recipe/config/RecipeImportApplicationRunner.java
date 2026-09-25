package com.cooker.recipe.config;

import com.cooker.recipe.dto.RecipeImportReport;
import com.cooker.recipe.service.RecipeImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 唯一的系统种子入口：同时要求 recipe-import profile 与 enabled 开关，
 * 因而常规 Spring Boot 启动只能运行迁移，绝不会读取本地菜谱快照。
 */
@Component
@Profile("recipe-import")
@ConditionalOnProperty(prefix = "cooker.recipe-import", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RecipeImportProperties.class)
public class RecipeImportApplicationRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RecipeImportApplicationRunner.class);
    private final RecipeImportProperties properties;
    private final RecipeImportService importService;

    public RecipeImportApplicationRunner(RecipeImportProperties properties, RecipeImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getDatasetPath() == null) {
            throw new IllegalArgumentException("必须显式提供 cooker.recipe-import.dataset-path");
        }
        RecipeImportReport report = importService.importHowToCookSnapshot(properties.getDatasetPath(), properties.getSourceVersion());
        LOG.info("HowToCook 导入完成：batchId={}, 候选={}, 成功={}, 跳过={}, warning={}, 失败={}",
                report.batchId(), report.candidateCount(), report.succeededCount(), report.skippedCount(),
                report.warningCount(), report.failedCount());
        report.failedPaths().forEach(path -> LOG.warn("HowToCook 导入失败：{}", path));
    }
}
