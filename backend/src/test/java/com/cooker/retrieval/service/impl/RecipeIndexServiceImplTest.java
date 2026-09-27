package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.recipe.service.RecipeIndexProjectionService;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeIndexReport;
import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import com.cooker.retrieval.entity.RecipeSearchDocumentEntity;
import com.cooker.retrieval.mapper.RecipeSearchDocumentMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

/** 用 Mock 替换模型与 Mapper，验证跳过与写入的编排逻辑，不产生任何真实外部调用。 */
@ExtendWith(MockitoExtension.class)
class RecipeIndexServiceImplTest {
    @Mock private RecipeIndexProjectionService projectionService;
    @Mock private RecipeSearchDocumentMapper documentMapper;
    @Mock private EmbeddingModel embeddingModel;

    /** 没有历史投影时，4 个块都必须调用模型并写入 READY 记录。 */
    @Test
    void 首次构建会为每个块写入向量() {
        RetrievalIndexProperties properties = properties();
        RecipeSearchDocumentBuilder builder = new RecipeSearchDocumentBuilder(properties);
        RecipeIndexProjection projection = RecipeSearchDocumentBuilderTest.projection(List.of("第一步", "第二步", "第三步"));
        when(projectionService.listCurrentRecipes()).thenReturn(List.of(projection));
        when(documentMapper.findByRecipeId(11L)).thenReturn(List.of());
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).stream()
                .map(ignored -> vector()).toList());

        RecipeIndexReport report = service(properties, builder).rebuildAll();

        assertThat(report).isEqualTo(new RecipeIndexReport(1, 4, 4, 0, 0));
        verify(documentMapper, org.mockito.Mockito.times(4)).upsertReady(any(), anyString());
    }

    /** 内容哈希、模板版本、模型与维度全部一致时，不得再次调用收费接口。 */
    @Test
    void 内容和版本均未变化时不重复调用模型() {
        RetrievalIndexProperties properties = properties();
        RecipeSearchDocumentBuilder builder = new RecipeSearchDocumentBuilder(properties);
        RecipeIndexProjection projection = RecipeSearchDocumentBuilderTest.projection(List.of("第一步", "第二步", "第三步"));
        List<RecipeSearchDocumentEntity> current = builder.build(projection).stream()
                .map(chunk -> readyDocument(chunk, properties)).toList();
        when(projectionService.listCurrentRecipes()).thenReturn(List.of(projection));
        when(documentMapper.findByRecipeId(11L)).thenReturn(current);

        RecipeIndexReport report = service(properties, builder).rebuildAll();

        assertThat(report).isEqualTo(new RecipeIndexReport(1, 4, 0, 4, 0));
        verify(embeddingModel, never()).embed(anyList());
        verify(documentMapper, never()).upsertReady(any(), anyString());
    }

    /** 手工装配被测服务，避免启动 Spring 上下文。 */
    private RecipeIndexServiceImpl service(RetrievalIndexProperties properties, RecipeSearchDocumentBuilder builder) {
        return new RecipeIndexServiceImpl(projectionService, builder, documentMapper, embeddingModel, properties);
    }

    /** 与运行配置对齐的最小属性集合。 */
    private RetrievalIndexProperties properties() {
        RetrievalIndexProperties properties = new RetrievalIndexProperties();
        properties.setBatchSize(10);
        properties.setMaxStepChunkSteps(3);
        properties.setMaxChunkCharacters(4000);
        properties.setDocumentTemplateVersion(1);
        properties.setEmbeddingModel("text-embedding-v4");
        properties.setEmbeddingDimension(1024);
        return properties;
    }

    /** 构造一条已是最新的投影记录，用于验证跳过逻辑。 */
    private RecipeSearchDocumentEntity readyDocument(RecipeSearchDocumentChunk chunk, RetrievalIndexProperties properties) {
        RecipeSearchDocumentEntity entity = new RecipeSearchDocumentEntity();
        entity.chunkType = chunk.chunkType();
        entity.chunkNo = chunk.chunkNo();
        entity.sourceDocumentId = chunk.sourceDocumentId();
        entity.sourceType = chunk.sourceType();
        entity.ownerUserId = chunk.ownerUserId();
        entity.visibility = chunk.visibility();
        entity.contentHash = chunk.contentHash();
        entity.documentTemplateVersion = properties.getDocumentTemplateVersion();
        entity.embeddingModel = properties.getEmbeddingModel();
        entity.embeddingDimension = properties.getEmbeddingDimension();
        entity.embeddingStatus = "READY";
        return entity;
    }

    @Test
    void 权限元数据改变只刷新记录不重复调用模型() {
        RetrievalIndexProperties properties = properties();
        RecipeSearchDocumentBuilder builder = new RecipeSearchDocumentBuilder(properties);
        RecipeIndexProjection original = RecipeSearchDocumentBuilderTest.projection(List.of("第一步", "第二步", "第三步"));
        RecipeIndexProjection changed = new RecipeIndexProjection(original.recipeId(), original.sourceDocumentId() + 1,
                "USER_CUSTOM", 42L, "PRIVATE", original.name(), original.categoryCode(), original.summary(),
                original.difficulty(), original.estimatedMinutes(), original.servings(), original.caloriesKcal(),
                original.ingredientNames(), original.toolNames(), original.steps());
        when(projectionService.listCurrentRecipes()).thenReturn(List.of(changed));
        when(documentMapper.findByRecipeId(11L)).thenReturn(builder.build(original).stream()
                .map(chunk -> readyDocument(chunk, properties)).toList());
        service(properties, builder).rebuildAll();
        verify(documentMapper, org.mockito.Mockito.times(4)).refreshMetadata(any());
        verify(embeddingModel, never()).embed(anyList());
    }

    @Test
    void 私有菜谱所有者变化也只刷新元数据() {
        RetrievalIndexProperties properties = properties();
        RecipeSearchDocumentBuilder builder = new RecipeSearchDocumentBuilder(properties);
        RecipeIndexProjection base = RecipeSearchDocumentBuilderTest.projection(List.of("第一步", "第二步", "第三步"));
        RecipeIndexProjection original = new RecipeIndexProjection(base.recipeId(), base.sourceDocumentId(),
                "USER_CUSTOM", 42L, "PRIVATE", base.name(), base.categoryCode(), base.summary(),
                base.difficulty(), base.estimatedMinutes(), base.servings(), base.caloriesKcal(),
                base.ingredientNames(), base.toolNames(), base.steps());
        RecipeIndexProjection changed = new RecipeIndexProjection(base.recipeId(), base.sourceDocumentId(),
                "USER_CUSTOM", 43L, "PRIVATE", base.name(), base.categoryCode(), base.summary(),
                base.difficulty(), base.estimatedMinutes(), base.servings(), base.caloriesKcal(),
                base.ingredientNames(), base.toolNames(), base.steps());
        when(projectionService.listCurrentRecipes()).thenReturn(List.of(changed));
        when(documentMapper.findByRecipeId(11L)).thenReturn(builder.build(original).stream()
                .map(chunk -> readyDocument(chunk, properties)).toList());
        service(properties, builder).rebuildAll();
        verify(documentMapper, org.mockito.Mockito.times(4)).refreshMetadata(any());
        verify(embeddingModel, never()).embed(anyList());
    }

    /** 维度必须与配置一致，否则会被判定为 Provider 返回异常。 */
    private float[] vector() {
        return new float[1024];
    }
}
