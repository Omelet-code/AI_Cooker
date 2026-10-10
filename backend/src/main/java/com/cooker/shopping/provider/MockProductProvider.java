package com.cooker.shopping.provider;

import com.cooker.shopping.config.MockCatalogProperties;
import com.cooker.shopping.service.ShoppingDemandCalculator;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 显式Mock，无网络、无名称推断；同维有效候选按精确比值确定排序。 */
@Component
public class MockProductProvider implements ProductProvider {
    private final MockCatalogProperties config;
    public MockProductProvider(MockCatalogProperties config){this.config=config;}
    @Override public Offers lookup(String city,List<Need> needs){
        String version=config.getCatalogVersion();var catalog=config.getProducts();
        if(version==null||!version.matches("[A-Za-z0-9._-]{1,64}")||catalog==null||catalog.size()>10000)throw invalid();
        catalog=List.copyOf(catalog);var ids=new HashSet<String>();for(var p:catalog)validate(p,ids);
        var result=new ArrayList<Selection>();for(var n:needs){
            var bound=catalog.stream().filter(p->p.ingredientId()==n.ingredientId()).toList();
            if(bound.isEmpty()){result.add(no(n,"NO_PRODUCT","Mock目录未配置该标准食材"));continue;}
            var local=bound.stream().filter(p->p.cityCodes().contains(city)).toList();
            if(local.isEmpty()){result.add(no(n,"NO_PRODUCT","Mock商品不支持所选城市"));continue;}
            var available=local.stream().filter(Product::available).toList();
            if(available.isEmpty()){result.add(no(n,"OUT_OF_STOCK","所选城市的Mock商品暂时缺货"));continue;}
            var compatible=available.stream().filter(p->p.packageUnit()==null||same(n.unit(),p.packageUnit())).toList();
            if(compatible.isEmpty()){result.add(no(n,"UNIT_UNSUPPORTED","Mock规格单位与需求维度不兼容"));continue;}
            var valid=compatible.stream().filter(p->p.packageAmount()!=null&&p.packageUnit()!=null&&p.packagePrice()!=null).sorted((a,b)->compare(n,a,b)).toList();
            if(!valid.isEmpty())result.add(new Selection(n.itemKey(),"MATCHED",null,valid.getFirst()));
            else result.add(new Selection(n.itemKey(),"QUOTE_UNKNOWN","Mock商品规格或价格尚未提供",compatible.stream().min(Comparator.comparing(Product::productId)).orElseThrow()));
        }return new Offers(version,List.copyOf(result));
    }
    private Selection no(Need n,String state,String reason){return new Selection(n.itemKey(),state,reason,null);}
    private int compare(Need n,Product a,Product b){
        var pa=ShoppingDemandCalculator.baseAmount(a.packageAmount(),a.packageUnit());var pb=ShoppingDemandCalculator.baseAmount(b.packageAmount(),b.packageUnit());
        int price=a.packagePrice().multiply(pb).compareTo(b.packagePrice().multiply(pa));if(price!=0)return price;
        var q=ShoppingDemandCalculator.baseAmount(n.quantity(),n.unit());
        int out=q.divide(pa,0,RoundingMode.CEILING).multiply(a.packagePrice()).compareTo(q.divide(pb,0,RoundingMode.CEILING).multiply(b.packagePrice()));return out!=0?out:a.productId().compareTo(b.productId());
    }
    public static boolean supported(String unit){return unit!=null&&Set.of("G","KG","ML","L","COUNT").contains(unit);}
    public static boolean same(String a,String b){return supported(a)&&supported(b)&&ShoppingDemandCalculator.baseUnit(a).equals(ShoppingDemandCalculator.baseUnit(b));}
    private void validate(Product p,Set<String> ids){
        if(p==null||p.productId()==null||!p.productId().matches("[A-Za-z0-9._-]{1,64}")||!ids.add(p.productId())||p.ingredientId()==null||p.ingredientId()<=0||p.name()==null||p.name().isBlank()||p.name().length()>200||p.cityCodes()==null||p.cityCodes().isEmpty()||p.cityCodes().size()>100||p.cityCodes().stream().anyMatch(c->c==null||!c.matches("[0-9]{6}"))||!"CNY".equals(p.currency()))throw invalid();
        quantity(p.packageAmount());money(p.packagePrice());
        if(p.packageUnit()!=null&&!supported(p.packageUnit()))throw invalid();
        if((p.referenceUnitPrice()==null)!=(p.referenceUnit()==null))throw invalid();
        if(p.referenceUnitPrice()!=null){if(!supported(p.referenceUnit())||p.referenceUnitPrice().signum()<0||p.referenceUnitPrice().scale()>8||p.referenceUnitPrice().compareTo(new BigDecimal("1000000000"))>0)throw invalid();}
    }
    private void quantity(BigDecimal n){if(n!=null&&(n.signum()<=0||n.scale()>8||n.compareTo(new BigDecimal("1000000000"))>0))throw invalid();}
    private void money(BigDecimal n){if(n!=null&&(n.signum()<0||n.scale()>2||n.compareTo(new BigDecimal("9999999999999999.99"))>0))throw invalid();}
    private IllegalStateException invalid(){return new IllegalStateException("Mock目录配置或报价不合法");}
}
