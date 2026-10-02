package com.cooker.identity.service.impl;

import com.cooker.common.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** JDK JCA 实现的 AES-GCM；主密钥只在实际加/解密时读取并校验。 */
@Component
public class AesGcmLlmCredentialCrypto {
    public static final int ALGORITHM_VERSION = 1;
    public static final int KEY_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] PURPOSE = "cooker:identity:user-llm-credential".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String encodedMasterKey;

    public AesGcmLlmCredentialCrypto(
            @Value("${cooker.identity.llm-credential.master-key:}") String encodedMasterKey) {
        // 不在构造阶段校验：缺 Key 时身份、菜谱等不使用凭证的服务仍可启动。
        this.encodedMasterKey = encodedMasterKey;
    }

    public EncryptedCredential encrypt(long userId, String apiKey) {
        byte[] key = decodeMasterKey();
        byte[] clear = apiKey.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId, ALGORITHM_VERSION, KEY_VERSION));
            return new EncryptedCredential(cipher.doFinal(clear), iv, ALGORITHM_VERSION, KEY_VERSION);
        } catch (GeneralSecurityException e) {
            throw unavailable();
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(clear, (byte) 0);
        }
    }

    public String decrypt(long userId, byte[] ciphertext, byte[] iv, int algorithmVersion, int keyVersion) {
        if (algorithmVersion != ALGORITHM_VERSION || keyVersion != KEY_VERSION) throw unavailable();
        if (iv == null || iv.length != IV_BYTES || ciphertext == null || ciphertext.length < TAG_BITS / 8)
            throw unavailable();
        byte[] key = decodeMasterKey();
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId, algorithmVersion, keyVersion));
            clear = cipher.doFinal(ciphertext);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw unavailable();
        } finally {
            Arrays.fill(key, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    private byte[] decodeMasterKey() {
        if (encodedMasterKey == null || encodedMasterKey.isBlank()) throw unavailable();
        final byte[] key;
        try { key = Base64.getDecoder().decode(encodedMasterKey); }
        catch (IllegalArgumentException e) { throw unavailable(); }
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw unavailable();
        }
        return key;
    }

    private static byte[] aad(long userId, int algorithmVersion, int keyVersion) {
        // 长度前缀避免字符串拼接歧义；用途、用户与两个独立版本都受认证标签保护。
        return ByteBuffer.allocate(Integer.BYTES + PURPOSE.length + Long.BYTES + Short.BYTES * 2)
                .putInt(PURPOSE.length).put(PURPOSE).putLong(userId)
                .putShort((short) algorithmVersion).putShort((short) keyVersion).array();
    }

    private static ApiException unavailable() {
        // 不把 Base64 解析、JCA 或认证标签异常作为 cause，避免异常链间接暴露实现细节。
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "凭证服务暂不可用");
    }

    /** 保护数组不被 Mapper 调用方事后修改，且默认字符串表示不包含密文或 IV。 */
    public record EncryptedCredential(byte[] ciphertext, byte[] iv, int algorithmVersion, int keyVersion) {
        public EncryptedCredential {
            ciphertext = ciphertext.clone();
            iv = iv.clone();
        }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public byte[] iv() { return iv.clone(); }
        @Override public String toString() { return "EncryptedCredential[material=[REDACTED]]"; }
    }
}
