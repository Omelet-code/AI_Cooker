package com.cooker.identity.service;

import com.cooker.identity.vo.LlmCredentialStatus;

/** 用户 Key 管理与未来 Agent 使用的内部解密能力；不提供按任意用户读取明文的方法。 */
public interface LlmCredentialService {
    LlmCredentialStatus saveCurrentUser(String apiKey);
    LlmCredentialStatus deleteCurrentUser();
    LlmCredentialStatus currentUserStatus();

    /** 仅从当前认证上下文读取并解密；不对应任何 HTTP 明文读取端点。 */
    String decryptCurrentUserCredential();
}
