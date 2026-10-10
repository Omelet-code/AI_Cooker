package com.cooker.identity.dto;

import tools.jackson.databind.JsonNode;

/** 请求对象严禁在 toString 中包含用户提交的凭证。 */
public record SaveLlmCredentialRequest(JsonNode apiKey) {
    @Override public String toString() { return "SaveLlmCredentialRequest[apiKey=[REDACTED]]"; }
}
