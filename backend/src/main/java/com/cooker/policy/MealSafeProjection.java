package com.cooker.policy;

import com.cooker.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** 餐单专属显示副本；原始事实、资格和指纹仍由事实服务负责。 */
public final class MealSafeProjection {
    private static final Pattern INJECTION=Pattern.compile("忽略.{0,8}(指令|规则)|系统指令|开发者指令");
    private static final Pattern MEDICAL=Pattern.compile("治疗|治愈|诊断|处方|药物|根治|包治|疗效");
    private static final Pattern HTML=Pattern.compile("<[^>]*>");
    private static final Pattern CONTROL=Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern SCHEME=Pattern.compile("(?i)(javascript|data|vbscript):");
    private static final Pattern LINK=Pattern.compile("\\[([^\\[\\]\\r\\n]*)\\]\\((https?://[^\\s]*?)\\)",Pattern.CASE_INSENSITIVE);
    // 裸 URL 只匹配地址字符，中文步骤和数量不得被当作地址一起删除。
    private static final Pattern URL=Pattern.compile("(?i)(?:https?://|www\\.)[a-z0-9._~:/?#@!$&'()*+,;=%-]+");
    private final MealPolicy safety;
    public MealSafeProjection(MealPolicy safety){this.safety=safety;}
    public JsonNode project(JsonNode original){return copy(original,"facts",null);}
    private JsonNode copy(JsonNode value,String path,Long recipe){
        if(value.isObject()){
            Long owner=recipe;
            if(value.path("recipeId").canConvertToLong())owner=value.path("recipeId").longValue();
            else if(value.path("facts").path("recipeId").canConvertToLong())owner=value.path("facts").path("recipeId").longValue();
            else if(value.path("sourceRecipeId").canConvertToLong())owner=value.path("sourceRecipeId").longValue();
            var result=JsonNodeFactory.instance.objectNode();final Long id=owner;
            value.fields().forEachRemaining(e->result.set(e.getKey(),copy(e.getValue(),path+"."+e.getKey(),id)));return result;
        }
        if(value.isArray()){var result=JsonNodeFactory.instance.arrayNode();for(int i=0;i<value.size();i++)result.add(copy(value.get(i),path+"["+i+"]",recipe));return result;}
        if(!value.isTextual())return value.deepCopy();
        String text=value.textValue();
        // 在投影前拒绝危险语义，不能通过删关键词或链接目标洗白。
        if(INJECTION.matcher(text).find())throw new Unsafe(recipe,path,"INJECTION");
        if(MEDICAL.matcher(text).find())throw new Unsafe(recipe,path,"MEDICAL");
        if(CONTROL.matcher(text).find())throw new Unsafe(recipe,path,"CONTROL");
        if(SCHEME.matcher(text).find())throw new Unsafe(recipe,path,"LINK_SCHEME");
        if(HTML.matcher(text).find())throw new Unsafe(recipe,path,"HTML");
        String projected=LINK.matcher(text).replaceAll("$1");projected=URL.matcher(projected).replaceAll("");
        try{safety.safeText(projected);}catch(ApiException e){throw new Unsafe(recipe,path,"TEXT_BOUNDARY");}
        return TextNode.valueOf(projected);
    }
    /** 异常仅携带受控定位信息，不携带触发正文。 */
    public static final class Unsafe extends ApiException {
        public final Long recipeId;public final String fieldPath,rule;
        private Unsafe(Long recipe,String path,String rule){super(HttpStatus.BAD_REQUEST,"UNSAFE_CONTENT","餐单内容无法安全展示");this.recipeId=recipe;this.fieldPath=path;this.rule=rule;}
    }
}
