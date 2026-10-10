package com.cooker.shopping.dto;

import com.cooker.conversation.ConversationIdParser;
import com.cooker.shopping.service.ShoppingJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** 局部原始JSON校验，不允许HTTP事实/价格或数值静默转换。 */
public record ShoppingRequest(String requestId,long conversationId,long mealId,int expectedVersion,long optionId,
        String sourceFingerprint,List<Confirmation> confirmations) {
    public enum Availability { HAVE_ENOUGH, NEED_BUY }
    public record Confirmation(String lineKey,Availability availability,BigDecimal confirmedAmount,String confirmedUnitCode) {}
    public static ShoppingRequest parse(String raw){
        var root=ShoppingJson.parse(raw);ShoppingJson.keys(root,"requestId","conversationId","mealId","expectedVersion","optionId","sourceFingerprint","confirmations");
        String request=text(root,"requestId");if(!request.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw ShoppingJson.invalid();
        var version=root.path("expectedVersion");if(!version.isIntegralNumber()||!version.canConvertToInt()||version.intValue()<=0)throw ShoppingJson.invalid();
        String fingerprint=text(root,"sourceFingerprint");if(!fingerprint.matches("[0-9a-f]{64}"))throw ShoppingJson.invalid();
        var list=root.path("confirmations");if(!list.isArray()||list.isEmpty()||list.size()>1000)throw ShoppingJson.invalid();
        var confirmations=new ArrayList<Confirmation>();var seen=new HashSet<String>();
        for(var node:list){ShoppingJson.keys(node,"lineKey","availability","confirmedAmount","confirmedUnitCode");String key=text(node,"lineKey");
            if(!key.matches("[1-9][0-9]*:[1-9][0-9]*")||!seen.add(key))throw ShoppingJson.invalid();
            Availability availability;try{availability=Availability.valueOf(text(node,"availability"));}catch(IllegalArgumentException e){throw ShoppingJson.invalid();}
            var amount=node.path("confirmedAmount");var unit=node.path("confirmedUnitCode");boolean noAmount=amount.isNull()||amount.isMissingNode(),noUnit=unit.isNull()||unit.isMissingNode();
            if(noAmount!=noUnit)throw ShoppingJson.invalid();BigDecimal value=null;String code=null;
            if(!noAmount){if(!amount.isTextual()||!amount.asText().matches("[0-9]{1,10}(\\.[0-9]{1,8})?"))throw ShoppingJson.invalid();
                value=new BigDecimal(amount.asText());if(value.signum()<=0||value.compareTo(new BigDecimal("1000000000"))>0)throw ShoppingJson.invalid();
                code=text(node,"confirmedUnitCode");if(!Set.of("G","KG","ML","L","COUNT","CUSTOM").contains(code))throw ShoppingJson.invalid();}
            confirmations.add(new Confirmation(key,availability,value,code));
        }
        confirmations.sort(Comparator.comparing(Confirmation::lineKey));
        return new ShoppingRequest(UUID.fromString(request).toString(),ConversationIdParser.parse(text(root,"conversationId")),ConversationIdParser.parse(text(root,"mealId")),version.intValue(),ConversationIdParser.parse(text(root,"optionId")),fingerprint,List.copyOf(confirmations));
    }
    private static String text(JsonNode node,String key){if(!node.path(key).isTextual()||node.path(key).asText().isBlank())throw ShoppingJson.invalid();return node.path(key).asText();}
}
