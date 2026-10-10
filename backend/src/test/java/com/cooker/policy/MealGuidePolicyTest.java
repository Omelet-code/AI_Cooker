package com.cooker.policy;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MealGuidePolicyTest {
    final ObjectMapper json=new ObjectMapper();final MealGuidePolicy policy=new MealGuidePolicy(new MealPolicy());
    JsonNode facts()throws Exception{return json.readTree("[{\"recipeId\":\"10\",\"steps\":[{\"stepNo\":1,\"content\":\"180℃烤10分钟\"},{\"stepNo\":2,\"content\":\"完成\"}],\"ingredients\":[{\"recipeIngredientId\":\"5\",\"rawLine\":\"盐1 g\",\"amountMin\":\"1\",\"amountMax\":\"1\",\"unitLabel\":\"g\"}]}]");}
    ObjectNode content()throws Exception{return (ObjectNode)json.readTree("{\"overview\":\"先确认材料\",\"preparation\":[{\"text\":\"依原量准备1克\",\"evidence\":[{\"recipeId\":\"10\",\"stepNos\":[],\"ingredientIds\":[\"5\"]}]}],\"recipes\":[{\"recipeId\":\"10\",\"explanations\":[{\"text\":\"按原步骤在180℃烤10分钟\",\"evidence\":[{\"recipeId\":\"10\",\"stepNos\":[1,2],\"ingredientIds\":[]}]}]}],\"cookingOrder\":[{\"text\":\"依原步骤准备\",\"evidence\":[{\"recipeId\":\"10\",\"stepNos\":[1],\"ingredientIds\":[]}]}],\"cautions\":[],\"optionalSuggestions\":[]}");}
    @Test void 真实引用完整覆盖与同义单位数值通过()throws Exception{assertThat(policy.validate(content().toString(),facts(),json.createObjectNode())).isNotNull();}
    @Test void 小数步骤错材料漏步骤未知字段与虚构数字失败()throws Exception {
        for(String wrong:List.of("999克","999℃","999分钟","一百克","价格10元")){var content=content();((ObjectNode)content.path("preparation").get(0)).put("text",wrong);assertThatThrownBy(()->policy.validate(content.toString(),facts(),json.createObjectNode())).isInstanceOf(com.cooker.common.ApiException.class);}
        var omitted=content();((ArrayNode)omitted.path("recipes").get(0).path("explanations").get(0).path("evidence").get(0).path("stepNos")).remove(1);assertThatThrownBy(()->policy.validate(omitted.toString(),facts(),json.createObjectNode())).isInstanceOf(com.cooker.common.ApiException.class);
        var fraction=content();((ArrayNode)fraction.path("cookingOrder").get(0).path("evidence").get(0).path("stepNos")).set(0,json.getNodeFactory().numberNode(1.5));assertThatThrownBy(()->policy.validate(fraction.toString(),facts(),json.createObjectNode())).isInstanceOf(com.cooker.common.ApiException.class);
        var unknown=content();unknown.put("materials","模型权威材料");assertThatThrownBy(()->policy.validate(unknown.toString(),facts(),json.createObjectNode())).isInstanceOf(com.cooker.common.ApiException.class);
    }
    @Test void 原步骤缺失必须全部材料引用且明示不编原做法()throws Exception {
        var facts=facts();((ObjectNode)facts.get(0)).putArray("steps");var content=content();
        for(String key:List.of("preparation","cookingOrder")){var ref=(ObjectNode)content.path(key).get(0).path("evidence").get(0);ref.putArray("stepNos");ref.putArray("ingredientIds").add("5");((ObjectNode)content.path(key).get(0)).put("text","确认原材料。");}
        var explanation=(ObjectNode)content.path("recipes").get(0).path("explanations").get(0);var ref=(ObjectNode)explanation.path("evidence").get(0);ref.putArray("stepNos");ref.putArray("ingredientIds").add("5");explanation.put("text","原步骤缺失，请确认做法。");assertThat(policy.validate(content.toString(),facts,json.createObjectNode())).isNotNull();explanation.put("text","自行按想法操作。");assertThatThrownBy(()->policy.validate(content.toString(),facts,json.createObjectNode())).isInstanceOf(com.cooker.common.ApiException.class);
    }
}
