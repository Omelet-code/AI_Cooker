package com.cooker.agent.service;

/** 不带供应商异常链或请求内容的网关安全异常。 */
public final class ModelGatewayException extends RuntimeException {
    private final Reason reason;

    public ModelGatewayException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_INPUT,
        AUTHENTICATION_REQUIRED,
        CREDENTIAL_NOT_CONFIGURED,
        CREDENTIAL_SERVICE_UNAVAILABLE,
        MODEL_AUTHENTICATION_FAILED,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        TIMEOUT,
        EVALUATION_STOPPED,
        INVALID_RESPONSE
    }
}
