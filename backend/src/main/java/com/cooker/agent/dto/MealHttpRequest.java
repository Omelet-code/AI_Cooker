package com.cooker.agent.dto;

import com.cooker.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 手工核验原JSON类型；不让全局标量转换把4.9转成4或把数值ID转字符串。 */
public record MealHttpRequest(String requestId,String content,Integer diningCount,Integer expectedVersion,String optionId) {
    public static MealHttpRequest parse(JsonNode node,String type){
        if(node==null||!node.isObject())throw invalid();
        Set<String> allowed=new HashSet<>(List.of("requestId"));
        if(!"SELECT".equals(type)){allowed.add("content");allowed.add("diningCount");}else allowed.add("optionId");
        if(!"CREATE".equals(type))allowed.add("expectedVersion");
        node.fieldNames().forEachRemaining(field->{if(!allowed.contains(field))throw invalid();});
        String request=text(node,"requestId");try{if(!UUID.fromString(request).toString().equals(request))throw invalid();}catch(Exception e){throw invalid();}
        String content="SELECT".equals(type)?"":text(node,"content");
        if(!"SELECT".equals(type)&&(content.isBlank()||content.codePointCount(0,content.length())>4000))throw invalid();
        Integer count=integer(node,"diningCount",false),version="CREATE".equals(type)?null:integer(node,"expectedVersion",true);
        String option="SELECT".equals(type)?text(node,"optionId"):null;
        if(option!=null)com.cooker.conversation.ConversationIdParser.parse(option);
        return new MealHttpRequest(request,content,count,version,option);
    }
    private static String text(JsonNode node,String field){if(!node.path(field).isTextual())throw invalid();return node.path(field).textValue();}
    private static Integer integer(JsonNode node,String field,boolean required){var value=node.get(field);if(value==null||value.isNull()){if(required)throw invalid();return null;}if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<=0)throw invalid();return value.intValue();}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","餐单请求参数不合法");}
    @Override public String toString(){return "MealHttpRequest[content=[已隐藏]]";}
}
