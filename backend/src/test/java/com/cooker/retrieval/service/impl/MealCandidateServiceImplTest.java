package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.cooker.common.ApiException;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.retrieval.dto.*;
import com.cooker.retrieval.dto.MealCandidateRequest.*;
import com.cooker.retrieval.service.RecipeSearchService;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MealCandidateServiceImplTest {
    final RecipeMealFactsService facts = mock(RecipeMealFactsService.class);
    final RecipeSearchService search = mock(RecipeSearchService.class);
    final MealCandidateServiceImpl service = new MealCandidateServiceImpl(facts, search);

    @ParameterizedTest @MethodSource("invalidRequests")
    void 非法输入先失败不触发检索或模型(MealCandidateRequest request) {
        assertThatThrownBy(() -> service.candidates(request)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(facts, search);
    }
    static Stream<MealCandidateRequest> invalidRequests() {
        var dish = List.of(new RoleRequest(MealRole.DISH, 1));
        var empty = new RecipeEligibilityFilter(null, null, null, true, null, null, 1L);
        var semantic = new RecipeEligibilityFilter("菜", null, null, true, null, null, 1L);
        return Stream.of(null, new MealCandidateRequest(0, Mode.RANDOM, empty, dish, 1L),
                new MealCandidateRequest(1, null, empty, dish, 1L),
                new MealCandidateRequest(1, Mode.RANDOM, null, dish, 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, null, 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, List.of(), 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, dish, null),
                new MealCandidateRequest(1, Mode.RANDOM, semantic, dish, 1L),
                new MealCandidateRequest(1, Mode.SEMANTIC, empty, dish, null),
                new MealCandidateRequest(1, Mode.SEMANTIC, new RecipeEligibilityFilter(" ", null, null, true, null, null, 1L), dish, null),
                new MealCandidateRequest(1, Mode.SEMANTIC, new RecipeEligibilityFilter("😀".repeat(1001), null, null, true, null, null, 1L), dish, null),
                new MealCandidateRequest(1, Mode.RANDOM, empty, List.of(new RoleRequest(MealRole.OTHER, 1)), 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, List.of(new RoleRequest(MealRole.DISH, 0)), 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, List.of(new RoleRequest(MealRole.DISH, 21)), 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, List.of(new RoleRequest(MealRole.DISH, 1), new RoleRequest(MealRole.DISH, 2)), 1L),
                new MealCandidateRequest(1, Mode.RANDOM, empty, Arrays.asList((RoleRequest)null), 1L),
                new MealCandidateRequest(1, Mode.RANDOM, new RecipeEligibilityFilter(null, null, -1, true, null, null, 1L), dish, 1L),
                new MealCandidateRequest(1, Mode.RANDOM, new RecipeEligibilityFilter(null, null, null, true, null, null, 2L), dish, 1L),
                new MealCandidateRequest(1, Mode.RANDOM, new RecipeEligibilityFilter(null, List.of(""), null, true, null, null, 1L), dish, 1L));
    }

    @Test void 语义一个角色一次去重保留排名且空分类交集零检索() {
        when(facts.categoriesFor(MealRole.DISH)).thenReturn(List.of("meat_dish", "aquatic"));
        var first = fact(10, MealRole.DISH);
        var second = fact(11, MealRole.DISH);
        var ranks = List.of(rank(11, 1), rank(11, 2), rank(10, 3));
        when(search.search(any())).thenReturn(ranks);
        when(facts.readFacts(1, List.of(11L, 10L))).thenReturn(List.of(second, first));
        when(facts.recheck(any(), any())).thenAnswer(call -> call.getArgument(1));
        var filter = new RecipeEligibilityFilter("😀".repeat(1000), List.of("meat_dish"), 20, false, List.of(), List.of("盐"), 1L);
        var request = new MealCandidateRequest(1, Mode.SEMANTIC, filter, List.of(new RoleRequest(MealRole.DISH, 3)), null);
        var result = service.candidates(request);
        assertThat(result.groups().getFirst().candidates()).extracting(c -> c.facts().recipeId()).containsExactly(11L, 10L);
        assertThat(result.groups().getFirst().candidates().getLast().searchRank().rank()).isEqualTo(3);
        verify(search).search(new RecipeSearchCriteria(filter.query(), 3, List.of("meat_dish"), 20, false, List.of(), List.of("盐"), 1L));
        assertThat(result.groups().getFirst().shortage()).isTrue();
        clearInvocations(search);
        var unmatched = new RecipeEligibilityFilter("菜", List.of("soup"), null, true, null, null, 1L);
        assertThat(service.candidates(new MealCandidateRequest(1, Mode.SEMANTIC, unmatched,
                request.roles(), null)).groups().getFirst().candidates()).isEmpty();
        verifyNoInteractions(search);
    }
    @Test void 随机资格池重排仍稳定且使用两组分类并集而非各自一千() {
        when(facts.categoriesFor(MealRole.DISH)).thenReturn(List.of("meat_dish"));
        when(facts.categoriesFor(MealRole.SOUP)).thenReturn(List.of("soup"));
        when(facts.eligiblePool(any(), eq(1000))).thenReturn(List.of(fact(12, MealRole.DISH), fact(10, MealRole.DISH), fact(20, MealRole.SOUP)));
        when(facts.recheck(any(), any())).thenAnswer(call -> call.getArgument(1));
        var filter = new RecipeEligibilityFilter("", null, null, true, null, null, 1L);
        var request = new MealCandidateRequest(1, Mode.RANDOM, filter, List.of(new RoleRequest(MealRole.DISH, 1), new RoleRequest(MealRole.SOUP, 1)), 2L);
        var first = service.candidates(request);
        when(facts.eligiblePool(any(), eq(1000))).thenReturn(List.of(fact(20, MealRole.SOUP), fact(10, MealRole.DISH), fact(12, MealRole.DISH)));
        assertThat(service.candidates(request)).isEqualTo(first);
        verify(facts, times(2)).eligiblePool(new RecipeEligibilityFilter("", List.of("meat_dish", "soup"), null, true, null, null, 1L), 1000);
        verifyNoInteractions(search);
    }
    static MealRecipeFacts fact(long id, MealRole role) {
        return new MealRecipeFacts(id, "菜", role == MealRole.DISH ? "meat_dish" : "soup",
                new MealRecipeFacts.RoleEvidence(role, "分类", "meal-role-v1"), id, "a".repeat(64), 2, 10,
                List.of(), List.of(), List.of(), null, "fingerprint" + id);
    }
    static RecipeSearchResult rank(long id, int rank) { return new RecipeSearchResult(id, rank, rank, null, 0.01, List.of(), List.of()); }
}
