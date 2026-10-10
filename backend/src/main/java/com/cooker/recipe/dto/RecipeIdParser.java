package com.cooker.recipe.dto;

import com.cooker.common.ApiException;
import org.springframework.http.HttpStatus;

/** URL 中 ID 保持十进制字符串，入 Service 前再校验并转为数据库 BIGINT。 */
public final class RecipeIdParser {
    private RecipeIdParser() { }
    public static Long parse(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]*")) throw invalid();
        try { return Long.valueOf(raw); }
        catch (NumberFormatException e) { throw invalid(); }
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "菜谱 ID 不合法");
    }
}
