package com.cooker.recipe.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cooker.recipe.mapper.JsonbStringTypeHandler;
import java.time.OffsetDateTime;

@TableName(value = "recipe_import_batch", autoResultMap = true)
public class RecipeImportBatchEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String sourceType;
    public String sourceVersion;
    public String status;
    public Integer candidateCount;
    public Integer succeededCount;
    public Integer skippedCount;
    public Integer warningCount;
    public Integer failedCount;
    @TableField(typeHandler = JsonbStringTypeHandler.class) public String errorSummary;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
}
