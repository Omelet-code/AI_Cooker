package com.cooker.retrieval.service.impl;

/** 外部向量服务的脱敏失败类型，消息不得携带请求正文、响应正文或 API Key。 */
public class EmbeddingProviderException extends RuntimeException {
    /** 仅保存有限分类，不携带供应商响应正文。 */
    public enum Kind {
        AUTH(true), PARAMETER(true), QUOTA(true), RESPONSE(true), REDIRECT(true),
        RATE_LIMIT(false), NETWORK(false), SERVER(false), CLIENT(true);
        private final boolean global;
        Kind(boolean global) { this.global = global; }
        public boolean global() { return global; }
    }
    private final Kind kind;
    public EmbeddingProviderException(String message) {
        this(message, Kind.RESPONSE);
    }
    public EmbeddingProviderException(String message, Kind kind) {
        super(message);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
