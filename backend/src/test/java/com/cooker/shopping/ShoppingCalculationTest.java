package com.cooker.shopping;

import static org.assertj.core.api.Assertions.*;
import com.cooker.conversation.dto.ShoppingSource;
import com.cooker.recipe.dto.*;
import com.cooker.shopping.dto.ShoppingRequest.*;
import com.cooker.shopping.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShoppingCalculationTest {
    @Test void 不明自定义规格不能因编码相同就当兼容(){assertThat(ShoppingCostCalculator.calculate(new BigDecimal("2"),"CUSTOM",new BigDecimal("4"),"CUSTOM",new BigDecimal("10"),false).unknown()).isTrue();assertThat(ShoppingCostCalculator.calculate(new BigDecimal("2"),"UNKNOWN",new BigDecimal("4"),"UNKNOWN",new BigDecimal("10"),false).unknown()).isTrue();assertThat(ShoppingCostCalculator.calculate(new BigDecimal("2"),"G",new BigDecimal("4"),"ML",new BigDecimal("10"),false).unknown()).isTrue();}
    private BigDecimal n(String number){return number==null?null:new BigDecimal(number);}
    private ShoppingSource source(boolean confirm,String unit,String label,String amount){
        var ingredient=new MealRecipeFacts.Ingredient(101,1L,"REVIEWED","葱","葱原始用量",n(amount),n(amount),unit,label,false,1,10);
        var recipe=new MealRecipeFacts(10,"菜","vegetable_dish",new MealRecipeFacts.RoleEvidence(MealRole.DISH,"测试","test"),10,"a".repeat(64),2,10,List.of(ingredient),List.of(),List.of(),"原注意事项","b".repeat(64));
        var scaled=new MealMaterials.ScaledIngredient(ingredient,n(amount),MealMaterials.ScaleStatus.REFERENCE_SCALED,"保留参考说明",confirm);
        var materials=new MealMaterials(3,List.of(new MealMaterials.RecipeMaterials(recipe,List.of(scaled))),List.of(),List.of(),"REFERENCE_ONLY");
        return new ShoppingSource(1,2,1,3,3,n("5.00"),List.of(new ShoppingSource.RecipeReference(10,"b".repeat(64))),materials);
    }
    private Confirmation confirmation(String amount,String unit,Availability availability){return new Confirmation("10:101",availability,n(amount),unit);}
    @Test void 可靠无需确认参考量不接受覆盖而未知足量缺口零(){var source=source(false,"G","g","10");var demand=ShoppingDemandCalculator.calculate(source,List.of(confirmation(null,null,Availability.NEED_BUY)));assertThat(demand.items().getFirst().requiredAmount()).isEqualTo("10");assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source,List.of(confirmation("20","G",Availability.NEED_BUY)))).isInstanceOf(com.cooker.common.ApiException.class);var unknown=ShoppingDemandCalculator.calculate(source(true,null,null,null),List.of(confirmation(null,null,Availability.HAVE_ENOUGH)));assertThat(unknown.items().getFirst().requiredAmount()).isNull();assertThat(unknown.items().getFirst().shortageAmount()).isEqualTo("0");}
    @Test void 同维换算单位缺失白名单与禁止计数质量体积混换(){var converted=ShoppingDemandCalculator.calculate(source(true,"KG","kg","1"),List.of(confirmation("10","G",Availability.NEED_BUY)));assertThat(converted.items().getFirst().requiredAmount()).isEqualTo("10");assertThat(converted.items().getFirst().unitCode()).isEqualTo("G");assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source(true,"COUNT","个","2"),List.of(confirmation("2","G",Availability.NEED_BUY)))).isInstanceOf(com.cooker.common.ApiException.class);assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source(true,"G","g","2"),List.of(confirmation("2","ML",Availability.NEED_BUY)))).isInstanceOf(com.cooker.common.ApiException.class);assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source(true,null,null,null),List.of(confirmation("2","COUNT",Availability.NEED_BUY)))).isInstanceOf(com.cooker.common.ApiException.class);}
    @Test void 数量转换超列精度拒绝且语义摘要忽略数量格式(){assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source(true,"G","g","1"),List.of(confirmation("0.000000001","G",Availability.NEED_BUY)))).isInstanceOf(com.cooker.common.ApiException.class);assertThat(ShoppingJson.hash(source(true,"G","g","1.000"))).isEqualTo(ShoppingJson.hash(source(true,"G","g","1.0")));}
    @Test void 十克与一百一十克消耗和包装支出分开(){var a=ShoppingCostCalculator.calculate(n("10"),"G",n("100"),"G",n("10"),false);assertThat(a.consumptionCost()).isEqualByComparingTo("1.00");assertThat(a.purchaseOutlay()).isEqualByComparingTo("10.00");var b=ShoppingCostCalculator.calculate(n("110"),"G",n("100"),"G",n("10"),false);assertThat(b.consumptionCost()).isEqualByComparingTo("11.00");assertThat(b.purchaseOutlay()).isEqualByComparingTo("20.00");}
    @Test void 先合并兼容需求再上取整足量与未知不得误计包数(){var demand=ShoppingCostCalculator.calculate(n("20"),"G",n("100"),"G",n("10"),false);assertThat(demand.purchaseOutlay()).isEqualByComparingTo("10");assertThat(ShoppingCostCalculator.calculate(null,"G",n("100"),"G",n("10"),false).unknown()).isTrue();assertThat(ShoppingCostCalculator.calculate(n("10"),"G",null,"G",n("10"),false).consumptionCost()).isNull();assertThat(ShoppingCostCalculator.calculate(null,null,null,null,null,true).purchaseOutlay()).isEqualByComparingTo("0");}
    @Test void 预算超出保留数值未知仅列小计且总额最后舍入(){var q=new ShoppingCostCalculator.Quote(n("110"),"G",n("100"),"G",n("10"),false);var over=ShoppingCostCalculator.totals(List.of(q),n("5"));assertThat(over.budgetStatus()).isEqualTo("OVER_BUDGET");assertThat(over.consumptionCost()).isEqualByComparingTo("11");var unknown=ShoppingCostCalculator.totals(List.of(q,new ShoppingCostCalculator.Quote(null,"G",n("100"),"G",n("10"),false)),n("50"));assertThat(unknown.budgetStatus()).isEqualTo("UNKNOWN");assertThat(unknown.consumptionCost()).isNull();assertThat(unknown.knownSubtotal()).isEqualByComparingTo("11");assertThat(unknown.unknownItemCount()).isEqualTo(1);var third=new ShoppingCostCalculator.Quote(n("1"),"G",n("3"),"G",n("1"),false);assertThat(ShoppingCostCalculator.totals(List.of(third,third),null).consumptionCost()).isEqualByComparingTo("0.67");}
}
