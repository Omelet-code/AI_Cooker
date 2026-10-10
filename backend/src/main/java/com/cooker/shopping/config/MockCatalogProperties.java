package com.cooker.shopping.config;

import com.cooker.shopping.provider.ProductProvider.Product;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 默认空目录；标准食材ID仅由明确本地配置绑定。 */
@Component
@ConfigurationProperties(prefix="cooker.shopping.mock")
public class MockCatalogProperties {
    private String catalogVersion="empty-v1";
    private List<Product> products=List.of();
    public String getCatalogVersion(){return catalogVersion;}
    public void setCatalogVersion(String version){catalogVersion=version;}
    public List<Product> getProducts(){return products;}
    public void setProducts(List<Product> products){this.products=products==null?null:List.copyOf(products);}
}
