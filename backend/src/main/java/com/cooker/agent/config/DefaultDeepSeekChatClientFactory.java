package com.cooker.agent.config;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.cooker.agent.dto.ModelOutputMode;
import com.cooker.agent.service.ModelGatewayException;
import com.cooker.evaluation.transport.SyncModelTransport;
import com.cooker.evaluation.transport.DefaultSyncModelTransport;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;

/** 为每次请求创建固定地址、固定模型且不重试的 DeepSeek 客户端。 */
@Component
public class DefaultDeepSeekChatClientFactory implements DeepSeekChatClientFactory {
    private static final String API_BASE_URL = "https://api.deepseek.com";
    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String BETA_PREFIX_PATH = "/beta";
    private static final String MODEL_NAME = "deepseek-flash";
    private static final int MAX_OUTPUT_TOKENS = 2048;

    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final SyncModelTransport transport;

    public DefaultDeepSeekChatClientFactory() {
        this(API_BASE_URL, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }
    @Autowired
    public DefaultDeepSeekChatClientFactory(SyncModelTransport transport) {
        this(API_BASE_URL, Duration.ofSeconds(5), Duration.ofSeconds(30), transport);
    }

    /** 仅供同包协议测试注入本地 Mock 地址与短超时；不由应用配置绑定。 */
    DefaultDeepSeekChatClientFactory(String baseUrl, Duration connectTimeout, Duration responseTimeout) {
        this(baseUrl,connectTimeout,responseTimeout,DefaultSyncModelTransport.ordinary());
    }
    /** 仅同包测试注入本机地址，不新增运行时目的地址配置。 */
    DefaultDeepSeekChatClientFactory(String baseUrl, Duration connectTimeout, Duration responseTimeout, SyncModelTransport transport) {
        this.baseUrl = baseUrl;
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.transport = transport;
    }

    @Override
    public ChatModel create(String apiKey, ModelOutputMode outputMode) {
        return build(apiKey, outputMode, MAX_OUTPUT_TOKENS, responseTimeout);
    }

    @Override
    public ChatModel createGuide(String apiKey) {
        return build(apiKey, ModelOutputMode.JSON, 8192, guideReadTimeout());
    }

    // 固定服务端档位，不允许HTTP请求或模型决定上限；测试构造器仍可使用本地短超时。
    Duration guideReadTimeout() {
        return API_BASE_URL.equals(baseUrl) ? Duration.ofSeconds(60) : responseTimeout;
    }

    Duration defaultReadTimeout() { return responseTimeout; }

    private ChatModel build(String apiKey, ModelOutputMode outputMode, int outputTokens, Duration readTimeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE,
                    "模型凭证暂不可用");
        }

        RestClient restClient = transport.builder(SyncModelTransport.Lane.LLM, URI.create(baseUrl + COMPLETIONS_PATH), connectTimeout, readTimeout)
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(apiKey))
                .build();
        // T12 同步请求只走 RestClient；流式路径尚未开放，WebClient 不持有用户凭证。
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        DeepSeekApi api = new DeepSeekApi(COMPLETIONS_PATH, BETA_PREFIX_PATH, restClient, webClient);

        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder()
                .model(MODEL_NAME)
                .maxTokens(outputTokens)
                .disableThinking();
        if (outputMode == ModelOutputMode.JSON) {
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }

        ChatModel delegate = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options.build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
        // 方法额度在实际call而非创建客户端时保留；整个进程所有Spring注入调用共用同一提供者。
        return transport.model(delegate);
    }
}
