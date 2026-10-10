package com.cooker.identity.service.impl;

import com.cooker.common.ApiException;
import com.cooker.identity.entity.LlmCredentialEntity;
import com.cooker.identity.mapper.LlmCredentialMapper;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.identity.service.LlmCredentialService;
import com.cooker.identity.vo.LlmCredentialStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmCredentialServiceImpl implements LlmCredentialService {
    private final CurrentUserService currentUser;
    private final LlmCredentialMapper mapper;
    private final AesGcmLlmCredentialCrypto crypto;

    public LlmCredentialServiceImpl(CurrentUserService currentUser, LlmCredentialMapper mapper,
            AesGcmLlmCredentialCrypto crypto) {
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.crypto = crypto;
    }

    @Override @Transactional
    public LlmCredentialStatus saveCurrentUser(String apiKey) {
        long userId = currentUser.requireUserId();
        if (apiKey == null || apiKey.isBlank() || apiKey.codePointCount(0, apiKey.length()) > 4096)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
        // 先在内存中完成认证加密；主密钥不可用时不会触及数据库，也不降级存明文。
        var encrypted = crypto.encrypt(userId, apiKey);
        mapper.upsert(userId, encrypted.ciphertext(), encrypted.iv(),
                encrypted.algorithmVersion(), encrypted.keyVersion());
        return new LlmCredentialStatus(true);
    }

    @Override @Transactional
    public LlmCredentialStatus deleteCurrentUser() {
        mapper.deleteByUserId(currentUser.requireUserId());
        return new LlmCredentialStatus(false);
    }

    @Override @Transactional(readOnly = true)
    public LlmCredentialStatus currentUserStatus() {
        return new LlmCredentialStatus(mapper.existsByUserId(currentUser.requireUserId()));
    }

    @Override @Transactional(readOnly = true)
    public String decryptCurrentUserCredential() {
        long userId = currentUser.requireUserId();
        LlmCredentialEntity entity = mapper.findByUserId(userId);
        if (entity == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "LLM_CREDENTIAL_NOT_CONFIGURED", "尚未配置模型凭证");
        return crypto.decrypt(userId, entity.ciphertext, entity.iv,
                entity.algorithmVersion, entity.keyVersion);
    }
}
