package com.cooker.policy;

import com.cooker.common.ApiException;
import org.springframework.http.HttpStatus;

/** 服务端有限诊断，不携带任意模型字段、正文、异常链或消息。 */
public final class MealGuideRejected extends ApiException {
    public enum Rule { JSON_OBJECT, OBJECT_FIELDS, ARRAY_BOUNDS, TEXT_TYPE, TEXT_LENGTH,
        TEXT_FORMAT, TEXT_SAFETY, MEAL_PROMISE, RECIPE_ID, RECIPE_REFERENCE,
        RECIPE_COVERAGE, STEP_TYPE, STEP_REFERENCE, INGREDIENT_REFERENCE,
        DUPLICATE_REFERENCE, EMPTY_EVIDENCE, STEP_COVERAGE, INGREDIENT_COVERAGE,
        MISSING_STEPS_NOTICE, NUMBER_EVIDENCE, PRICE_NUTRITION, RESPONSE_LIMIT }
    private final Rule rule;
    private final String fieldPath;
    MealGuideRejected(Rule rule,String path) {
        super(rule==Rule.RESPONSE_LIMIT?HttpStatus.PAYLOAD_TOO_LARGE:HttpStatus.BAD_REQUEST,
            rule==Rule.RESPONSE_LIMIT?"RESOURCE_LIMIT":rule==Rule.TEXT_SAFETY?"UNSAFE_CONTENT":"MODEL_INVALID_RESPONSE",
            rule==Rule.RESPONSE_LIMIT?"完整指导超出内容上限":rule==Rule.TEXT_SAFETY?"餐单内容无法安全展示":"模型返回内容无效");
        // 路径只由Policy的固定字段和有限数组索引拼接，额外检查防止后续误传模型键。
        if(path==null||path.length()>240||!path.matches("guide(?:\\.(?:overview|preparation|recipes|cookingOrder|cautions|optionalSuggestions|recipeId|explanations|text|evidence|stepNos|ingredientIds|recipeIds)|\\[[0-9]{1,6}\\])*") )
            throw new IllegalArgumentException("指导诊断路径不是服务端固定字段");
        this.rule=rule;this.fieldPath=path;
    }
    public Rule rule(){return rule;}
    public String fieldPath(){return fieldPath;}
}
