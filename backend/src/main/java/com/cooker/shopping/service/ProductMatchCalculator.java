package com.cooker.shopping.service;

import com.cooker.shopping.provider.ProductProvider.Need;
import com.cooker.shopping.provider.ProductProvider.Selection;
import com.cooker.shopping.provider.ProductProvider.Offers;
import com.cooker.shopping.provider.MockProductProvider;
import com.cooker.shopping.vo.*;
import com.cooker.shopping.vo.ProductMatchView.*;
import java.math.*;
import java.util.*;

/** 保留原清单不变，先合商品采购量再上取整，明细支出不重复计入总额。 */
public final class ProductMatchCalculator {
    private ProductMatchCalculator(){}
    private static final MathContext PRECISION=new MathContext(80,RoundingMode.HALF_EVEN);
    public static List<Need> needs(ShoppingView source){var out=new ArrayList<Need>();for(var i:source.items())if("NEED_BUY".equals(i.status())&&i.ingredientId()!=null&&MockProductProvider.supported(i.unitCode()))out.add(new Need(i.itemKey(),Long.parseLong(i.ingredientId()),new BigDecimal(i.shortageAmount()),i.unitCode()));return List.copyOf(out);}
    public static ProductMatchView calculate(ShoppingView source,String city,Offers offers){
        var selected=new HashMap<String,Selection>();for(var s:offers.selections())if(selected.put(s.itemKey(),s)!=null)throw ShoppingJson.invalid();
        var lines=new ArrayList<Line>();var groups=new LinkedHashMap<String,PurchaseGroup>();BigDecimal consumed=BigDecimal.ZERO,estimated=BigDecimal.ZERO;int unknown=0,estimateUnknown=0,matched=0,buy=0;
        for(var i:source.items()){
            String state,reason=i.reason();Selection selection=selected.get(i.itemKey());Product product=null;BigDecimal consumption=null,outlay=null,estimate=null,count=null;
            if("HAVE_ENOUGH".equals(i.status())){state="HAVE_ENOUGH";consumption=outlay=estimate=BigDecimal.ZERO;}
            else {
                buy++;if("QUANTITY_PENDING".equals(i.status())){state="QUANTITY_PENDING";reason=(i.reason()==null||i.reason().isBlank()?"":i.reason()+"；")+"需求用量待确认";}
                else if(!MockProductProvider.supported(i.unitCode())){state="UNIT_UNSUPPORTED";reason="该需求单位不支持商品规格换算";}
                else if(i.ingredientId()==null){state="NO_PRODUCT";reason="原料没有明确标准食材标识，不进行名称推断";}
                else {if(selection==null)throw ShoppingJson.invalid();state=selection.status();reason=selection.reason();product=view(selection.product());}
                if("MATCHED".equals(state)){
                    var p=selection.product();var quantity=ShoppingDemandCalculator.baseAmount(new BigDecimal(i.shortageAmount()),i.unitCode());var pack=ShoppingDemandCalculator.baseAmount(p.packageAmount(),p.packageUnit());
                    consumption=quantity.multiply(p.packagePrice()).divide(pack,PRECISION);count=quantity.divide(pack,0,RoundingMode.CEILING);outlay=count.multiply(p.packagePrice());matched++;
                    String key=p.productId()+":"+city+":"+ShoppingDemandCalculator.baseUnit(i.unitCode());var old=groups.get(key);var keys=new ArrayList<String>();BigDecimal total=quantity;if(old!=null){keys.addAll(old.itemKeys());total=total.add(new BigDecimal(old.quantity()));}keys.add(i.itemKey());
                    quantityColumn(total);var packages=total.divide(pack,0,RoundingMode.CEILING);quantityColumn(packages);var purchase=packages.multiply(p.packagePrice());
                    groups.put(key,new PurchaseGroup(key,List.copyOf(keys),decimal(total),ShoppingDemandCalculator.baseUnit(i.unitCode()),product,decimal(packages),money(purchase)));
                }
                // 独立估价只取显式reference，不用模拟成交价冒充估价来源。
                if(selection!=null&&selection.product()!=null&&selection.product().referenceUnitPrice()!=null&&MockProductProvider.same(i.unitCode(),selection.product().referenceUnit())){
                    var quantity=ShoppingDemandCalculator.baseAmount(new BigDecimal(i.shortageAmount()),i.unitCode());var unit=ShoppingDemandCalculator.baseAmount(BigDecimal.ONE,selection.product().referenceUnit());estimate=quantity.multiply(selection.product().referenceUnitPrice()).divide(unit,PRECISION);
                }
                if(consumption==null)unknown++;if(estimate==null)estimateUnknown++;
            }
            if(consumption!=null)consumed=consumed.add(consumption);if(estimate!=null)estimated=estimated.add(estimate);
            lines.add(new Line(i.itemKey(),i.lineKeys(),i.ingredientId(),i.name(),i.requiredAmount(),i.shortageAmount(),i.unitCode(),state,reason,product,decimal(count),money(consumption),money(outlay),money(estimate),product!=null&&estimate!=null?"MOCK_REFERENCE_UNIT_PRICE":null));
        }
        BigDecimal purchase=BigDecimal.ZERO;for(var g:groups.values())purchase=purchase.add(new BigDecimal(g.purchaseOutlay()));
        String consumptionText=unknown>0?null:money(consumed);String budget=source.source().budgetYuan()==null?"NOT_SET":unknown>0?"UNKNOWN":new BigDecimal(consumptionText).compareTo(new BigDecimal(source.source().budgetYuan()))>0?"OVER_BUDGET":"WITHIN_BUDGET";
        var costs=new Costs("CNY",estimateUnknown>0?null:money(estimated),consumptionText,unknown>0?null:money(purchase),money(consumed),unknown,money(estimated),estimateUnknown,budget);
        String state=buy==0?"NOT_NEEDED":matched==buy?"MATCHED":matched>0?"PARTIAL":"NO_MATCH";
        return new ProductMatchView(1,null,source.id(),source.source().sourceFingerprint(),source.factsStatus(),"MOCK",false,offers.catalogVersion(),city,null,state,List.copyOf(lines),List.copyOf(groups.values()),costs,
                "OVER_BUDGET".equals(budget)?"模拟用量成本超过预算，保留原清单，请自行确认采购安排":"UNKNOWN".equals(budget)?"存在未知成本；仅展示已知小计，不能确认满足预算":"商品与费用为Mock模拟结果，不可下单");
    }
    private static Product view(com.cooker.shopping.provider.ProductProvider.Product p){return p==null?null:new Product(p.productId(),p.ingredientId().toString(),p.name(),decimal(p.packageAmount()),p.packageUnit(),money(p.packagePrice()),p.currency(),null,decimal(p.referenceUnitPrice()),p.referenceUnit());}
    private static String decimal(BigDecimal n){return ShoppingJson.decimal(n);}
    public static String money(BigDecimal n){if(n==null)return null;var rounded=n.setScale(2,RoundingMode.HALF_UP);if(rounded.signum()<0||rounded.compareTo(new BigDecimal("9999999999999999.99"))>0)throw ShoppingJson.invalid();return rounded.toPlainString();}
    public static void quantityColumn(BigDecimal n){var v=n.stripTrailingZeros();if(v.signum()<0||v.scale()>8||v.precision()-v.scale()>16)throw ShoppingJson.invalid();}
}
