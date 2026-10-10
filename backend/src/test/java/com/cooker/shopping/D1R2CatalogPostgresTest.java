package com.cooker.shopping;

import static org.assertj.core.api.Assertions.*;
import com.cooker.conversation.dto.ShoppingSource;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.impl.MealMaterialCalculator;
import com.cooker.shopping.config.MockCatalogProperties;
import com.cooker.shopping.dto.ShoppingRequest.*;
import com.cooker.shopping.provider.*;
import com.cooker.shopping.provider.ProductProvider.*;
import com.cooker.shopping.service.*;
import com.cooker.shopping.vo.ShoppingView;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 新目录真实配置绑定、有限审定SQL及纯采购链路；只用独立临时PG，最终库不造用户/餐单。 */
@Testcontainers
class D1R2CatalogPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("cooker-postgres:pg18-zhparser").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cooker_rebuild_d1r2_20261007").withUsername("root").withPassword("test-only");
    static JdbcTemplate jdbc;static MockCatalogProperties catalog,original;static JsonNode manifest;static String reviewSql;
    static Path artifact(String name) {return Path.of("..","scripts","d1-r",name).toAbsolutePath();}
    static MockCatalogProperties load(Resource resource) {
        var yaml=new YamlPropertiesFactoryBean();yaml.setResources(resource);
        var map=new HashMap<String,Object>();yaml.getObject().forEach((k,v)->map.put(k.toString(),v));
        return new Binder(new MapConfigurationPropertySource(map)).bind("cooker.shopping.mock",Bindable.of(MockCatalogProperties.class)).get();
    }
    @BeforeAll static void initialize() throws Exception {
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).load().migrate();jdbc=new JdbcTemplate(source);
        catalog=load(new FileSystemResource(artifact("mock-catalog-d1r2-20261007.yml")));
        original=load(new ClassPathResource("fixtures/d1-r2/mock-catalog-original.yml"));
        manifest=new ObjectMapper().readTree(Files.readString(artifact("mock-bindings-d1r2-20261007.json")));
        for(var b:manifest.path("bindings")) jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES(?,?,'UNREVIEWED')",b.path("finalId").asLong(),b.path("canonicalName").asText());
        jdbc.update("INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES(9999,'未审材料','UNREVIEWED')");
        reviewSql=Files.readString(artifact("Review-MockBindings-D1R2.sql"));jdbc.execute(reviewSql);
    }
    @Test void 完整12新目录绑定准确ID且全部旧商品规格量价城市标识不变() {
        assertThat(catalog.getProducts()).hasSize(12);assertThat(manifest.path("bindings")).hasSize(12);
        assertThat(catalog.getCatalogVersion()).isEqualTo(manifest.path("catalogVersion").asText());
        for(var b:manifest.path("bindings")) {
            long id=b.path("finalId").asLong();String productId=b.path("productId").asText();
            assertThat(jdbc.queryForObject("SELECT canonical_name FROM ingredient WHERE id=?",String.class,id)).isEqualTo(b.path("canonicalName").asText());
            var p=catalog.getProducts().stream().filter(v->v.productId().equals(productId)).findFirst().orElseThrow();
            var old=original.getProducts().stream().filter(v->v.productId().equals(productId)).findFirst().orElseThrow();
            assertThat(p).isEqualTo(new Product(old.productId(),id,old.name(),old.cityCodes(),old.available(),old.packageAmount(),old.packageUnit(),old.packagePrice(),old.currency(),old.referenceUnitPrice(),old.referenceUnit()));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE normalization_status='REVIEWED'",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT normalization_status FROM ingredient WHERE id=9999",String.class)).isEqualTo("UNREVIEWED");
    }
    private ShoppingSource source(String unit,String label) {
        long id=catalog.getProducts().stream().filter(p->p.productId().equals("mock-shrimp-194")).findFirst().orElseThrow().ingredientId();
        var a=new MealRecipeFacts.Ingredient(101,id,"REVIEWED","鲜虾","鲜虾原始量",null,null,unit,label,false,1,10);
        var b=new MealRecipeFacts.Ingredient(102,id,"REVIEWED","鲜虾","鲜虾原始量",null,null,unit,label,true,2,10);
        var r=new MealRecipeFacts(10,"隔离测试菜","meat_dish",new MealRecipeFacts.RoleEvidence(MealRole.DISH,"测试","test"),10,"a".repeat(64),null,10,List.of(a,b),List.of(),List.of(),null,"b".repeat(64));
        return new ShoppingSource(1,2,1,3,3,new BigDecimal("1"),List.of(new ShoppingSource.RecipeReference(10,"b".repeat(64))),MealMaterialCalculator.calculate(List.of(r),3));
    }
    private ShoppingView view(ShoppingSource s,ShoppingDemandCalculator.Demand d) {return new ShoppingView(1,"1","test","CURRENT",ShoppingDemandCalculator.source(s),d.lines(),d.items(),d.status(),"NOT_MATCHED",d.costs());}
    private List<Confirmation> confirmations(String amount,String unit) {return List.of(new Confirmation("10:101",Availability.NEED_BUY,amount==null?null:new BigDecimal(amount),unit),new Confirmation("10:102",Availability.NEED_BUY,amount==null?null:new BigDecimal(amount),unit));}
    @Test void 未知保持null经用户明确确认后按最终ID匹配同商品合包并保留预算原因() {
        var s=source("G","g");var pending=ShoppingDemandCalculator.calculate(s,confirmations(null,null));var pendingView=view(s,pending);
        assertThat(ProductMatchCalculator.needs(pendingView)).isEmpty();
        var unknown=ProductMatchCalculator.calculate(pendingView,"110000",new Offers("NOT_QUERIED",List.of()));
        assertThat(unknown.lines()).allMatch(l->l.reason().contains("原份数未知")&&l.shortageAmount()==null);
        assertThat(unknown.costs().consumptionCost()).isNull();assertThat(unknown.costs().budgetStatus()).isEqualTo("UNKNOWN");
        var confirmed=view(s,ShoppingDemandCalculator.calculate(s,confirmations("10","G")));
        var provider=new MockProductProvider(catalog);var result=ProductMatchCalculator.calculate(confirmed,"110000",provider.lookup("110000",ProductMatchCalculator.needs(confirmed)));
        assertThat(result.lines()).allMatch(l->l.status().equals("MATCHED")&&l.product().purchaseUrl()==null);
        assertThat(result.purchaseGroups()).hasSize(1);assertThat(result.purchaseGroups().getFirst().packageCount()).isEqualTo("1");
        assertThat(result.costs().consumptionCost()).isEqualTo("1.20");assertThat(result.costs().purchaseOutlay()).isEqualTo("18.00");assertThat(result.costs().budgetStatus()).isEqualTo("OVER_BUDGET");
        assertThat(result.provider()).isEqualTo("MOCK");assertThat(result.realData()).isFalse();assertThat(s.materials().recipes().getFirst().facts().servings()).isNull();
    }
    @Test void 最终目录不靠同名替代ID且质量体积计数保持维度隔离() {
        var provider=new MockProductProvider(catalog);
        assertThat(provider.lookup("110000",List.of(new Need("未绑定",9999,new BigDecimal("10"),"G"))).selections().getFirst().status()).isEqualTo("NO_PRODUCT");
        assertThat(provider.lookup("110000",List.of(new Need("计数鲜虾",138,new BigDecimal("2"),"COUNT"))).selections().getFirst().status()).isEqualTo("UNIT_UNSUPPORTED");
        assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source("G","g"),confirmations("10","ML"))).isInstanceOf(com.cooker.common.ApiException.class);
        assertThatThrownBy(()->ShoppingDemandCalculator.calculate(source("COUNT","个"),confirmations("10","G"))).isInstanceOf(com.cooker.common.ApiException.class);
    }
    @Test void 审定脚本拒绝重复执行且不批量升级未审行() {
        assertThatThrownBy(()->jdbc.execute(reviewSql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE normalization_status='REVIEWED'",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT normalization_status FROM ingredient WHERE id=9999",String.class)).isEqualTo("UNREVIEWED");
    }
}
