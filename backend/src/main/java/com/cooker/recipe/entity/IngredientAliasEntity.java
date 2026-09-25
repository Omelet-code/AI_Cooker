package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ingredient_alias")
public class IngredientAliasEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long ingredientId;
    public String aliasName;
}
