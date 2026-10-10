package com.cooker.conversation.service.impl;

import java.util.Map;

final class ConversationErrors {
    static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("RUNTIME_INTERRUPTED", "执行已中断，请重新发起请求"),
            Map.entry("CREDENTIAL_NOT_CONFIGURED", "尚未配置模型凭证"),
            Map.entry("CREDENTIAL_SERVICE_UNAVAILABLE", "凭证服务暂时不可用"),
            Map.entry("MODEL_AUTHENTICATION_FAILED", "模型认证失败"),
            Map.entry("MODEL_RATE_LIMITED", "模型服务请求过于频繁"),
            Map.entry("MODEL_TIMEOUT", "模型处理超时"),
            Map.entry("MODEL_UNAVAILABLE", "模型服务暂时不可用"),
            Map.entry("MODEL_INVALID_RESPONSE", "模型返回内容无效"),
            Map.entry("RETRIEVAL_FAILED", "菜谱检索失败"),
            Map.entry("RESOURCE_LIMIT", "请求超过资源限制"),
            Map.entry("EXECUTION_FAILED", "执行失败，请稍后重试"));

    private ConversationErrors() { }
    static String message(String code) { return MESSAGES.getOrDefault(code, "执行失败，请稍后重试"); }
    static boolean allowed(String code) { return MESSAGES.containsKey(code); }
}
