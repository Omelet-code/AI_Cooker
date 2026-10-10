package com.cooker.shopping.vo;

import java.util.List;

/** HTTP数量及ID为十进制字符串；未知金额/数量保持null。 */
public record ShoppingView(int schemaVersion,String id,String createdAt,String factsStatus,Source source,List<Line> lines,
        List<Item> items,String status,String matchingStatus,Costs costs) {
    public record Source(String conversationId,String mealId,int version,String optionId,int diningCount,String budgetYuan,String sourceFingerprint) {}
    public record Line(String lineKey,String recipeId,String recipeIngredientId,String ingredientId,String name,String rawLine,
            boolean optional,String originalAmountMin,String originalAmountMax,String unitCode,String unitLabel,
            String referenceAmount,String scaleStatus,String reason,boolean needsConfirmation,
            String availability,String confirmedAmount,String confirmedUnitCode) {}
    public record Item(String itemKey,List<String> lineKeys,String ingredientId,String name,String unitCode,String unitLabel,
            boolean optional,String requiredAmount,String shortageAmount,String status,String reason) {}
    public record Costs(String currency,String estimatedDemandCost,String consumptionCost,String purchaseOutlay,
            String knownSubtotal,int unknownItemCount,String budgetStatus) {}
    public record Preview(int schemaVersion,Source source,List<Line> lines) {}
}
