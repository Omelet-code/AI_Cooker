package com.cooker.retrieval.service.impl;

import com.cooker.retrieval.service.QueryEmbeddingService;
import java.util.function.Supplier;

/** 仅用于 T6 测试层：装饰查询向量服务，测量网络调用并识别生产 Service 的静默词法降级。 */
final class T6EmbeddingProbe {
    private boolean remoteCallsEnabled = true;
    private int expectedDimension;
    private Observation observation = Observation.none();

    QueryEmbeddingService decorate(QueryEmbeddingService delegate) {
        return query -> {
            if (!remoteCallsEnabled) {
                observation = new Observation(1, 0, 0, false, false, 0, null);
                return null;
            }

            long started = System.nanoTime();
            try {
                float[] vector = delegate.embed(query);
                observation = new Observation(1, 1, elapsedMicros(started), vector != null,
                        isUsable(vector), vector == null ? 0 : vector.length, null);
                return vector;
            } catch (RuntimeException exception) {
                observation = new Observation(1, 1, elapsedMicros(started), false, false, 0,
                        exception.getClass().getSimpleName());
                throw exception;
            }
        };
    }

    void reset() {
        observation = Observation.none();
    }

    Observation observation() {
        return observation;
    }

    void expectedDimension(int dimension) {
        expectedDimension = dimension;
    }

    <T> T withoutRemoteCall(Supplier<T> action) {
        boolean previous = remoteCallsEnabled;
        remoteCallsEnabled = false;
        try {
            return action.get();
        } finally {
            remoteCallsEnabled = previous;
        }
    }

    private boolean isUsable(float[] vector) {
        if (vector == null || vector.length != expectedDimension) return false;
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static long elapsedMicros(long started) {
        return (System.nanoTime() - started) / 1_000;
    }

    record Observation(int invocations, int providerCalls, long elapsedMicros,
            boolean vectorReturned, boolean usableVector, int vectorDimension, String failureType) {
        static Observation none() {
            return new Observation(0, 0, 0, false, false, 0, null);
        }
    }
}
