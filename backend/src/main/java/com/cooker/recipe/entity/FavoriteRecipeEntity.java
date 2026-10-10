package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("favorite_recipe")
public class FavoriteRecipeEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public Long recipeId;
    public OffsetDateTime createdAt;
}
