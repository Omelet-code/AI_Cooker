package com.cooker.retrieval.service.impl;

import com.cooker.common.ApiException;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.retrieval.dto.*;
import com.cooker.retrieval.dto.MealCandidateRequest.*;
import com.cooker.retrieval.dto.MealCandidateResult.*;
import com.cooker.retrieval.service.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MealCandidateServiceImpl implements MealCandidateService {
    private final RecipeMealFactsService facts;
    private final RecipeSearchService search;
    public MealCandidateServiceImpl(RecipeMealFactsService facts, RecipeSearchService search) {
        this.facts = facts; this.search = search;
    }

    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MealCandidateResult candidates(MealCandidateRequest request) {
        validate(request);
        facts.verifyViewer(request.viewerUserId());
        var selections = new ArrayList<Selection>();
        List<MealRecipeFacts> pool = List.of();
        if (request.mode() == Mode.RANDOM) {
            var union = request.roles().stream().flatMap(role -> facts.categoriesFor(role.role()).stream()).distinct().sorted().toList();
            var categories = intersect(union, request.filter().categoryCodes());
            if (!categories.isEmpty()) pool = facts.eligiblePool(withCategories(request, categories), 1000);
        }
        for (var role : request.roles()) {
            var categories = intersect(facts.categoriesFor(role.role()), request.filter().categoryCodes());
            var filter = withCategories(request, categories);
            List<Candidate> candidates = List.of();
            if (!categories.isEmpty()) {
                if (request.mode() == Mode.RANDOM) {
                    var group = new ArrayList<>(pool.stream().filter(item -> item.role().role() == role.role())
                            .sorted(Comparator.comparingLong(MealRecipeFacts::recipeId)).toList());
                    // 各角色独立种子；固定ID顺序和事实版本保证同输入复测一致。
                    Collections.shuffle(group, new Random(request.seed() ^ (role.role() == MealRole.DISH
                            ? 0x41D15A7EL : 0x50A17B3EL)));
                    candidates = group.stream().limit(role.candidateCount()).map(item -> candidate(item, null,
                            "完整资格池按角色及种子抽样：" + request.seed())).toList();
                } else {
                    // 每角色一次T5调用，绝不为例外过滤造成的不足再请求Embedding。
                    var ranked = search.search(new RecipeSearchCriteria(filter.query(), role.candidateCount(), categories,
                            filter.maxCookingMinutes(), filter.includeUnknownCookingTime(), filter.availableToolNames(),
                            filter.excludedIngredientNames(), request.viewerUserId()));
                    var distinct = new LinkedHashMap<Long, RecipeSearchResult>();
                    ranked.stream().limit(20).forEach(rank -> distinct.putIfAbsent(rank.recipeId(), rank));
                    if (!distinct.isEmpty()) {
                        var snapshot = facts.readFacts(request.viewerUserId(), List.copyOf(distinct.keySet()));
                        var byId = new HashMap<Long, MealRecipeFacts>();
                        snapshot.forEach(item -> byId.put(item.recipeId(), item));
                        candidates = distinct.values().stream().filter(rank -> byId.containsKey(rank.recipeId())
                                && byId.get(rank.recipeId()).role().role() == role.role())
                                .limit(role.candidateCount()).map(rank -> candidate(byId.get(rank.recipeId()), rank,
                                        "现有T5顺序，未追加检索或改动RRF")).toList();
                    }
                }
            }
            selections.add(new Selection(role, filter, candidates));
        }
        var groups = new ArrayList<Group>();
        // 所有外部等待结束后，再通过菜谱公开Service开启独立短事务复核。
        for (var selection : selections) {
            var current = selection.candidates().isEmpty() ? List.<MealRecipeFacts>of()
                    : facts.recheck(selection.filter(), selection.candidates().stream().map(Candidate::facts).toList());
            var byId = new HashMap<Long, MealRecipeFacts>();
            current.forEach(item -> byId.put(item.recipeId(), item));
            var checked = selection.candidates().stream().filter(old -> byId.containsKey(old.facts().recipeId())
                    && byId.get(old.facts().recipeId()).role().role() == selection.role().role())
                    .map(old -> candidate(byId.get(old.facts().recipeId()), old.searchRank(), old.selectionBasis())).toList();
            groups.add(new Group(selection.role().role(), selection.role().candidateCount(), checked,
                    checked.size() < selection.role().candidateCount()));
        }
        return new MealCandidateResult(request.mode(), request.mode() == Mode.RANDOM ? request.seed() : null, List.copyOf(groups));
    }

    private static Candidate candidate(MealRecipeFacts facts, RecipeSearchResult rank, String basis) {
        return new Candidate(facts, basis, rank, facts.servings() == null || facts.servings() <= 0,
                facts.estimatedMinutes() == null);
    }
    private static List<String> intersect(List<String> roleCategories, List<String> requested) {
        return requested == null || requested.isEmpty() ? roleCategories
                : roleCategories.stream().filter(requested::contains).toList();
    }
    private static RecipeEligibilityFilter withCategories(MealCandidateRequest request, List<String> categories) {
        var filter = request.filter();
        return new RecipeEligibilityFilter(filter.query(), categories, filter.maxCookingMinutes(),
                filter.includeUnknownCookingTime(), filter.availableToolNames(), filter.excludedIngredientNames(), request.viewerUserId());
    }
    private static void validate(MealCandidateRequest request) {
        if (request == null || request.viewerUserId() <= 0 || request.mode() == null || request.filter() == null
                || request.roles() == null || request.roles().isEmpty() || request.roles().size() > 2) throw invalid();
        if (request.roles().stream().anyMatch(role -> role == null || role.role() == null
                || role.role() != MealRole.DISH && role.role() != MealRole.SOUP
                || role.candidateCount() < 1 || role.candidateCount() > 20)
                || request.roles().stream().map(RoleRequest::role).distinct().count() != request.roles().size()) throw invalid();
        var filter = request.filter();
        if (filter.viewerUserId() != null && filter.viewerUserId() != request.viewerUserId()
                || filter.maxCookingMinutes() != null && filter.maxCookingMinutes() < 0) throw invalid();
        for (var list : Arrays.asList(filter.categoryCodes(), filter.availableToolNames(), filter.excludedIngredientNames()))
            if (list != null && list.stream().anyMatch(value -> value == null || value.isBlank())) throw invalid();
        String query = filter.query();
        if (request.mode() == Mode.RANDOM) {
            if (request.seed() == null || query != null && !query.isEmpty()) throw invalid();
        } else if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 1000) throw invalid();
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "组餐候选请求参数不合法");
    }
    private record Selection(RoleRequest role, RecipeEligibilityFilter filter, List<Candidate> candidates) {}
}
