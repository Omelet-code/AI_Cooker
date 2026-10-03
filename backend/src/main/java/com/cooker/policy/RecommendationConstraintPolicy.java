package com.cooker.policy;

import com.cooker.agent.dto.ConstraintChange;
import com.cooker.agent.dto.ConstraintChanges;
import com.cooker.agent.dto.RecommendationConstraints;
import com.cooker.agent.dto.UnderstandingResponse;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 以当前用户原文证据校验字段级 KEEP/SET/CLEAR，不接受模型直接覆盖可信状态。 */
public class RecommendationConstraintPolicy {
    private static final Set<String> INTENTS = Set.of("RECOMMEND", "CLARIFY", "UNSUPPORTED");
    private static final Set<String> CLARIFICATION_CODES = Set.of(
            "TIME_AMBIGUOUS", "TOOLS_AMBIGUOUS", "EXCLUSION_AMBIGUOUS", "REQUEST_AMBIGUOUS");

    public RecommendationConstraints defaults() {
        return new RecommendationConstraints("家常菜", null, null, List.of(), List.of(), null);
    }

    public RecommendationConstraints merge(RecommendationConstraints previous,
            UnderstandingResponse response, String original) {
        if (response == null || !INTENTS.contains(response.intent()) || response.changes() == null)
            throw invalid();
        String query = requiredText(response.query(), 512);
        ConstraintChanges changes = response.changes();
        Integer minutes = apply(changes.maxCookingMinutes(), previous.maxCookingMinutes(), original,
                null, value -> value != null && value >= 1 && value <= 1440);
        List<String> tools = apply(changes.availableToolNames(), previous.availableToolNames(), original,
                null, RecommendationConstraintPolicy::validNames);
        List<String> exclusions = apply(changes.excludedIngredientNames(), previous.excludedIngredientNames(), original,
                List.of(), RecommendationConstraintPolicy::validNames);
        List<String> available = apply(changes.availableIngredientNames(), previous.availableIngredientNames(), original,
                List.of(), RecommendationConstraintPolicy::validNames);
        String budget = apply(changes.budgetYuan(), previous.budgetYuan(), original,
                null, RecommendationConstraintPolicy::validBudget);

        if (response.clarificationCodes() == null || response.clarificationCodes().size() > 4
                || response.clarificationCodes().stream().anyMatch(code -> !CLARIFICATION_CODES.contains(code)))
            throw invalid();

        if (!"RECOMMEND".equals(response.intent())) return previous;
        if (!response.clarificationCodes().isEmpty()) throw invalid();
        return new RecommendationConstraints(query, minutes, tools, exclusions, available, budget);
    }

    private static <T> T apply(ConstraintChange<T> change, T previous, String original, T clearValue,
            java.util.function.Predicate<T> validValue) {
        if (change == null || change.action() == null) throw invalid();
        return switch (change.action()) {
            case "KEEP" -> {
                if (change.value() != null || change.evidence() != null) throw invalid();
                yield previous;
            }
            case "SET" -> {
                verifyEvidence(change.evidence(), original);
                if (change.value() == null || !validValue.test(change.value())) throw invalid();
                yield normalizeValue(change.value());
            }
            case "CLEAR" -> {
                verifyEvidence(change.evidence(), original);
                if (change.value() != null) throw invalid();
                yield clearValue;
            }
            default -> throw invalid();
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T normalizeValue(T value) {
        if (value instanceof List<?> list) return (T) cleanNames((List<String>) list);
        if (value instanceof String text) return (T) text.strip();
        return value;
    }

    private static void verifyEvidence(String evidence, String original) {
        if (evidence == null || evidence.isBlank() || evidence.codePointCount(0, evidence.length()) > 200
                || !original.contains(evidence)) throw invalid();
    }

    private static boolean validNames(List<String> values) {
        if (values == null || values.size() > 20) return false;
        for (String value : values) {
            if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 80) return false;
        }
        return true;
    }

    private static List<String> cleanNames(List<String> values) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : values) distinct.add(value.strip());
        return List.copyOf(distinct);
    }

    private static boolean validBudget(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,4})(?:\\.[0-9]{1,2})?")) return false;
        try {
            BigDecimal amount = new BigDecimal(value);
            return amount.signum() >= 0 && amount.compareTo(new BigDecimal("10000")) <= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String requiredText(String value, int maxCodePoints) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) throw invalid();
        return value.strip();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("模型约束或证据不符合契约");
    }
}
