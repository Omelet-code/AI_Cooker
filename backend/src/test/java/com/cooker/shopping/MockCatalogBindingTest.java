package com.cooker.shopping;

import static org.assertj.core.api.Assertions.*;
import com.cooker.shopping.config.MockCatalogProperties;
import com.cooker.shopping.provider.MockProductProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MockCatalogBindingTest {
    @Test void 默认空目录与显式标准ID配置真实绑定(){var empty=new MockCatalogProperties();assertThat(empty.getProducts()).isEmpty();assertThat(empty.getCatalogVersion()).isEqualTo("empty-v1");var config=new Binder(new MapConfigurationPropertySource(Map.ofEntries(Map.entry("cooker.shopping.mock.catalog-version","binding-v1"),Map.entry("cooker.shopping.mock.products[0].product-id","mock-a"),Map.entry("cooker.shopping.mock.products[0].ingredient-id","42"),Map.entry("cooker.shopping.mock.products[0].name","Mock示例"),Map.entry("cooker.shopping.mock.products[0].city-codes[0]","110000"),Map.entry("cooker.shopping.mock.products[0].available","true"),Map.entry("cooker.shopping.mock.products[0].package-amount","100"),Map.entry("cooker.shopping.mock.products[0].package-unit","G"),Map.entry("cooker.shopping.mock.products[0].package-price","10.00"),Map.entry("cooker.shopping.mock.products[0].currency","CNY")))).bind("cooker.shopping.mock",Bindable.of(MockCatalogProperties.class)).get();assertThat(config.getProducts().getFirst().ingredientId()).isEqualTo(42L);assertThat(new MockProductProvider(config).lookup("110000",List.of()).catalogVersion()).isEqualTo("binding-v1");}
}
