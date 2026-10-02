package com.cooker.identity.mapper;

import com.cooker.identity.entity.LlmCredentialEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LlmCredentialMapper {
    int upsert(@Param("userId") Long userId, @Param("ciphertext") byte[] ciphertext,
            @Param("iv") byte[] iv, @Param("algorithmVersion") int algorithmVersion,
            @Param("keyVersion") int keyVersion);
    LlmCredentialEntity findByUserId(@Param("userId") Long userId);
    boolean existsByUserId(@Param("userId") Long userId);
    int deleteByUserId(@Param("userId") Long userId);
}
