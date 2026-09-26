package com.cooker.retrieval.service.impl;

import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Spring AI 2 的 EmbeddingModel 适配器。DashScope 特有的 HTTP 结构与 text_type=document
 * 被限制在该类中，调用方只依赖 EmbeddingModel，后续可替换为兼容的官方 Provider。
 */
public final class DashScopeEmbeddingModel implements EmbeddingModel {
    private static final int MAX_BATCH_SIZE = 10;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RetrievalIndexProperties properties;

    public DashScopeEmbeddingModel(RestClient restClient, ObjectMapper objectMapper,
            RetrievalIndexProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Spring AI 统一入口：按 request 的顺序返回向量，内部再切分为每批最多 10 条文本。
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) return new EmbeddingResponse(List.of());
        List<Embedding> allEmbeddings = new ArrayList<>();
        for (int offset = 0; offset < texts.size(); offset += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(offset, Math.min(offset + MAX_BATCH_SIZE, texts.size()));
            allEmbeddings.addAll(callBatch(batch, offset));
        }
        allEmbeddings.sort(Comparator.comparing(Embedding::getIndex));
        return new EmbeddingResponse(List.copyOf(allEmbeddings));
    }

    /** 便捷重载：对单个 Spring AI Document 生成向量。 */
    @Override
    public float[] embed(Document document) {
        if (document == null) throw new IllegalArgumentException("待向量化文档不能为空");
        return embed(getEmbeddingContent(document));
    }

    /** 维度直接取自配置，不做远程探测。 */
    @Override
    public int dimensions() {
        // 避免 Spring AI 默认实现为了获知维度而额外产生一次付费远程调用。
        return properties.getEmbeddingDimension();
    }

    /**
     * 调用一次百炼原生 Embedding 接口。
     *
     * @param texts  本批待向量化文本
     * @param offset 本批文本在整体列表中的起始下标，用于还原全局索引顺序
     */
    private List<Embedding> callBatch(List<String> texts, int offset) {
        if (texts.stream().anyMatch(text -> !StringUtils.hasText(text))) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        Map<String, Object> payload = Map.of(
                "model", properties.getEmbeddingModel(),
                "input", Map.of("texts", texts),
                "parameters", Map.of(
                        "text_type", "document",
                        "dimension", properties.getEmbeddingDimension(),
                        "output_type", "dense"));
        try {
            // 百炼实际返回 JSON，但其响应 Content-Type 在不同网关环境可能不稳定。
            // 先按文本接收并使用项目的 Jackson 解析，避免 RestClient 按媒体类型选择转换器失败。
            String responseBody = restClient.post().uri(properties.getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
            JsonNode response = responseBody == null ? null : objectMapper.readTree(responseBody);
            JsonNode embeddings = response == null ? null : response.path("output").path("embeddings");
            if (embeddings == null || !embeddings.isArray() || embeddings.size() != texts.size()) {
                throw new EmbeddingProviderException("百炼返回的向量数量与请求不一致");
            }
            List<Embedding> result = new ArrayList<>();
            for (JsonNode item : embeddings) {
                int textIndex = item.path("text_index").asInt(-1);
                JsonNode vector = item.path("embedding");
                if (textIndex < 0 || textIndex >= texts.size() || !vector.isArray()) {
                    throw new EmbeddingProviderException("百炼返回的向量格式无效");
                }
                float[] values = new float[vector.size()];
                for (int index = 0; index < vector.size(); index++) values[index] = (float) vector.get(index).asDouble();
                if (values.length != properties.getEmbeddingDimension()) {
                    throw new EmbeddingProviderException("百炼返回的向量维度与配置不一致");
                }
                result.add(new Embedding(values, offset + textIndex));
            }
            return result;
        } catch (EmbeddingProviderException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new EmbeddingProviderException("百炼服务返回的 JSON 格式无效");
        } catch (RestClientResponseException ex) {
            throw new EmbeddingProviderException("百炼服务请求失败，HTTP 状态码：" + ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            // 只记录归类后的网络异常，避免底层异常消息意外包含请求地址或鉴权信息。
            throw new EmbeddingProviderException("百炼服务网络或 TLS 连接失败");
        } catch (RestClientException ex) {
            throw new EmbeddingProviderException("百炼客户端请求异常：" + ex.getClass().getSimpleName());
        } catch (RuntimeException ex) {
            throw new EmbeddingProviderException("百炼响应处理异常：" + ex.getClass().getSimpleName());
        }
    }
}
