package com.cooker.identity.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cooker.common.ApiException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AesGcmLlmCredentialCryptoTest {
    private static final String FAKE_API_KEY = "fake-test-key-do-not-use-outside-tests";
    private static final String VALID_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void 随机IV让重复加密产生不同密文且可恢复虚构明文() {
        var crypto = new AesGcmLlmCredentialCrypto(VALID_MASTER_KEY);
        var first = crypto.encrypt(42L, FAKE_API_KEY);
        var second = crypto.encrypt(42L, FAKE_API_KEY);

        assertThat(first.iv()).hasSize(12).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(crypto.decrypt(42L, first.ciphertext(), first.iv(), first.algorithmVersion(), first.keyVersion()))
                .isEqualTo(FAKE_API_KEY);
        assertThat(first.algorithmVersion()).isEqualTo(AesGcmLlmCredentialCrypto.ALGORITHM_VERSION);
        assertThat(first.keyVersion()).isEqualTo(AesGcmLlmCredentialCrypto.KEY_VERSION);
        assertThat(first.toString()).doesNotContain(FAKE_API_KEY);
    }

    @Test void AAD绑定用户且密文IV篡改均认证失败() {
        var crypto = new AesGcmLlmCredentialCrypto(VALID_MASTER_KEY);
        var saved = crypto.encrypt(42L, FAKE_API_KEY);
        assertCryptoFailure(() -> crypto.decrypt(43L, saved.ciphertext(), saved.iv(), 1, 1));

        byte[] changedCiphertext = saved.ciphertext();
        changedCiphertext[0] ^= 1;
        assertCryptoFailure(() -> crypto.decrypt(42L, changedCiphertext, saved.iv(), 1, 1));

        byte[] changedIv = saved.iv();
        changedIv[0] ^= 1;
        assertCryptoFailure(() -> crypto.decrypt(42L, saved.ciphertext(), changedIv, 1, 1));
    }

    @Test void 错误主密钥缺失主密钥及未知版本均安全失败() {
        var crypto = new AesGcmLlmCredentialCrypto(VALID_MASTER_KEY);
        var saved = crypto.encrypt(42L, FAKE_API_KEY);
        assertCryptoFailure(() -> crypto.decrypt(42L, saved.ciphertext(), saved.iv(), 2, 1));
        assertCryptoFailure(() -> crypto.decrypt(42L, saved.ciphertext(), saved.iv(), 1, 2));
        byte[] wrongMasterKey = new byte[32];
        java.util.Arrays.fill(wrongMasterKey, (byte) 7);
        assertCryptoFailure(() -> new AesGcmLlmCredentialCrypto(
                Base64.getEncoder().encodeToString(wrongMasterKey)).decrypt(42L, saved.ciphertext(), saved.iv(), 1, 1));
        assertCryptoFailure(() -> new AesGcmLlmCredentialCrypto("").encrypt(42L, FAKE_API_KEY));
        assertCryptoFailure(() -> new AesGcmLlmCredentialCrypto("not-base64!").encrypt(42L, FAKE_API_KEY));
        assertCryptoFailure(() -> new AesGcmLlmCredentialCrypto(
                Base64.getEncoder().encodeToString(new byte[31])).encrypt(42L, FAKE_API_KEY));
    }

    private static void assertCryptoFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class).satisfies(error -> {
            ApiException apiError = (ApiException) error;
            assertThat(apiError.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(apiError.code()).isEqualTo("INTERNAL_ERROR");
            assertThat(apiError.getMessage()).doesNotContain(FAKE_API_KEY, "base64", "GCM", "tag");
            assertThat(apiError.getCause()).isNull();
        });
    }
}
