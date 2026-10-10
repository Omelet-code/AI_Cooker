package com.cooker.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 当前用户密文记录；凭证材料不得被日志或自动生成的 toString 输出。 */
@TableName("user_llm_credential")
public class LlmCredentialEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public byte[] ciphertext;
    public byte[] iv;
    public Integer algorithmVersion;
    public Integer keyVersion;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    @Override public String toString() {
        return "LlmCredentialEntity[id=" + id + ", userId=" + userId + ", credentialMaterial=[REDACTED]]";
    }
}
