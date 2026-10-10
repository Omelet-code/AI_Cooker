package com.cooker.policy;

import com.cooker.common.ApiException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 严格结构和展示边界；证据引用仍不能证明自然语言理解必然正确。 */
@Component
public class MealPolicy {
    public static final List<String> FIELDS=List.of("dishCount","soupCount","diningCount","maxCookingMinutes","availableToolNames","excludedIngredientNames","availableIngredientNames","budgetYuan");
    private static final Set<String> CODES=Set.of("COUNT_REQUIRED","DINING_COUNT_REQUIRED","DINING_COUNT_CONFLICT","TIME_SCOPE_AMBIGUOUS","REQUEST_AMBIGUOUS");
    private static final Pattern UNSAFE=Pattern.compile("(?i)(https?://|www\\.|<[^>]*>|忽略.{0,8}(指令|规则)|系统指令|开发者指令|治疗|治愈|诊断|处方|药物|根治|包治|疗效|[\\p{Cntrl}&&[^\\n\\r\\t]])");
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ObjectNode parse(String input){try{JsonNode value=json.readTree(input);if(value==null||!value.isObject())throw invalid();return (ObjectNode)value;}catch(Exception e){throw invalid();}}
    public void keys(JsonNode object,String... allowed){if(!object.isObject())throw invalid();Set<String> expected=new HashSet<>(List.of(allowed));object.fieldNames().forEachRemaining(key->{if(!expected.remove(key))throw invalid();});if(!expected.isEmpty())throw invalid();}
    public ObjectNode requirements(ObjectNode output,String content,JsonNode previous,Integer profile,Integer formCount,Set<String> clarifications) {
        keys(output,"intent","mode","query","changes","clarificationCodes");
        if(!output.path("intent").isTextual()||!Set.of("MEAL_PLAN","CLARIFY","UNSUPPORTED").contains(output.path("intent").textValue()))throw invalid();
        var codes=output.path("clarificationCodes");if(!codes.isArray()||codes.size()>4)throw invalid();
        for(var code:codes){if(!code.isTextual()||!CODES.contains(code.textValue()))throw invalid();clarifications.add(code.textValue());}
        var changes=output.path("changes");keys(changes,FIELDS.toArray(String[]::new));
        var current=json.createObjectNode();
        JsonNode priorRequirements=previous.path("requirements");
        for(String field:FIELDS){
            JsonNode defaultValue=List.of("excludedIngredientNames","availableIngredientNames").contains(field)?json.createArrayNode():NullNode.instance;
            if("diningCount".equals(field)&&profile!=null)defaultValue=IntNode.valueOf(profile);
            JsonNode prior="diningCount".equals(field)?previous.path(field):priorRequirements.path(field);
            if(prior.isMissingNode())prior=defaultValue;
            var change=changes.path(field);keys(change,"action","value","evidence");
            String action=change.path("action").isTextual()?change.path("action").textValue():"";
            var value=change.path("value");var evidence=change.path("evidence");
            if("KEEP".equals(action)){if(!value.isNull()||!evidence.isNull())throw invalid();current.set(field,prior.deepCopy());}
            else if(Set.of("SET","CLEAR").contains(action)){
                if(!evidence.isTextual()||evidence.textValue().isBlank()||points(evidence.textValue())>200||!content.contains(evidence.textValue()))throw invalid();
                if("CLEAR".equals(action)){if(!value.isNull())throw invalid();current.set(field,defaultValue);}
                else current.set(field,validated(field,value));
            }else throw invalid();
        }
        String source=previous.path("diningCountSource").isTextual()?previous.path("diningCountSource").textValue():(profile==null?null:"PROFILE");
        String diningAction=changes.path("diningCount").path("action").asText();
        if("SET".equals(diningAction))source="EXPLICIT";
        if("CLEAR".equals(diningAction))source=profile==null?null:"PROFILE";
        if(formCount!=null){if("SET".equals(diningAction)&&current.path("diningCount").intValue()!=formCount)clarifications.add("DINING_COUNT_CONFLICT");current.put("diningCount",formCount);source="EXPLICIT";}
        current.put("diningCountSource",source);
        JsonNode mode=output.path("mode"),query=output.path("query");
        if(!mode.isNull()&&(!mode.isTextual()||!Set.of("RANDOM","SEMANTIC").contains(mode.textValue())))throw invalid();
        if(!query.isNull()&&(!query.isTextual()||query.textValue().isBlank()||points(query.textValue())>1000))throw invalid();
        if("RANDOM".equals(mode.asText())&&!query.isNull())throw invalid();
        if("SEMANTIC".equals(mode.asText())&&!query.isTextual())throw invalid();
        current.set("mode",mode);current.set("query",query);
        if(current.path("dishCount").isNull()||current.path("soupCount").isNull())clarifications.add("COUNT_REQUIRED");
        else if(current.path("dishCount").intValue()+current.path("soupCount").intValue()<1||current.path("dishCount").intValue()+current.path("soupCount").intValue()>10)throw invalid();
        if(current.path("diningCount").isNull())clarifications.add("DINING_COUNT_REQUIRED");
        if(content.matches("(?s).*(整餐|整桌|全部|一桌).*(分钟|小时).*"))clarifications.add("TIME_SCOPE_AMBIGUOUS");
        if("MEAL_PLAN".equals(output.path("intent").asText())&&mode.isNull())clarifications.add("REQUEST_AMBIGUOUS");
        if(clarifications.size()>4)throw invalid();
        return current;
    }
    private JsonNode validated(String field,JsonNode value){
        if(List.of("dishCount","soupCount","diningCount","maxCookingMinutes").contains(field)){
            int min=Set.of("dishCount","soupCount").contains(field)?0:1;
            int max=switch(field){case "dishCount"->8;case "soupCount"->4;case "maxCookingMinutes"->1440;default->Integer.MAX_VALUE;};
            if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<min||value.intValue()>max)throw invalid();return value;
        }
        if("budgetYuan".equals(field)){if(!value.isTextual()||!value.textValue().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?")||new BigDecimal(value.textValue()).compareTo(new BigDecimal("10000"))>0)throw invalid();return value;}
        if(!value.isArray()||value.size()>20)throw invalid();var normalized=json.createArrayNode();var seen=new HashSet<String>();
        for(var item:value){if(!item.isTextual()||item.textValue().strip().isEmpty()||points(item.textValue().strip())>80)throw invalid();String name=item.textValue().strip();safeText(name);if(seen.add(name))normalized.add(name);}return normalized;
    }
    public void safeTree(JsonNode value){if(value.isTextual())safeText(value.textValue());else if(value.isContainerNode())value.forEach(this::safeTree);}
    public void safeText(String text){if(text!=null&&UNSAFE.matcher(text).find())throw new ApiException(HttpStatus.BAD_REQUEST,"UNSAFE_CONTENT","餐单内容无法安全展示");}
    public void explanation(String value){safeText(value);if(value==null||value.isBlank()||points(value)>200||Pattern.compile("[0-9０-９*#`\\[\\]]|[一二三四五六七八九十百]+(?:人|份|克|分钟|元)|步骤|加入|倒入|翻炒|摄氏|热量|蛋白质|营养数值|用量|食材[：:]").matcher(value).find())throw invalid();}
    public String clarification(Set<String> codes){return String.join(" ",codes.stream().map(code->switch(code){case "COUNT_REQUIRED"->"这餐需要几道菜和几道汤？";case "DINING_COUNT_REQUIRED"->"这餐有几个人用餐？";case "DINING_COUNT_CONFLICT"->"表单人数与正文人数不同，请确认。";case "TIME_SCOPE_AMBIGUOUS"->"请确认单道菜的时间上限，当前不能核实整餐完成时间。";default->"请补充这餐的安排要求。";}).toList());}
    public static int points(String text){return text.codePointCount(0,text.length());}
    public static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"MODEL_INVALID_RESPONSE","餐单结构或模型内容不符合要求");}
}
