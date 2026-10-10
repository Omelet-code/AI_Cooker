package com.cooker.agent.dto;

/** 供应商确实返回 usage 时才提供用量；未知值保持 null。 */
public record ModelUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
