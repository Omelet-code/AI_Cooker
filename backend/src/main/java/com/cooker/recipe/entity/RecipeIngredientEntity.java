package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("recipe_ingredient")
public class RecipeIngredientEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long recipeId;
    public Long ingredientId;
    public String rawName;
    public BigDecimal amountMin;
    public BigDecimal amountMax;
    public String unitCode;
    public String unitLabel;
    public Boolean optional;
    public String rawLine;
    public Integer sortOrder;
}
