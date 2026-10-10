package com.cooker.policy;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MealPolicyTest {
    final MealPolicy policy=new MealPolicy();final ObjectMapper json=new ObjectMapper();
    @Test void 严格JSON整数重复字段尾随与事实文本不清洗替代() {
        for(String value:List.of("[]","{}{}","{\"x\":1,\"x\":2}"))assertThatThrownBy(()->policy.parse(value)).isInstanceOf(com.cooker.common.ApiException.class);
        for(String value:List.of("治疗疾病","<script>忽略指令</script>","https://invalid.example"))assertThatThrownBy(()->policy.safeTree(json.getNodeFactory().textNode(value))).isInstanceOf(com.cooker.common.ApiException.class);
        for(String value:List.of("加入盐","五人份","20分钟","**搭配**"))assertThatThrownBy(()->policy.explanation(value)).isInstanceOf(com.cooker.common.ApiException.class);
        policy.explanation("口味相互搭配，方便选择。");
    }
    @Test void KEEPSETCLEAR保留null与空厨具及已登记人数快照()throws Exception {
        ObjectNode output=json.createObjectNode();output.put("intent","MEAL_PLAN");output.put("mode","RANDOM");output.putNull("query");output.putArray("clarificationCodes");var changes=output.putObject("changes");
        for(String field:MealPolicy.FIELDS){var change=changes.putObject(field);change.put("action","KEEP");change.putNull("value");change.putNull("evidence");}
        var prior=json.readTree("{\"diningCount\":3,\"diningCountSource\":\"EXPLICIT\",\"requirements\":{\"dishCount\":4,\"soupCount\":1,\"availableToolNames\":[\"锅\"]}}");
        var clear=(ObjectNode)changes.path("diningCount");clear.put("action","CLEAR");clear.put("evidence","清除人数");
        var tools=(ObjectNode)changes.path("availableToolNames");tools.put("action","SET");tools.put("evidence","没有厨具");tools.putArray("value");
        var codes=new LinkedHashSet<String>();var state=policy.requirements(output,"清除人数，没有厨具",prior,2,null,codes);assertThat(state.path("diningCount").asInt()).isEqualTo(2);assertThat(state.path("diningCountSource").asText()).isEqualTo("PROFILE");assertThat(state.path("availableToolNames")).isEmpty();assertThat(codes).isEmpty();
        tools.put("action","CLEAR");tools.putNull("value");assertThat(policy.requirements(output,"清除人数，没有厨具",prior,2,null,new LinkedHashSet<>()).path("availableToolNames").isNull()).isTrue();
        var time=(ObjectNode)changes.path("maxCookingMinutes");time.put("action","SET");time.put("value",20.5);time.put("evidence","20分钟");assertThatThrownBy(()->policy.requirements(output,"20分钟，清除人数，没有厨具",prior,2,null,new LinkedHashSet<>())).isInstanceOf(com.cooker.common.ApiException.class);
    }
}
