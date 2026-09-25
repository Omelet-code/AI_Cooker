package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("recipe_tool")
public class RecipeToolEntity {
    @TableId(value = "recipe_id", type = IdType.INPUT) public Long recipeId;
    public Long toolId;
    public Boolean optional;
}
