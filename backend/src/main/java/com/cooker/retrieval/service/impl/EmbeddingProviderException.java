package com.cooker.retrieval.service.impl;

/** 外部向量服务的脱敏失败类型，消息不得携带请求正文、响应正文或 API Key。 */
public class EmbeddingProviderException extends RuntimeException {
    public EmbeddingProviderException(String message) {
        super(message);
    }
}
