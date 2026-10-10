package com.cooker.shopping.vo;

import java.util.List;

/** 独立Mock快照；所有数量和金额为普通十进制字符串，购买链接始终空。 */
public record ProductMatchView(int schemaVersion,String id,String listId,String sourceFingerprint,String factsStatus,
        String provider,boolean realData,String catalogVersion,String cityCode,String capturedAt,String matchingStatus,
        List<Line> lines,List<PurchaseGroup> purchaseGroups,Costs costs,String message) {
    public record Product(String productId,String ingredientId,String name,String packageAmount,String packageUnit,String packagePrice,String currency,String purchaseUrl,String referenceUnitPrice,String referenceUnit) {}
    public record Line(String itemKey,List<String> lineKeys,String ingredientId,String name,String requiredAmount,String shortageAmount,String unitCode,
            String status,String reason,Product product,String packageCount,String consumptionCost,String purchaseOutlay,String estimatedDemandCost,String estimateSource) {}
    public record PurchaseGroup(String groupKey,List<String> itemKeys,String quantity,String unitCode,Product product,String packageCount,String purchaseOutlay) {}
    public record Costs(String currency,String estimatedDemandCost,String consumptionCost,String purchaseOutlay,String knownSubtotal,int unknownItemCount,
            String estimatedKnownSubtotal,int estimatedUnknownItemCount,String budgetStatus) {}
}
