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
    private final IndexRunControl control;

    public DashScopeEmbeddingModel(RestClient restClient, ObjectMapper objectMapper,
            RetrievalIndexProperties properties) {
        this(restClient, objectMapper, properties, IndexRunControl.NONE);
    }

    /** 独立document任务可附加出站记账；embedQuery仍使用既有路径，不触发此保护。 */
    public DashScopeEmbeddingModel(RestClient restClient, ObjectMapper objectMapper,
            RetrievalIndexProperties properties, IndexRunControl control) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.control = control;
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
            allEmbeddings.addAll(callBatch(batch, offset, "document"));
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

    /** 查询向量使用百炼 query 模式，不能复用入库文档的 document 模式。 */
    public float[] embedQuery(String query) {
        if (!StringUtils.hasText(query)) throw new IllegalArgumentException("查询文本不能为空");
        return callBatch(List.of(query), 0, "query").getFirst().getOutput();
    }

    /**
     * 调用一次百炼原生 Embedding 接口。
     *
     * @param texts  本批待向量化文本
     * @param offset 本批文本在整体列表中的起始下标，用于还原全局索引顺序
     */
    private List<Embedding> callBatch(List<String> texts, int offset, String textType) {
        if (texts.stream().anyMatch(text -> !StringUtils.hasText(text))) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        Map<String, Object> payload = Map.of(
                "model", properties.getEmbeddingModel(),
                "input", Map.of("texts", texts),
                "parameters", Map.of(
                        "text_type", textType,
                        "dimension", properties.getEmbeddingDimension(),
                        "output_type", "dense"));
        if ("document".equals(textType)) control.beforeSend(texts);
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
            boolean[] seen = new boolean[texts.size()];
            for (JsonNode item : embeddings) {
                int textIndex = item.path("text_index").asInt(-1);
                JsonNode vector = item.path("embedding");
                if (textIndex < 0 || textIndex >= texts.size() || !vector.isArray()) {
                    throw new EmbeddingProviderException("百炼返回的向量格式无效");
                }
                if ("document".equals(textType) && seen[textIndex]) throw new EmbeddingProviderException("百炼返回重复文档索引");
                seen[textIndex] = true;
                float[] values = new float[vector.size()];
                for (int index = 0; index < vector.size(); index++) {
                    values[index] = (float) vector.get(index).asDouble();
                    if ("document".equals(textType) && (!vector.get(index).isNumber() || !Float.isFinite(values[index]))) {
                        throw new EmbeddingProviderException("百炼文档向量包含无效或非有限值");
                    }
                }
                if (values.length != properties.getEmbeddingDimension()) {
                    throw new EmbeddingProviderException("百炼返回的向量维度与配置不一致");
                }
                result.add(new Embedding(values, offset + textIndex));
            }
            if ("document".equals(textType)) {
                JsonNode usage = response.path("usage").path("total_tokens");
                // 只提取安全数值；缺失不补0，也不持久化供应商正文或请求文本。
                control.succeeded(usage.isIntegralNumber() && usage.canConvertToLong() && usage.asLong() >= 0 ? usage.asLong() : null);
            }
            return result;
        } catch (IndexRunStoppedException ex) {
            throw ex;
        } catch (EmbeddingProviderException ex) {
            throw observed(ex, textType);
        } catch (JsonProcessingException ex) {
            throw observed(new EmbeddingProviderException("百炼服务返回的 JSON 格式无效"), textType);
        } catch (RestClientResponseException ex) {
            if ("query".equals(textType)) throw new EmbeddingProviderException("百炼服务请求失败，HTTP 状态码：" + ex.getStatusCode().value());
            throw observed(new EmbeddingProviderException("百炼服务请求失败，HTTP 状态码：" + ex.getStatusCode().value(),
                    classify(ex.getStatusCode().value(), ex.getResponseBodyAsString())), textType);
        } catch (ResourceAccessException ex) {
            // 只记录归类后的网络异常，避免底层异常消息意外包含请求地址或鉴权信息。
            throw observed(new EmbeddingProviderException("百炼服务网络或 TLS 连接失败", EmbeddingProviderException.Kind.NETWORK), textType);
        } catch (RestClientException ex) {
            // 响应读取超时可能被包装为RestClientException，而不是ResourceAccessException。
            // 仅检查原因类型，不提取原因消息，仍按已发送尝试记账并保留一次有界恢复。
            throw observed(new EmbeddingProviderException("百炼客户端请求异常：" + ex.getClass().getSimpleName(),
                    hasNetworkCause(ex) ? EmbeddingProviderException.Kind.NETWORK : EmbeddingProviderException.Kind.CLIENT), textType);
        } catch (RuntimeException ex) {
            throw observed(new EmbeddingProviderException("百炼响应处理异常：" + ex.getClass().getSimpleName()), textType);
        }
    }

    private EmbeddingProviderException observed(EmbeddingProviderException failure, String textType) {
        if ("document".equals(textType)) control.failed(failure);
        return failure;
    }

    private static boolean hasNetworkCause(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof java.io.IOException) return true;
        }
        return false;
    }

    /** 仅将响应错误码用于有限分类，绝不把它或第三方消息写入运行记录。 */
    public static EmbeddingProviderException.Kind classify(int status, String body) {
        if (status == 401 || status == 403) return EmbeddingProviderException.Kind.AUTH;
        if (status >= 300 && status < 400) return EmbeddingProviderException.Kind.REDIRECT;
        String code = "";
        try { code = new ObjectMapper().readTree(body).path("code").asText("").toLowerCase(java.util.Locale.ROOT); }
        catch (Exception ignored) { /* 非JSON错误不回显正文，按HTTP状态保守归类。 */ }
        if (code.contains("quota") || code.contains("balance") || code.contains("arrear") || code.contains("allocation")
                || code.contains("bill") || code.contains("exhaust") || code.contains("credit") || status == 402) {
            return EmbeddingProviderException.Kind.QUOTA;
        }
        // 无法确认的429可能是额度耗尽，保守全局停止；只有明确瞬时请求限流才允许隔离。
        if (status == 429) return code.equals("throttling") || code.contains("requests") || code.contains("ratelimit")
                || code.equals("rate_limit_exceeded") ? EmbeddingProviderException.Kind.RATE_LIMIT : EmbeddingProviderException.Kind.QUOTA;
        if (status >= 500) return EmbeddingProviderException.Kind.SERVER;
        return EmbeddingProviderException.Kind.PARAMETER;
    }
}
