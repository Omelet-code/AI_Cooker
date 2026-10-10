package com.cooker.shopping.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 清单持久化行，仅供shopping内部Mapper使用。 */
public class ShoppingList {
    public Long id,userId,conversationId,mealId,optionId;
    public Integer version,unknownItemCount;
    public String requestId,inputHash,sourceFingerprint,status,budgetStatus,evidenceJson,viewJson;
    public BigDecimal budgetYuan,estimatedDemandCost,consumptionCost,purchaseOutlay;
    public OffsetDateTime createdAt;
}
