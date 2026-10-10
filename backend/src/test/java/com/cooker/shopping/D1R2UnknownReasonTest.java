package com.cooker.shopping;

import static org.assertj.core.api.Assertions.*;
import com.cooker.conversation.dto.ShoppingSource;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.impl.MealMaterialCalculator;
import com.cooker.shopping.dto.ShoppingRequest.*;
import com.cooker.shopping.provider.ProductProvider.Offers;
import com.cooker.shopping.service.*;
import com.cooker.shopping.vo.ShoppingView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 锁定UNKNOWN解释从材料到缺口及商品匹配的传递，不改变用量算法或HTTP形状。 */
class D1R2UnknownReasonTest {
    private ShoppingSource source() {
        var i=new MealRecipeFacts.Ingredient(101,35L,"REVIEWED","盐","盐 10g",new BigDecimal("10"),new BigDecimal("10"),"G","g",false,1,10);
        var r=new MealRecipeFacts(10,"测试菜","meat_dish",new MealRecipeFacts.RoleEvidence(MealRole.DISH,"测试","test"),10,"a".repeat(64),null,10,List.of(i),List.of(),List.of(),null,"b".repeat(64));
        return new ShoppingSource(1,2,1,3,3,new BigDecimal("5"),List.of(new ShoppingSource.RecipeReference(10,"b".repeat(64))),MealMaterialCalculator.calculate(List.of(r),3));
    }
    @Test void 原人数未知原因穿过购物缺口层且不变成零或食材不存在() {
        var source=source();var demand=ShoppingDemandCalculator.calculate(source,List.of(new Confirmation("10:101",Availability.NEED_BUY,null,null)));
        assertThat(demand.items().getFirst().reason()).contains("原份数未知");
        assertThat(demand.items().getFirst().status()).isEqualTo("QUANTITY_PENDING");
        assertThat(demand.items().getFirst().shortageAmount()).isNull();
        assertThat(source.materials().recipes().getFirst().facts().servings()).isNull();
    }
    @Test void 商品数量待确认保留原人数解释且不请求Provider() {
        var item=new ShoppingView.Item("10:101",List.of("10:101"),"35","盐","G","g",false,null,null,"QUANTITY_PENDING","原份数未知或无效；用量待用户确认");
        var view=new ShoppingView(1,"1","test","CURRENT",new ShoppingView.Source("1","2",1,"3",3,"5","a".repeat(64)),List.of(),List.of(item),"DRAFT","NOT_MATCHED",null);
        assertThat(ProductMatchCalculator.needs(view)).isEmpty();
        var match=ProductMatchCalculator.calculate(view,"110000",new Offers("NOT_QUERIED",List.of()));
        assertThat(match.lines().getFirst().reason()).contains("原份数未知");
        assertThat(match.lines().getFirst().status()).isEqualTo("QUANTITY_PENDING");
        assertThat(match.costs().consumptionCost()).isNull();
        assertThat(match.costs().budgetStatus()).isEqualTo("UNKNOWN");
    }
    @Test void 公式水位及待审定标准食材仅补解释而不改变事实() {
        var formula=new MealRecipeFacts.Ingredient(101,35L,"REVIEWED","盐","盐量 = 肉重 * 2 克",null,null,null,null,false,1,10);
        var water=new MealRecipeFacts.Ingredient(102,999L,"UNREVIEWED","江南米","江南米，食指触及米时水没过第一个指节",null,null,null,null,false,2,10);
        var f=MealMaterialCalculator.scale(formula,2,3);var w=MealMaterialCalculator.scale(water,2,3);
        assertThat(f.reason()).contains("公式");assertThat(f.referenceAmount()).isNull();assertThat(f.original()).isSameAs(formula);
        assertThat(w.reason()).contains("水位");assertThat(w.referenceAmount()).isNull();assertThat(w.original()).isSameAs(water);
        var unreviewed=new MealRecipeFacts.Ingredient(103,999L,"UNREVIEWED","待审食材","待审食材 10g",new BigDecimal("10"),new BigDecimal("10"),"G","g",false,3,10);
        var scaled=MealMaterialCalculator.scale(unreviewed,2,3);
        assertThat(scaled.referenceAmount()).isEqualByComparingTo("15");assertThat(scaled.reason()).contains("标准食材尚未确认");
        assertThat(unreviewed.normalizationStatus()).isEqualTo("UNREVIEWED");
    }
}
