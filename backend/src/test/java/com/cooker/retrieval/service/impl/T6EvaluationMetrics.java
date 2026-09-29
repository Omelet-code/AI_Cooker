package com.cooker.retrieval.service.impl;

import java.util.List;
import java.util.Set;

/** T6 指标计算器：先按查询分别算指标，再对查询取宏平均。 */
final class T6EvaluationMetrics {
    private T6EvaluationMetrics() { }

    static double hitAt(List<Long> rankedIds, Set<Long> relevant, int k) {
        return rankedIds.stream().limit(k).anyMatch(relevant::contains) ? 1.0 : 0.0;
    }

    static double recallAt(List<Long> rankedIds, Set<Long> relevant, int k) {
        if (relevant.isEmpty()) return 0.0;
        long found = rankedIds.stream().limit(k).filter(relevant::contains).distinct().count();
        return (double) found / relevant.size();
    }

    static double reciprocalRankAt(List<Long> rankedIds, Set<Long> relevant, int k) {
        for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
            if (relevant.contains(rankedIds.get(i))) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
