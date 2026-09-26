package com.cooker.retrieval.config;

import com.cooker.retrieval.dto.RecipeIndexReport;
import com.cooker.retrieval.service.RecipeIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 显式全量重建入口；不会在普通应用启动时扫描菜谱或调用外部模型。
 */
@Component
@Profile("recipe-index")
@ConditionalOnProperty(prefix = "cooker.retrieval.index", name = "enabled", havingValue = "true")
public class RecipeIndexApplicationRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RecipeIndexApplicationRunner.class);
    private final RecipeIndexService indexService;

    public RecipeIndexApplicationRunner(RecipeIndexService indexService) {
        this.indexService = indexService;
    }

    /**
     * 启动完成后执行一次全量重建，并打印可用于审计的统计。
     * 重复执行是幂等的：未变化的块跳过，失败块保留可重试状态。
     */
    @Override
    public void run(ApplicationArguments args) {
        RecipeIndexReport report = indexService.rebuildAll();
        LOG.info("菜谱检索投影完成：菜谱={}, 块={}, 新建或更新={}, 跳过={}, 失败={}",
                report.recipeCount(), report.totalChunks(), report.indexedChunks(), report.skippedChunks(),
                report.failedChunks());
    }
}
