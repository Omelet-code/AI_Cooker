package com.cooker.evaluation.transport;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;

/** 模型同步HTTP的窄接缝，不改变业务DTO、身份或模型策略。 */
public interface SyncModelTransport {
    enum Lane { QUERY, LLM }
    RestClient.Builder builder(Lane lane, URI endpoint, Duration connectTimeout, Duration readTimeout);
    <T> T call(Lane lane, Supplier<T> operation);
    default org.springframework.ai.chat.model.ChatModel model(org.springframework.ai.chat.model.ChatModel delegate){return delegate;}
    /** 受控模式跨模型返回与业务校验持有同一批次锁，普通模式直接执行。 */
    default <T>T withGuideValidation(Supplier<T> operation){return operation.get();}
    /** 仅通知业务拒绝，不回写传输成功，不退款，不改变普通模式。 */
    default void guideRejected(){}
}
