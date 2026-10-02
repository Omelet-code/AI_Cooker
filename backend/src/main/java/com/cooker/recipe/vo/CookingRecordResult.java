package com.cooker.recipe.vo;

/** HTTP 状态与稳定事件分开，避免把是否首次写入混进契约响应。 */
public record CookingRecordResult(CookingRecord record, boolean created) { }
