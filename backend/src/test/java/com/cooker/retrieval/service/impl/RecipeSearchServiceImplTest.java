package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cooker.recipe.dto.RecipeSearchEligibility;
import com.cooker.recipe.dto.RecipeEligibilityFilter;
import com.cooker.recipe.service.RecipeSearchEligibilityService;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchCandidate;
import com.cooker.retrieval.dto.RecipeSearchCriteria;
import com.cooker.retrieval.mapper.RecipeSearchMapper;
import com.cooker.retrieval.service.QueryEmbeddingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecipeSearchServiceImplTest {
    @Mock RecipeSearchEligibilityService eligibility;
    @Mock RecipeSearchMapper mapper;
    @Mock QueryEmbeddingService embedding;

    @Test
    void 两路排名按RRF融合且同分按词法优先级排序() {
        var facts = Map.of(1L, fact(1L), 2L, fact(2L), 3L, fact(3L));
        var result = RecipeSearchServiceImpl.fuse(
                List.of(candidate(1L, 0), candidate(2L, 2)),
                List.of(candidate(2L, null), candidate(3L, null)), facts, 10);
        assertThat(result).extracting(item -> item.recipeId()).containsExactly(2L, 1L, 3L);
        assertThat(result.getFirst().rrfScore()).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(result.get(1).matchedReasonCodes()).containsExactly("TITLE_EXACT");
        assertThat(result.get(2).matchedReasonCodes()).containsExactly("SEMANTIC");
    }

    @Test
    void 无查询向量时只返回词法结果并保留原料解释() {
        var fact = new RecipeSearchEligibility(1L, "番茄炒蛋", 11L, List.of("鸡蛋"));
        when(eligibility.eligibleRecipes(any())).thenReturn(List.of(fact));
        when(mapper.lexical(any(), eq("鸡蛋"), eq(true), any(), eq(1024), eq(1), any()))
                .thenReturn(List.of(candidate(1L, 1)));
        var result = service().search(new RecipeSearchCriteria(" 鸡蛋 ", null, List.of(), null,
                false, List.of(), List.of(), null));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().matchedIngredients()).containsExactly("鸡蛋");
        assertThat(result.getFirst().matchedReasonCodes()).containsExactly("INGREDIENT_EXACT");
        verify(mapper, never()).vector(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void 查询向量维度错误时降级() {
        when(eligibility.eligibleRecipes(any())).thenReturn(List.of(fact(1L)));
        when(mapper.lexical(any(), any(), anyBoolean(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(candidate(1L, 0)));
        when(embedding.embed(any())).thenReturn(new float[2]);
        assertThat(service().search(new RecipeSearchCriteria("番茄炒蛋", 10, List.of(), null,
                false, List.of(), List.of(), null))).hasSize(1);
        verify(mapper, never()).vector(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void 非Provider程序错误不能被静默降级() {
        when(eligibility.eligibleRecipes(any())).thenReturn(List.of(fact(1L)));
        when(embedding.embed(any())).thenThrow(new IllegalStateException("测试用编程错误"));
        assertThatThrownBy(() -> service().search(new RecipeSearchCriteria("番茄", 10, List.of(), null,
                false, null, List.of(), null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 未指定厨具条件保留null语义() {
        when(eligibility.eligibleRecipes(any())).thenReturn(List.of());
        service().search(new RecipeSearchCriteria("番茄", 10, List.of(), null,
                true, null, List.of(), null));
        ArgumentCaptor<RecipeEligibilityFilter> captured = ArgumentCaptor.forClass(RecipeEligibilityFilter.class);
        verify(eligibility).eligibleRecipes(captured.capture());
        assertThat(captured.getValue().availableToolNames()).isNull();
    }

    @Test
    void 明确无可用厨具保留空集合语义() {
        when(eligibility.eligibleRecipes(any())).thenReturn(List.of());
        service().search(new RecipeSearchCriteria("番茄", 10, List.of(), null,
                true, List.of(), List.of(), null));
        ArgumentCaptor<RecipeEligibilityFilter> captured = ArgumentCaptor.forClass(RecipeEligibilityFilter.class);
        verify(eligibility).eligibleRecipes(captured.capture());
        assertThat(captured.getValue().availableToolNames()).isEmpty();
    }

    private RecipeSearchServiceImpl service() {
        return new RecipeSearchServiceImpl(eligibility, mapper, embedding, new RetrievalIndexProperties());
    }
    private static RecipeSearchEligibility fact(Long id) {
        return new RecipeSearchEligibility(id, "菜" + id, 10L + id, List.of());
    }
    private static RecipeSearchCandidate candidate(Long id, Integer tier) {
        return new RecipeSearchCandidate(id, tier, tier == null ? null : 1.0, tier == null ? 0.1 : null);
    }
}
