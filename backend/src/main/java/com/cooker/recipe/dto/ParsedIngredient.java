package com.cooker.recipe.dto;

import java.math.BigDecimal;

/** 解析器输出的原料事实；无法可靠结构化的数量仍由 rawLine 完整保留。 */
public record ParsedIngredient(
        String rawName,
        BigDecimal amountMin,
        BigDecimal amountMax,
        String unitCode,
        String unitLabel,
        boolean optional,
        String rawLine,
        int sortOrder) {
}
