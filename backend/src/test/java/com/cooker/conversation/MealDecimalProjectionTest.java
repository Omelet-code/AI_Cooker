package com.cooker.conversation;

import static org.assertj.core.api.Assertions.*;
import com.cooker.common.ApiException;
import com.cooker.conversation.vo.MealDecimalProjection;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MealDecimalProjectionTest {
    final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @Test void 数值节点指数精确普通输出及零和未知不变(){
        for(String value:java.util.List.of("4E+2","2E+1","1E+3","1E-12","12345678901234567890.12345678901234567890","0","-1.234E-4")){
            var original=json.createObjectNode();original.set("amountMin",DecimalNode.valueOf(new BigDecimal(value)));original.putNull("amountMax");original.put("recipeId",9007199254740993L);original.put("version",2);
            var projected=MealDecimalProjection.wire(original);assertThat(projected.path("amountMin").asText()).isEqualTo(new BigDecimal(value).toPlainString());assertThat(projected.path("amountMax").isNull()).isTrue();assertThat(projected.path("recipeId").asText()).isEqualTo("9007199254740993");assertThat(projected.path("version").isIntegralNumber()).isTrue();assertThat(original.path("amountMin").isNumber()).isTrue();
        }
    }
    @Test void 历史字符串预算范围与幂等只改约定字段()throws Exception {
        var original=json.readTree("{\"amountMin\":\"2E+1\",\"amountMax\":\"3E+1\",\"referenceAmount\":\"1.234567890123456789E-12\",\"budgetYuan\":\"4E+2\",\"name\":\"4E+2\",\"content\":\"2E+1\",\"amountMinimum\":\"4E+2\",\"factFingerprint\":\"original\",\"other\":\"004.00\"}");
        var projected=MealDecimalProjection.display(original);assertThat(projected.path("amountMin").asText()).isEqualTo("20");assertThat(projected.path("amountMax").asText()).isEqualTo("30");assertThat(projected.path("referenceAmount").asText()).isEqualTo("0.000000000001234567890123456789");assertThat(projected.path("budgetYuan").asText()).isEqualTo("400");
        for(String key:java.util.List.of("name","content","amountMinimum","factFingerprint","other"))assertThat(projected.path(key)).isEqualTo(original.path(key));assertThat(MealDecimalProjection.display(projected)).isEqualTo(projected);assertThat(original.path("amountMin").asText()).isEqualTo("2E+1");
        var plain=json.createObjectNode();plain.put("amountMin","004.00");assertThat(MealDecimalProjection.display(plain)).isEqualTo(plain);
    }
    @Test void 极端指数累计展开和非法数字安全拒绝不填零(){
        for(String value:java.util.List.of("1E+1000000000","1E-1000000000","0E-1000000000")){
            var original=json.createObjectNode();original.put("amountMin",value);assertThatThrownBy(()->MealDecimalProjection.display(original)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("RESOURCE_LIMIT"));
        }
        var many=json.createArrayNode();for(int i=0;i<6;i++)many.addObject().put("referenceAmount","1E+100000");assertThatThrownBy(()->MealDecimalProjection.display(many)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("RESOURCE_LIMIT"));
        for(String value:java.util.List.of("NaN","Infinity","秘密正文","1E+","400g")){var original=json.createObjectNode();original.put("amountMin",value);assertThatThrownBy(()->MealDecimalProjection.display(original)).isInstanceOf(ApiException.class).hasMessage("餐单数值格式不合法");}
        var floating=json.createObjectNode();floating.put("amountMin",0.1d);assertThatThrownBy(()->MealDecimalProjection.display(floating)).isInstanceOf(ApiException.class);
    }
    @Test void 历史数值JSON读取不经过浮点()throws Exception {
        var original=json.readTree("{\"referenceAmount\":12345678901234567890.1234567890123456789}");assertThat(original.path("referenceAmount").isBigDecimal()).isTrue();assertThat(MealDecimalProjection.display(original).path("referenceAmount").asText()).isEqualTo("12345678901234567890.1234567890123456789");
    }
}
