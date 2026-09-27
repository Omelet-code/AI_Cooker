package com.cooker.retrieval.service.impl;

import com.cooker.recipe.dto.RecipeEligibilityFilter;
import com.cooker.recipe.dto.RecipeSearchEligibility;
import com.cooker.recipe.service.RecipeSearchEligibilityService;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchCandidate;
import com.cooker.retrieval.dto.RecipeSearchCriteria;
import com.cooker.retrieval.dto.RecipeSearchResult;
import com.cooker.retrieval.mapper.RecipeSearchMapper;
import com.cooker.retrieval.service.QueryEmbeddingService;
import com.cooker.retrieval.service.RecipeSearchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class RecipeSearchServiceImpl implements RecipeSearchService {
    private final RecipeSearchEligibilityService eligibilityService;
    private final RecipeSearchMapper mapper;
    private final QueryEmbeddingService queryEmbeddingService;
    private final RetrievalIndexProperties properties;

    public RecipeSearchServiceImpl(RecipeSearchEligibilityService eligibilityService, RecipeSearchMapper mapper,
            QueryEmbeddingService queryEmbeddingService, RetrievalIndexProperties properties) {
        this.eligibilityService = eligibilityService;
        this.mapper = mapper;
        this.queryEmbeddingService = queryEmbeddingService;
        this.properties = properties;
    }

    @Override
    public List<RecipeSearchResult> search(RecipeSearchCriteria criteria) {
        if (criteria == null || criteria.query() == null || criteria.query().strip().isEmpty()) {
            throw new IllegalArgumentException("查询文本不能为空");
        }
        String query = criteria.query().strip();
        int limit = criteria.limit() == null ? 10 : criteria.limit();
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("检索数量必须在 1 到 20 之间");
        if (criteria.maxCookingMinutes() != null && criteria.maxCookingMinutes() < 0) {
            throw new IllegalArgumentException("最长烹饪时间不能为负数");
        }
        List<RecipeSearchEligibility> eligible = eligibilityService.eligibleRecipes(new RecipeEligibilityFilter(query,
                clean(criteria.categoryCodes()), criteria.maxCookingMinutes(), criteria.includeUnknownCookingTime(),
                criteria.availableToolNames() == null ? null : clean(criteria.availableToolNames()),
                clean(criteria.excludedIngredientNames()), criteria.viewerUserId()));
        if (eligible.isEmpty()) return List.of();
        Map<Long, RecipeSearchEligibility> facts = new HashMap<>();
        eligible.forEach(item -> facts.put(item.recipeId(), item));
        List<RecipeSearchCandidate> lexical = mapper.lexical(eligible, query, query.codePointCount(0, query.length()) <= 2,
                properties.getEmbeddingModel(), properties.getEmbeddingDimension(),
                properties.getDocumentTemplateVersion(), criteria.viewerUserId());
        float[] embedding;
        try {
            embedding = queryEmbeddingService.embed(query);
        } catch (EmbeddingProviderException ignored) {
            // 查询侧模型限流、超时或无 Key 时仍返回已验证的词法候选，不暴露第三方异常详情。
            embedding = null;
        }
        List<RecipeSearchCandidate> vector = List.of();
        if (embedding != null && embedding.length == properties.getEmbeddingDimension() && finite(embedding)) {
            vector = mapper.vector(eligible, vectorLiteral(embedding), properties.getEmbeddingModel(),
                    properties.getEmbeddingDimension(), properties.getDocumentTemplateVersion(), criteria.viewerUserId());
        }
        return fuse(lexical, vector, facts, limit);
    }

    /** RRF 只使用每路菜谱名次；同菜多块已由 SQL 选最佳块，不会累积加分。 */
    static List<RecipeSearchResult> fuse(List<RecipeSearchCandidate> lexical,
            List<RecipeSearchCandidate> vector, Map<Long, RecipeSearchEligibility> facts, int limit) {
        Map<Long, Rank> ranks = new HashMap<>();
        for (int i = 0; i < lexical.size(); i++) {
            RecipeSearchCandidate candidate = lexical.get(i);
            if (facts.containsKey(candidate.recipeId())) ranks.computeIfAbsent(candidate.recipeId(), ignored -> new Rank()).lexical(i + 1, candidate);
        }
        for (int i = 0; i < vector.size(); i++) {
            RecipeSearchCandidate candidate = vector.get(i);
            if (facts.containsKey(candidate.recipeId())) ranks.computeIfAbsent(candidate.recipeId(), ignored -> new Rank()).vector(i + 1);
        }
        List<Map.Entry<Long, Rank>> ordered = new ArrayList<>(ranks.entrySet());
        ordered.sort(Comparator.<Map.Entry<Long, Rank>>comparingDouble(entry -> entry.getValue().score()).reversed()
                .thenComparingInt(entry -> entry.getValue().tier)
                .thenComparing((a, b) -> Double.compare(b.getValue().lexicalScore, a.getValue().lexicalScore))
                .thenComparingLong(Map.Entry::getKey));
        List<RecipeSearchResult> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, ordered.size()); i++) {
            var entry = ordered.get(i);
            Rank rank = entry.getValue();
            List<String> reasons = new ArrayList<>();
            if (rank.lexicalRank != null) reasons.add(switch (rank.tier) {
                case 0 -> "TITLE_EXACT";
                case 1 -> "INGREDIENT_EXACT";
                default -> "LEXICAL";
            });
            if (rank.vectorRank != null) reasons.add("SEMANTIC");
            result.add(new RecipeSearchResult(entry.getKey(), i + 1, rank.lexicalRank, rank.vectorRank,
                    rank.score(), List.copyOf(facts.get(entry.getKey()).matchedIngredients()), List.copyOf(reasons)));
        }
        return List.copyOf(result);
    }

    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        values.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isEmpty()).forEach(cleaned::add);
        return List.copyOf(cleaned);
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder("[");
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("查询向量包含非法数值");
            if (out.length() > 1) out.append(',');
            out.append(value);
        }
        return out.append(']').toString();
    }

    private static boolean finite(float[] vector) {
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static final class Rank {
        Integer lexicalRank;
        Integer vectorRank;
        int tier = Integer.MAX_VALUE;
        double lexicalScore = Double.NEGATIVE_INFINITY;
        void lexical(int value, RecipeSearchCandidate candidate) {
            lexicalRank = value;
            tier = candidate.matchTier();
            lexicalScore = candidate.lexicalScore();
        }
        void vector(int value) { vectorRank = value; }
        double score() {
            return (lexicalRank == null ? 0 : 1.0 / (60 + lexicalRank))
                    + (vectorRank == null ? 0 : 1.0 / (60 + vectorRank));
        }
    }
}
