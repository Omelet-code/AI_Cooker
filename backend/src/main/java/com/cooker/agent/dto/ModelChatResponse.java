package com.cooker.agent.dto;

/** 不暴露 Spring AI 或 DeepSeek 的响应类型。 */
public record ModelChatResponse(String content, String finishReason, ModelUsage usage) {
    @Override
    public String toString() {
        return "ModelChatResponse[content=[已隐藏], finishReason=" + finishReason + ", usage=" + usage + "]";
    }
}
