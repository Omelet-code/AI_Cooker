package com.cooker.recipe.dto;

import java.util.List;

/**
 * `recipe` 模块向 `retrieval` 模块暴露的只读事实快照。
 * 检索模块只能消费此 DTO，不能绕过 Service 读取菜谱 Mapper 或 Entity。
 *
 * @param recipeId         菜谱主键，检索命中后据此回查真实菜谱
 * @param sourceDocumentId 当前事实对应的原始 Markdown 版本
 * @param sourceType       来源类型，区分系统快照与用户自定义菜谱
 * @param ownerUserId      私有菜谱所有者；系统菜谱为空
 * @param visibility       PUBLIC 或 PRIVATE，检索前必须据此过滤
 * @param name             菜名，写入每个块的上下文前缀
 * @param categoryCode     分类编码，写入每个块的上下文前缀
 * @param summary          简介，可为空
 * @param difficulty       烹饪难度 1～5，可为空
 * @param estimatedMinutes 预计用时（分钟），可为空
 * @param servings         份数，可为空
 * @param caloriesKcal     热量（千卡），可为空
 * @param ingredientNames  按原文顺序排列的原料名，用于 ingredients 块
 * @param toolNames        按名称排序的厨具名，用于 tools 块
 * @param steps            按步骤号排列的步骤正文，用于 steps 块
 */
public record RecipeIndexProjection(
        Long recipeId,
        Long sourceDocumentId,
        String sourceType,
        Long ownerUserId,
        String visibility,
        String name,
        String categoryCode,
        String summary,
        Integer difficulty,
        Integer estimatedMinutes,
        Integer servings,
        Integer caloriesKcal,
        List<String> ingredientNames,
        List<String> toolNames,
        List<String> steps) {
}
