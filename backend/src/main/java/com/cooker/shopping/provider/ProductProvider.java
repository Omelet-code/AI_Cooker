package com.cooker.shopping.provider;

import java.math.BigDecimal;
import java.util.List;

/** 一次本地目录查询返回同一版本；未来网络适配不能直接搬入当前事务。 */
public interface ProductProvider {
    record Need(String itemKey,long ingredientId,BigDecimal quantity,String unit) {}
    record Product(String productId,Long ingredientId,String name,List<String> cityCodes,boolean available,
            BigDecimal packageAmount,String packageUnit,BigDecimal packagePrice,String currency,
            BigDecimal referenceUnitPrice,String referenceUnit) {}
    record Selection(String itemKey,String status,String reason,Product product) {}
    record Offers(String catalogVersion,List<Selection> selections) {}
    Offers lookup(String cityCode,List<Need> needs);
}
