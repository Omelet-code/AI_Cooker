package com.cooker.retrieval.config;

import com.cooker.retrieval.service.impl.DashScopeEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 仅在显式索引任务中创建 document EmbeddingModel，常规 Web 启动不会要求百炼密钥。
 * 项目代码依赖 Spring AI 的 EmbeddingModel 接口，DashScope HTTP 细节局限在此适配器内。
 */
@Configuration(proxyBeanMethods = false)
@Profile("recipe-index")
@ConditionalOnProperty(prefix = "cooker.retrieval.index", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RetrievalIndexProperties.class)
public class RetrievalEmbeddingConfiguration {
    /**
     * 创建文档侧 EmbeddingModel；缺少 Key 或 endpoint 时直接失败，避免发出无意义的远程调用。
     * Bean 名固定为 documentEmbeddingModel，与 RecipeIndexServiceImpl 的注入点对应。
     */
    @Bean("documentEmbeddingModel")
    EmbeddingModel documentEmbeddingModel(RetrievalIndexProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalArgumentException("启用 recipe-index 时必须提供 DASHSCOPE_API_KEY");
        }
        if (!StringUtils.hasText(properties.getEndpoint())) {
            throw new IllegalArgumentException("启用 recipe-index 时必须提供百炼 Embedding endpoint");
        }
        // 当前轻量级 Web 依赖不会自动注册 ObjectMapper Bean；该对象只解析百炼的简单 JSON 响应。
        return new DashScopeEmbeddingModel(RestClient.create(), new ObjectMapper(), properties);
    }
}
