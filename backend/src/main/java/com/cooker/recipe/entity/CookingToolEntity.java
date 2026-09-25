package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("cooking_tool")
public class CookingToolEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String canonicalName;
}
