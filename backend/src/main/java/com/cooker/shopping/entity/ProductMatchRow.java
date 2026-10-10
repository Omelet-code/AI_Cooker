package com.cooker.shopping.entity;

import java.time.OffsetDateTime;

/** 新匹配头表内部映射，不覆盖T16需求快照。 */
public class ProductMatchRow {
    public Long id,userId,listId;
    public String requestId,inputHash,sourceFingerprint,cityCode,catalogVersion,matchingStatus,viewJson;
    public OffsetDateTime capturedAt;
}
