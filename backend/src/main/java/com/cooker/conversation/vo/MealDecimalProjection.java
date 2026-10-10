package com.cooker.conversation.vo;

import com.cooker.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** 厨房餐单/指导专属输出边界：精确十进制，历史响应只复制不写回。 */
public final class MealDecimalProjection {
    private static final Set<String> DECIMALS=Set.of("amountMin","amountMax","referenceAmount","budgetYuan");
    private static final Set<String> IDS=Set.of("recipeId","recipeIngredientId","ingredientId","sourceRecipeId","sourceDocumentId","stepId","toolId");
    private static final Pattern PLAIN=Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern NUMBER=Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    // 沿用餐单/指导结果512KiB上限；具体方案256KiB仍由原编排/持久化校验。
    private static final int MAX_BYTES=524288;
    private MealDecimalProjection(){}
    public static JsonNode wire(JsonNode original){return project(original,true);}
    public static JsonNode display(JsonNode original){return project(original,false);}
    private static JsonNode project(JsonNode original,boolean ids){
        bounded(original);var budget=new Budget();JsonNode result=copy(original,null,ids,budget);bounded(result);return result;
    }
    private static JsonNode copy(JsonNode node,String field,boolean ids,Budget budget){
        if(node.isObject()){var result=JsonNodeFactory.instance.objectNode();node.fields().forEachRemaining(e->result.set(e.getKey(),copy(e.getValue(),e.getKey(),ids,budget)));return result;}
        if(node.isArray()){var result=JsonNodeFactory.instance.arrayNode();node.forEach(child->result.add(copy(child,null,ids,budget)));return result;}
        if(node.isNull())return node;
        if(DECIMALS.contains(field==null?"":field))return TextNode.valueOf(decimal(node,budget));
        if(ids&&IDS.contains(field==null?"":field))return TextNode.valueOf(node.asText());
        return node.deepCopy();
    }
    private static String decimal(JsonNode node,Budget budget){
        if(node.isTextual()&&PLAIN.matcher(node.textValue()).matches()){
            budget.use(node.textValue().length());return node.textValue();
        }
        BigDecimal number;
        try{
            if(node.isTextual()){
                String text=node.textValue();if(text.length()>MAX_BYTES)throw limit();if(!NUMBER.matcher(text).matches())throw invalid();number=new BigDecimal(text);
            }else if(node.isNumber()&&!node.isDouble()&&!node.isFloat())number=node.decimalValue();
            else throw invalid();
        }catch(NumberFormatException e){throw invalid();}
        long scale=number.scale(),precision=number.precision();
        long length=scale<=0?(number.signum()==0?1:precision-scale):Math.max(precision+1,scale+2);
        if(number.signum()<0)length++;
        // 先算展开长度并累计预算，不能先分配十亿位字符串再检查大小。
        budget.use(length);return number.toPlainString();
    }
    private static void bounded(JsonNode node){if(node.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw limit();}
    private static ApiException limit(){return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"RESOURCE_LIMIT","餐单数值显示超出资源上限");}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_RESPONSE","餐单数值格式不合法");}
    private static final class Budget {long used;void use(long length){if(length<0||length>MAX_BYTES||used>MAX_BYTES-length)throw limit();used+=length;}}
}
