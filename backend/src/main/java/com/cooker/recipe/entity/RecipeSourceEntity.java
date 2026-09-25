package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("recipe_source")
public class RecipeSourceEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String sourceType;
    public String sourceKey;
    public String sourceUri;
    public Long ownerUserId;
    public String visibility;
}
