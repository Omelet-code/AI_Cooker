package com.cooker.recipe.dto;

import java.time.OffsetDateTime;

/** 数据库事件投影；只在 Mapper 与 Service 之间传递。 */
public class CookingRecordRow {
    public Long id;
    public Long recipeId;
    public Integer rating;
    public OffsetDateTime cookedAt;
}
