package com.cooker.shopping.service;

import com.cooker.common.ApiException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 模块私有严格解析与规范化；十进制语义摘要不包含对象属性顺序或展示格式。 */
public final class ShoppingJson {
    public static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    private ShoppingJson(){}
    public static JsonNode parse(String raw){try{if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>1048576)throw invalid();return JSON.readTree(raw);}catch(Exception e){throw invalid();}}
    public static void keys(JsonNode node,String...allowed){if(node==null||!node.isObject())throw invalid();var keys=Set.of(allowed);node.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    public static String encode(Object value){try{String raw=JSON.writeValueAsString(value);if(raw.getBytes(StandardCharsets.UTF_8).length>1048576)throw invalid();return raw;}catch(Exception e){throw invalid();}}
    public static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(canonical(JSON.valueToTree(value))).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("运行环境不支持摘要");}}
    private static JsonNode canonical(JsonNode node){
        if(node.isObject()){var out=JSON.createObjectNode();var names=new ArrayList<String>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);names.forEach(k->out.set(k,canonical(node.get(k))));return out;}
        if(node.isArray()){var out=JSON.createArrayNode();node.forEach(n->out.add(canonical(n)));return out;}
        if(node.isNumber())return TextNode.valueOf(node.decimalValue().stripTrailingZeros().toPlainString());return node.deepCopy();
    }
    public static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
    public static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","采购请求的字段、数量或单位不合法");}
    public static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"采购来源或同键请求内容已变化");}
}
