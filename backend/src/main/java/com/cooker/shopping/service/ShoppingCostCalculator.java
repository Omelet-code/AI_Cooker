package com.cooker.shopping.service;

import java.math.*;

/** 仅显式测试报价或未来T17内部调用；T16公开接口没有商品报价输入。 */
public final class ShoppingCostCalculator {
    private ShoppingCostCalculator(){}
    public record Cost(BigDecimal consumptionCost,BigDecimal purchaseOutlay,boolean unknown) {}
    public record Quote(BigDecimal quantity,String unit,BigDecimal packageAmount,String packageUnit,BigDecimal packagePrice,boolean haveEnough) {}
    public record Total(BigDecimal consumptionCost,BigDecimal purchaseOutlay,BigDecimal knownSubtotal,int unknownItemCount,String budgetStatus) {}
    public static Cost calculate(BigDecimal quantity,String unit,BigDecimal packageAmount,String packageUnit,BigDecimal packagePrice,boolean haveEnough){
        var raw=raw(new Quote(quantity,unit,packageAmount,packageUnit,packagePrice,haveEnough));
        return raw.unknown()?raw:new Cost(raw.consumptionCost().setScale(2,RoundingMode.HALF_UP),raw.purchaseOutlay().setScale(2,RoundingMode.HALF_UP),false);
    }
    public static Total totals(java.util.List<Quote> quotes,BigDecimal budget){
        BigDecimal consumed=BigDecimal.ZERO,outlay=BigDecimal.ZERO;int unknown=0;
        for(var quote:quotes){var cost=raw(quote);if(cost.unknown()){unknown++;continue;}consumed=consumed.add(cost.consumptionCost());outlay=outlay.add(cost.purchaseOutlay());}
        var finalConsumption=consumed.setScale(2,RoundingMode.HALF_UP);
        String status=budget==null?"NOT_SET":unknown>0?"UNKNOWN":finalConsumption.compareTo(budget)>0?"OVER_BUDGET":"WITHIN_BUDGET";
        return new Total(unknown>0?null:finalConsumption,unknown>0?null:outlay.setScale(2,RoundingMode.HALF_UP),finalConsumption,unknown,status);
    }
    private static Cost raw(Quote quote){
        var quantity=quote.quantity();var unit=quote.unit();var packageAmount=quote.packageAmount();var packageUnit=quote.packageUnit();var packagePrice=quote.packagePrice();boolean haveEnough=quote.haveEnough();
        if(haveEnough)return new Cost(new BigDecimal("0.00"),new BigDecimal("0.00"),false);
        // 辅助报价没有自定义单位标签，不能仅因两个CUSTOM码相同就认定规格兼容。
        var units=java.util.Set.of("G","KG","ML","L","COUNT");
        if(quantity==null||packageAmount==null||packagePrice==null||unit==null||packageUnit==null||!units.contains(unit)||!units.contains(packageUnit)||quantity.signum()<0||packageAmount.signum()<=0||packagePrice.signum()<0||!ShoppingDemandCalculator.baseUnit(unit).equals(ShoppingDemandCalculator.baseUnit(packageUnit)))return new Cost(null,null,true);
        var demand=ShoppingDemandCalculator.baseAmount(quantity,unit);var pack=ShoppingDemandCalculator.baseAmount(packageAmount,packageUnit);
        // 中间不截断单价，乘后除；包装数独立上取整。
        var consumption=demand.multiply(packagePrice).divide(pack,new MathContext(80,RoundingMode.HALF_EVEN));
        var outlay=demand.divide(pack,0,RoundingMode.CEILING).multiply(packagePrice);
        return new Cost(consumption,outlay,false);
    }
}
