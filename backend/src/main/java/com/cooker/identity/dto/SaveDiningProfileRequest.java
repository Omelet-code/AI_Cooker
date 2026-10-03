package com.cooker.identity.dto;

import com.cooker.common.ApiException;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** 只在档案接口解析原始节点，防止全局 Jackson 的宽松转换改变人数语义。 */
public record SaveDiningProfileRequest(@Positive(message = "默认用餐人数必须为正整数") Integer defaultDiningCount) {
    public static SaveDiningProfileRequest fromJson(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("defaultDiningCount"))
            throw invalid();
        JsonNode value = body.get("defaultDiningCount");
        if (value.isNull()) return new SaveDiningProfileRequest(null);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)
            throw invalid();
        return new SaveDiningProfileRequest(value.intValue());
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
    }
}
