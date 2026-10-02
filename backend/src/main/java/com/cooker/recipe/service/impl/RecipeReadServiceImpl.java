package com.cooker.recipe.service.impl;

import com.cooker.common.ApiException;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.recipe.dto.RecipeCardRow;
import com.cooker.recipe.mapper.RecipeReadMapper;
import com.cooker.recipe.service.RecipeReadService;
import com.cooker.recipe.vo.*;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeReadServiceImpl implements RecipeReadService {
    private final CurrentUserService currentUser;
    private final RecipeReadMapper mapper;

    public RecipeReadServiceImpl(CurrentUserService currentUser, RecipeReadMapper mapper) {
        this.currentUser = currentUser; this.mapper = mapper;
    }

    @Override @Transactional(readOnly = true)
    public PageView<RecipeCard> list(int page, int size, String keyword) {
        validatePage(page, size);
        Long userId = currentUser.requireUserId();
        String escaped = escapeKeyword(keyword);
        long total = mapper.countVisible(userId, escaped);
        // 收藏状态与卡片一次 JOIN 取得，分页数量不增加 SQL 次数。
        var items = mapper.listVisible(userId, escaped, size, offset(page, size))
                .stream().map(RecipeReadServiceImpl::card).toList();
        return new PageView<>(items, page, size, total);
    }

    @Override @Transactional(readOnly = true)
    public RecipeDetail detail(Long recipeId) {
        Long userId = currentUser.requireUserId();
        RecipeCardRow row = mapper.findVisible(userId, recipeId);
        if (row == null) throw notFound();
        // 详情使用固定三条子项 SQL；不按原料、步骤逐条查询，避免 N+1。
        var ingredients = mapper.ingredients(recipeId).stream()
                .map(item -> new IngredientView(item.name, item.rawLine, decimal(item.amountMin),
                        decimal(item.amountMax), item.unitLabel, Boolean.TRUE.equals(item.optional))).toList();
        var steps = mapper.steps(recipeId).stream()
                .map(item -> new StepView(item.stepNo, item.content)).toList();
        var tools = mapper.tools(recipeId).stream()
                .map(item -> new ToolView(item.name, Boolean.TRUE.equals(item.optional))).toList();
        return new RecipeDetail(card(row), row.servings, ingredients, steps, tools, row.additionalContent);
    }

    @Override @Transactional(readOnly = true)
    public void requireVisible(Long recipeId) {
        if (mapper.findVisible(currentUser.requireUserId(), recipeId) == null) throw notFound();
    }

    public static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 50)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数不合法");
    }

    public static long offset(int page, int size) { return ((long) page - 1) * size; }

    public static RecipeCard card(RecipeCardRow row) {
        return new RecipeCard(row.id.toString(), row.name, row.categoryCode, row.summary,
                row.difficulty, row.estimatedMinutes, Boolean.TRUE.equals(row.isFavorite));
    }

    private String escapeKeyword(String keyword) {
        if (keyword == null) return null;
        String value = keyword.strip();
        if (value.codePointCount(0, value.length()) > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "菜名关键字最多 100 字符");
        if (value.isEmpty()) return null;
        // 先转义反斜杠，再转义 LIKE 通配符；参数仍通过 MyBatis 绑定，避免 SQL 拼接。
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RECIPE_NOT_FOUND", "菜谱不存在或不可见");
    }
}
