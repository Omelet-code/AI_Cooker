package com.cooker.agent.config;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.cooker.agent.dto.ModelOutputMode;
import com.cooker.agent.service.ModelGatewayException;

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

    public DefaultDeepSeekChatClientFactory() {
        this(API_BASE_URL, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    /** 仅供同包协议测试注入本地 Mock 地址与短超时；不由应用配置绑定。 */
    DefaultDeepSeekChatClientFactory(String baseUrl, Duration connectTimeout, Duration responseTimeout) {
        this.baseUrl = baseUrl;
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
    }

    @Override
    public ChatModel create(String apiKey, ModelOutputMode outputMode) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE,
                    "模型凭证暂不可用");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(responseTimeout);

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBearerAuth(apiKey))
                .build();
        // T12 同步请求只走 RestClient；流式路径尚未开放，WebClient 不持有用户凭证。
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        DeepSeekApi api = new DeepSeekApi(COMPLETIONS_PATH, BETA_PREFIX_PATH, restClient, webClient);

        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder()
                .model(MODEL_NAME)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .disableThinking();
        if (outputMode == ModelOutputMode.JSON) {
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options.build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
    }
}
