package com.cooker.retrieval.service.impl;

import com.cooker.retrieval.dto.RecipeSearchCriteria;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 从菜谱事实表独立读取检索结果事实，避免把资格筛选结果当作自己的正确性依据。 */
final class T6RecipeHardConditionAuditor {
    private final JdbcTemplate jdbc;

    T6RecipeHardConditionAuditor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Violation> audit(String queryId, Collection<Long> recipeIds, RecipeSearchCriteria criteria) {
        List<Long> ids = recipeIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return List.of();

        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        Object[] parameters = ids.toArray();
        Map<Long, MutableFacts> facts = new HashMap<>();
        forEachRow("SELECT r.id, r.category_code, r.estimated_minutes, s.source_type, s.owner_user_id, s.visibility "
                        + "FROM recipe r JOIN recipe_source s ON s.id = r.source_id "
                        + "WHERE r.id IN (" + placeholders + ")",
                parameters, row -> { facts.put(row.getLong("id"), readBase(row)); });

        forEachRow("SELECT rt.recipe_id, ct.canonical_name FROM recipe_tool rt "
                        + "JOIN cooking_tool ct ON ct.id = rt.tool_id "
                        + "WHERE rt.optional = FALSE AND rt.recipe_id IN (" + placeholders + ")",
                parameters, row -> { add(facts, row.getLong("recipe_id"), row.getString("canonical_name"), true); });

        forEachRow("SELECT ri.recipe_id, ri.raw_name, i.canonical_name, ia.alias_name "
                        + "FROM recipe_ingredient ri LEFT JOIN ingredient i ON i.id = ri.ingredient_id "
                        + "LEFT JOIN ingredient_alias ia ON ia.ingredient_id = ri.ingredient_id "
                        + "WHERE ri.recipe_id IN (" + placeholders + ")",
                parameters, row -> {
                    MutableFacts item = facts.get(row.getLong("recipe_id"));
                    if (item != null) {
                        item.ingredientTerms.add(normalize(row.getString("raw_name")));
                        item.ingredientTerms.add(normalize(row.getString("canonical_name")));
                        item.ingredientTerms.add(normalize(row.getString("alias_name")));
                    }
                });

        List<Violation> violations = new ArrayList<>();
        for (Long id : ids) {
            MutableFacts item = facts.get(id);
            if (item == null) {
                violations.add(new Violation(queryId, id, "事实存在性", "结果 ID 在 recipe/recipe_source 中不存在"));
                continue;
            }
            violations.addAll(auditFacts(queryId, item.freeze(), criteria));
        }
        return List.copyOf(violations);
    }

    private void forEachRow(String sql, Object[] parameters, RowCallbackHandler handler) {
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement;
        }, handler);
    }

    static List<Violation> auditFacts(String queryId, RecipeFacts facts, RecipeSearchCriteria criteria) {
        List<Violation> violations = new ArrayList<>();
        if (criteria.categoryCodes() != null && !criteria.categoryCodes().isEmpty()
                && !criteria.categoryCodes().contains(facts.categoryCode())) {
            violations.add(new Violation(queryId, facts.recipeId(), "分类", "菜谱分类不在请求分类范围内"));
        }
        if (criteria.maxCookingMinutes() != null) {
            Integer minutes = facts.estimatedMinutes();
            if (minutes == null && !criteria.includeUnknownCookingTime()
                    || minutes != null && minutes > criteria.maxCookingMinutes()) {
                violations.add(new Violation(queryId, facts.recipeId(), "时间", "估算用时违反请求上限"));
            }
        }
        if (criteria.availableToolNames() != null) {
            Set<String> available = normalizedSet(criteria.availableToolNames());
            facts.requiredToolNames().stream().map(T6RecipeHardConditionAuditor::normalize)
                    .filter(tool -> !available.contains(tool))
                    .forEach(tool -> violations.add(new Violation(queryId, facts.recipeId(), "厨具",
                            "必需厨具不在可用列表中：" + tool)));
        }
        Set<String> excluded = normalizedSet(criteria.excludedIngredientNames());
        facts.ingredientTerms().stream().filter(excluded::contains)
                .forEach(ingredient -> violations.add(new Violation(queryId, facts.recipeId(), "排除食材",
                        "菜谱事实包含排除食材：" + ingredient)));
        boolean visible = "SYSTEM_HOWTOCOOK".equals(facts.sourceType())
                && facts.ownerUserId() == null && "PUBLIC".equals(facts.visibility())
                || "USER_CUSTOM".equals(facts.sourceType()) && "PRIVATE".equals(facts.visibility())
                && criteria.viewerUserId() != null && criteria.viewerUserId().equals(facts.ownerUserId());
        if (!visible) {
            violations.add(new Violation(queryId, facts.recipeId(), "可见性", "菜谱来源/所有者不允许当前查看者访问"));
        }
        return List.copyOf(violations);
    }

    private static MutableFacts readBase(ResultSet row) throws SQLException {
        return new MutableFacts(row.getLong("id"), row.getString("category_code"),
                row.getObject("estimated_minutes", Integer.class), row.getString("source_type"),
                row.getObject("owner_user_id", Long.class), row.getString("visibility"));
    }

    private static void add(Map<Long, MutableFacts> facts, long recipeId, String value, boolean tool) {
        MutableFacts item = facts.get(recipeId);
        if (item != null && tool) item.requiredToolNames.add(value);
    }

    private static Set<String> normalizedSet(List<String> values) {
        if (values == null) return Set.of();
        return values.stream().map(T6RecipeHardConditionAuditor::normalize)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    record Violation(String queryId, long recipeId, String condition, String reason) { }

    record RecipeFacts(long recipeId, String categoryCode, Integer estimatedMinutes, String sourceType,
            Long ownerUserId, String visibility, Set<String> requiredToolNames, Set<String> ingredientTerms) { }

    private static final class MutableFacts {
        private final long recipeId;
        private final String categoryCode;
        private final Integer estimatedMinutes;
        private final String sourceType;
        private final Long ownerUserId;
        private final String visibility;
        private final Set<String> requiredToolNames = new LinkedHashSet<>();
        private final Set<String> ingredientTerms = new LinkedHashSet<>();

        private MutableFacts(long recipeId, String categoryCode, Integer estimatedMinutes,
                String sourceType, Long ownerUserId, String visibility) {
            this.recipeId = recipeId;
            this.categoryCode = categoryCode;
            this.estimatedMinutes = estimatedMinutes;
            this.sourceType = sourceType;
            this.ownerUserId = ownerUserId;
            this.visibility = visibility;
        }

        private RecipeFacts freeze() {
            return new RecipeFacts(recipeId, categoryCode, estimatedMinutes, sourceType,
                    ownerUserId, visibility, Set.copyOf(requiredToolNames), Set.copyOf(ingredientTerms));
        }
    }
}
