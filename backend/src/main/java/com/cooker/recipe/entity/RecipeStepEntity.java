package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("recipe_step")
public class RecipeStepEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long recipeId;
    public Integer stepNo;
    public String content;
}
