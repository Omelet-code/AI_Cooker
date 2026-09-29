package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class T6EvaluationMetricsTest {
    @Test
    void 按查询区分命中率召回率和倒数排名() {
        var ranked = List.of(9L, 2L, 7L, 5L, 4L, 1L);
        var relevant = Set.of(2L, 5L, 6L);
        assertThat(T6EvaluationMetrics.hitAt(ranked, relevant, 3)).isEqualTo(1.0);
        assertThat(T6EvaluationMetrics.hitAt(ranked, relevant, 1)).isZero();
        assertThat(T6EvaluationMetrics.recallAt(ranked, relevant, 5)).isEqualTo(2.0 / 3.0);
        assertThat(T6EvaluationMetrics.reciprocalRankAt(ranked, relevant, 10)).isEqualTo(0.5);
        assertThat(T6EvaluationMetrics.mean(List.of(1.0, 0.0))).isEqualTo(0.5);
    }
}
