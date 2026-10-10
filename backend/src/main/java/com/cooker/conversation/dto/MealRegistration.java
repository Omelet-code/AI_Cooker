package com.cooker.conversation.dto;

/** 原文/快照只作内部传输，避免toString记录用户内容。 */
public record MealRegistration(long actionId, long mealId, int version, long seed,
        Integer profileDiningCount, String previousJson, boolean created, String status) {
    @Override public String toString() { return "MealRegistration[actionId=" + actionId + ",version=" + version + "]"; }
}
