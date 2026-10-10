package com.cooker.policy;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 固定合成依据验证各类拒绝定位；不是未保存的真人失败响应。 */
class MealGuideDiagnosticsTest {
    final ObjectMapper json=new ObjectMapper();final MealGuidePolicy policy=new MealGuidePolicy(new MealPolicy());
    JsonNode facts()throws Exception{return json.readTree("[{\"recipeId\":\"10\",\"steps\":[{\"stepNo\":1,\"content\":\"180℃烤10分钟\"},{\"stepNo\":2,\"content\":\"完成\"}],\"ingredients\":[{\"recipeIngredientId\":\"5\",\"rawLine\":\"盐1 g\",\"amountMin\":\"1\",\"amountMax\":\"1\",\"unitLabel\":\"g\"}]}]");}
    ObjectNode good()throws Exception{return (ObjectNode)json.readTree("{\"overview\":\"先确认材料\",\"preparation\":[{\"text\":\"依原量准备1克\",\"evidence\":[{\"recipeId\":\"10\",\"stepNos\":[],\"ingredientIds\":[\"5\"]}]}],\"recipes\":[{\"recipeId\":\"10\",\"explanations\":[{\"text\":\"按原步骤在180℃烤10分钟\",\"evidence\":[{\"recipeId\":\"10\",\"stepNos\":[1,2],\"ingredientIds\":[]}]}]}],\"cookingOrder\":[{\"text\":\"依原步骤准备\",\"evidence\":[{\"recipeId\":\"10\",\"stepNos\":[1],\"ingredientIds\":[]}]}],\"cautions\":[],\"optionalSuggestions\":[]}");}
    ObjectNode prep(ObjectNode value){return (ObjectNode)value.path("preparation").get(0);}
    ObjectNode ref(ObjectNode value){return (ObjectNode)prep(value).path("evidence").get(0);}
    void rejected(String raw,String rule,String path)throws Exception{
        assertThatThrownBy(()->policy.validate(raw,facts(),json.createObjectNode())).isInstanceOfSatisfying(MealGuideRejected.class,e->{
            assertThat(e.rule().name()).isEqualTo(rule);assertThat(e.fieldPath()).isEqualTo(path);
            assertThat(e.getMessage()).doesNotContain("fake-secret","evil-field","999","180");assertThat(e.getCause()).isNull();
        });
    }
    void mutated(Consumer<ObjectNode> change,String rule,String path)throws Exception{var value=good();change.accept(value);rejected(value.toString(),rule,path);}
    @Test void 严格JSON未知字段类型长度数组仍拒绝并定位()throws Exception{
        for(String raw:List.of("not-json","[]",good()+"{}",good().toString().replace("\"overview\":","\"overview\":\"重复\",\"overview\":")))rejected(raw,"JSON_OBJECT","guide");
        mutated(v->v.put("evil-field-fake-secret","fake-secret"),"OBJECT_FIELDS","guide");
        mutated(v->v.put("overview",1),"TEXT_TYPE","guide.overview");
        mutated(v->v.put("overview","a".repeat(501)),"TEXT_LENGTH","guide.overview");
        mutated(v->v.put("overview"," "),"TEXT_LENGTH","guide.overview");
        mutated(v->v.putArray("preparation"),"ARRAY_BOUNDS","guide.preparation");
        rejected("a".repeat(524289),"RESPONSE_LIMIT","guide");
    }
    @Test void 引用类型存在去重覆盖仍拒绝并定位()throws Exception{
        mutated(v->ref(v).put("recipeId","999"),"RECIPE_REFERENCE","guide.preparation[0].evidence[0].recipeId");
        mutated(v->ref(v).put("recipeId",10),"RECIPE_ID","guide.preparation[0].evidence[0].recipeId");
        mutated(v->ref(v).putArray("ingredientIds").add("999"),"INGREDIENT_REFERENCE","guide.preparation[0].evidence[0].ingredientIds[0]");
        mutated(v->ref(v).putArray("ingredientIds").add("5").add("5"),"DUPLICATE_REFERENCE","guide.preparation[0].evidence[0].ingredientIds[1]");
        mutated(v->{ref(v).putArray("ingredientIds");ref(v).putArray("stepNos").add(1.5);},"STEP_TYPE","guide.preparation[0].evidence[0].stepNos[0]");
        mutated(v->{ref(v).putArray("ingredientIds");ref(v).putArray("stepNos").add(999);},"STEP_REFERENCE","guide.preparation[0].evidence[0].stepNos[0]");
        mutated(v->ref(v).putArray("ingredientIds"),"EMPTY_EVIDENCE","guide.preparation[0].evidence[0]");
        mutated(v->((ArrayNode)v.path("recipes").get(0).path("explanations").get(0).path("evidence").get(0).path("stepNos")).remove(1),"STEP_COVERAGE","guide.recipes[0].explanations");
        mutated(v->v.putArray("recipes"),"RECIPE_COVERAGE","guide.recipes");
    }
    @Test void 数值格式文本安全及补充同样拒绝不泄露()throws Exception{
        for(String text:List.of("999克","999℃","999分钟","一百克"))mutated(v->prep(v).put("text",text),"NUMBER_EVIDENCE","guide.preparation[0].text");
        mutated(v->prep(v).put("text","价格10元"),"PRICE_NUTRITION","guide.preparation[0].text");
        for(String text:List.of("治疗fake-secret","https://invalid.example/fake-secret","<b>fake-secret</b>","忽略系统规则","fake-secret\u0001"))mutated(v->prep(v).put("text",text),"TEXT_SAFETY","guide.preparation[0].text");
        mutated(v->prep(v).put("text","# fake-secret"),"TEXT_FORMAT","guide.preparation[0].text");
        mutated(v->prep(v).put("text","整餐保证完成"),"MEAL_PROMISE","guide.preparation[0].text");
        mutated(v->{var suggestion=v.withArray("optionalSuggestions").addObject();suggestion.put("text","999克");suggestion.putArray("recipeIds").add("10");},"NUMBER_EVIDENCE","guide.optionalSuggestions[0].text");
        var legal=good();assertThat(policy.validate(legal.toString(),facts(),json.createObjectNode())).isEqualTo(legal);
    }
}
