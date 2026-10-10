package com.cooker.recipe.dto;

/** Mapper 专用投影，既不作为 HTTP 响应，也不暴露数据库 Entity。 */
public class RecipeCardRow {
    public Long id;
    public String name;
    public String categoryCode;
    public String summary;
    public Integer difficulty;
    public Integer estimatedMinutes;
    public Boolean isFavorite;
    public Integer servings;
    public String additionalContent;
}
