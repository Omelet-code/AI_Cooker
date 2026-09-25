package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("recipe")
public class RecipeEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long sourceId;
    public Long sourceDocumentId;
    public String name;
    public String categoryCode;
    public String summary;
    public Integer difficulty;
    public Integer estimatedMinutes;
    public Integer servings;
    public Integer caloriesKcal;
    public String additionalContent;
}
