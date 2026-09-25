package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cooker.recipe.mapper.JsonbStringTypeHandler;
import java.time.OffsetDateTime;

@TableName(value = "recipe_source_document", autoResultMap = true)
public class RecipeSourceDocumentEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public Long sourceId;
    public String sourceVersion;
    public String contentHash;
    public String rawMarkdown;
    public String parseStatus;
    @TableField(typeHandler = JsonbStringTypeHandler.class) public String parseWarnings;
    public String failureMessage;
    public Long importBatchId;
    public OffsetDateTime importedAt;
}
