package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 只验证分块规则本身：不连接数据库，也不调用任何模型。 */
class RecipeSearchDocumentBuilderTest {
    /**
     * 预期：overview、ingredients、tools 各一块，6 个步骤按 3 个一组拆成 steps:0 与 steps:1；
     * 每个块都带菜名与分类上下文，步骤既不跨块重复也不丢失。
     */
    @Test
    void 应按结构化字段构建四类块并把步骤拆为连续小组() {
        RetrievalIndexProperties properties = properties();
        properties.setMaxStepChunkSteps(3);
        RecipeSearchDocumentBuilder builder = new RecipeSearchDocumentBuilder(properties);

        List<RecipeSearchDocumentChunk> chunks = builder.build(projection(List.of(
                "切番茄", "打鸡蛋", "热锅", "炒鸡蛋", "加入番茄", "调味")));

        assertThat(chunks).extracting(RecipeSearchDocumentChunk::key)
                .containsExactly("overview:0", "ingredients:0", "tools:0", "steps:0", "steps:1");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).contains("菜名：番茄炒鸡蛋").contains("分类：家常菜");
            assertThat(chunk.contentHash()).hasSize(64);
        });
        assertThat(chunks.get(3).content()).contains("步骤1：切番茄").contains("步骤3：热锅")
                .doesNotContain("步骤4：炒鸡蛋");
        assertThat(chunks.get(4).content()).contains("步骤4：炒鸡蛋").contains("步骤6：调味");
    }

    /** 测试用最小配置，避免启动 Spring 上下文。 */
    private RetrievalIndexProperties properties() {
        RetrievalIndexProperties properties = new RetrievalIndexProperties();
        properties.setMaxStepChunkSteps(4);
        properties.setMaxChunkCharacters(4000);
        return properties;
    }

    /** 构造固定的事实快照，供本类与索引编排测试共用。 */
    static RecipeIndexProjection projection(List<String> steps) {
        return new RecipeIndexProjection(11L, 22L, "SYSTEM_HOWTOCOOK", null, "PUBLIC", "番茄炒鸡蛋", "家常菜",
                "简单快捷", 1, 10, 2, 200, List.of("番茄", "鸡蛋"), List.of("炒锅"), steps);
    }
}
