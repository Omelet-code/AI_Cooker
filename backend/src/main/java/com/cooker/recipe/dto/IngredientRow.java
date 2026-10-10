package com.cooker.recipe.dto;

import java.math.BigDecimal;

public class IngredientRow {
    public String name;
    public String rawLine;
    public BigDecimal amountMin;
    public BigDecimal amountMax;
    public String unitLabel;
    public Boolean optional;
}
