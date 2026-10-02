package com.cooker.identity.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.cooker.common.ApiException;
import com.cooker.identity.dto.SaveLlmCredentialRequest;
import com.cooker.identity.entity.LlmCredentialEntity;
import com.cooker.identity.mapper.LlmCredentialMapper;
import com.cooker.identity.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LlmCredentialServiceImplTest {
    @Test void 缺少主密钥时状态删除仍可用且保存不会写库() {
        CurrentUserService current = mock(CurrentUserService.class);
        LlmCredentialMapper mapper = mock(LlmCredentialMapper.class);
        when(current.requireUserId()).thenReturn(42L);
        when(mapper.existsByUserId(42L)).thenReturn(false);
        var service = new LlmCredentialServiceImpl(current, mapper, new AesGcmLlmCredentialCrypto(""));

        assertThat(service.currentUserStatus().configured()).isFalse();
        assertThat(service.deleteCurrentUser().configured()).isFalse();
        assertThatThrownBy(() -> service.saveCurrentUser("fake-test-key"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        verify(mapper).deleteByUserId(42L);
        verify(mapper, never()).upsert(anyLong(), any(), any(), anyInt(), anyInt());
        verify(mapper, never()).findByUserId(anyLong());
    }

    @Test void 请求DTO与实体字符串表示不暴露凭证材料() {
        String fake = "fake-test-key-do-not-log";
        var request = new SaveLlmCredentialRequest(tools.jackson.databind.node.JsonNodeFactory.instance.textNode(fake));
        var entity = new LlmCredentialEntity();
        entity.userId = 42L;
        entity.ciphertext = fake.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        entity.iv = new byte[] {1, 2, 3};
        assertThat(request.toString()).doesNotContain(fake).contains("REDACTED");
        assertThat(entity.toString()).doesNotContain(fake).contains("REDACTED");
    }
}
