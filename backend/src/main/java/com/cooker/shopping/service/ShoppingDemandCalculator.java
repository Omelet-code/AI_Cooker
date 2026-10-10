package com.cooker.shopping.service;

import com.cooker.conversation.dto.ShoppingSource;
import com.cooker.recipe.dto.MealRecipeFacts.Ingredient;
import com.cooker.shopping.dto.ShoppingRequest.*;
import com.cooker.shopping.vo.ShoppingView.*;
import java.math.*;
import java.util.*;
import static com.cooker.shopping.service.ShoppingJson.*;

/** 确定性缺口计算；只有明确标准食材、兼容单位及optional一致才合并。 */
public final class ShoppingDemandCalculator {
    private ShoppingDemandCalculator(){}
    public static Source source(ShoppingSource s){return new Source(Long.toString(s.conversationId()),Long.toString(s.mealId()),s.version(),Long.toString(s.optionId()),s.diningCount(),decimal(s.budgetYuan()),hash(s));}
    public static List<Line> lines(ShoppingSource s){var result=new ArrayList<Line>();var seen=new HashSet<String>();
        for(var recipe:s.materials().recipes())for(var material:recipe.materials()){
            var i=material.original();String key=recipe.facts().recipeId()+":"+i.recipeIngredientId();
            if(i.sourceRecipeId()!=recipe.facts().recipeId()||!seen.add(key)||recipe.facts().ingredients().stream().noneMatch(i::equals))throw conflict("SHOPPING_SOURCE_CHANGED");
            result.add(new Line(key,Long.toString(recipe.facts().recipeId()),Long.toString(i.recipeIngredientId()),i.ingredientId()==null?null:i.ingredientId().toString(),i.rawName(),i.rawLine(),i.optional(),decimal(i.amountMin()),decimal(i.amountMax()),i.unitCode(),i.unitLabel(),decimal(material.referenceAmount()),material.status().name(),material.reason(),material.needsConfirmation(),null,null,null));
        }if(result.isEmpty())throw conflict("SHOPPING_SOURCE_CHANGED");return List.copyOf(result);
    }
    public record Demand(List<Line> lines,List<Item> items,String status,Costs costs) {}
    public static Demand calculate(ShoppingSource s,List<Confirmation> confirmations){
        var preview=lines(s);var byKey=new HashMap<String,Confirmation>();for(var c:confirmations)if(byKey.put(c.lineKey(),c)!=null)throw invalid();
        if(preview.size()!=byKey.size())throw invalid();var lines=new ArrayList<Line>();var items=new LinkedHashMap<String,Item>();var reviewed=new HashSet<String>();
        for(var r:s.materials().recipes())for(var material:r.materials())if("REVIEWED".equals(material.original().normalizationStatus()))reviewed.add(r.facts().recipeId()+":"+material.original().recipeIngredientId());
        for(var line:preview){var c=byKey.get(line.lineKey());if(c==null)throw invalid();
            boolean reliable=!line.needsConfirmation()&&line.referenceAmount()!=null&&"REFERENCE_SCALED".equals(line.scaleStatus());
            if(reliable&&c.confirmedAmount()!=null)throw invalid();
            String code=c.confirmedAmount()==null?line.unitCode():c.confirmedUnitCode();
            if(c.confirmedAmount()!=null)validateUnit(line.unitCode(),code);
            BigDecimal amount=c.confirmedAmount()!=null?c.confirmedAmount():line.referenceAmount()==null?null:new BigDecimal(line.referenceAmount());
            // 未确认的参考量不能因为可解析就当成已知需求，尤其个数、调味或区间。
            if(!reliable&&c.confirmedAmount()==null)amount=null;
            String unit=baseUnit(code),label=unitLabel(unit,line.unitLabel());
            if(amount!=null){amount=baseAmount(amount,code);if(amount.signum()<=0)amount=null;else columnAmount(amount);}
            String status=c.availability()==Availability.HAVE_ENOUGH?"HAVE_ENOUGH":amount==null?"QUANTITY_PENDING":"NEED_BUY";
            BigDecimal shortage="HAVE_ENOUGH".equals(status)?BigDecimal.ZERO:amount;
            String reason="QUANTITY_PENDING".equals(status)?(line.reason()==null||line.reason().isBlank()?"":line.reason()+"；")+"用量待用户确认；保留原用量与未知说明":line.reason();
            String group="NEED_BUY".equals(status)&&line.ingredientId()!=null&&reviewed.contains(line.lineKey())?line.ingredientId()+":"+unit+":"+label+":"+line.optional():"line:"+line.lineKey();
            var item=new Item(line.lineKey(),List.of(line.lineKey()),line.ingredientId(),line.name(),unit,label,line.optional(),decimal(amount),decimal(shortage),status,reason);
            var prior=items.get(group);if(prior!=null){var keys=new ArrayList<>(prior.lineKeys());keys.add(line.lineKey());BigDecimal sum=new BigDecimal(prior.requiredAmount()).add(amount);columnAmount(sum);item=new Item(prior.itemKey(),List.copyOf(keys),prior.ingredientId(),prior.name(),prior.unitCode(),prior.unitLabel(),prior.optional(),decimal(sum),decimal(sum),status,reason);}items.put(group,item);
            lines.add(new Line(line.lineKey(),line.recipeId(),line.recipeIngredientId(),line.ingredientId(),line.name(),line.rawLine(),line.optional(),line.originalAmountMin(),line.originalAmountMax(),line.unitCode(),line.unitLabel(),line.referenceAmount(),line.scaleStatus(),line.reason(),line.needsConfirmation(),c.availability().name(),decimal(c.confirmedAmount()),c.confirmedUnitCode()));
        }
        var values=List.copyOf(items.values());int unknown=(int)values.stream().filter(i->!"HAVE_ENOUGH".equals(i.status())).count();
        boolean pending=values.stream().anyMatch(i->"QUANTITY_PENDING".equals(i.status()));String zero=unknown==0?"0.00":null;
        return new Demand(List.copyOf(lines),values,pending?"DRAFT":"READY_FOR_MATCHING",new Costs("CNY",zero,zero,zero,"0.00",unknown,s.budgetYuan()==null?"NOT_SET":unknown>0?"UNKNOWN":"WITHIN_BUDGET"));
    }
    private static void validateUnit(String original,String confirmed){if(original==null){if(!Set.of("G","KG","ML","L").contains(confirmed))throw invalid();return;}if(!original.equals(confirmed)&&!(Set.of("G","KG").contains(original)&&Set.of("G","KG").contains(confirmed))&&!(Set.of("ML","L").contains(original)&&Set.of("ML","L").contains(confirmed)))throw invalid();}
    public static String baseUnit(String unit){return unit==null?null:switch(unit){case "KG"->"G";case "L"->"ML";default->unit;};}
    public static BigDecimal baseAmount(BigDecimal amount,String unit){return Set.of("KG","L").contains(unit==null?"":unit)?amount.multiply(new BigDecimal("1000")):amount;}
    private static String unitLabel(String unit,String original){return unit==null?original:switch(unit){case "G"->"g";case "ML"->"ml";default->original;};}
    private static void columnAmount(BigDecimal amount){var n=amount.stripTrailingZeros();if(n.scale()>8||n.precision()-n.scale()>16)throw invalid();}
}
