package com.cooker.recipe.parser;

import static org.assertj.core.api.Assertions.*;
import com.cooker.recipe.dto.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 使用原公开文件及最小反例复现D1-R残留；不依赖开发数据库或收费模型。 */
class D1R2ParserRegressionTest {
    private final HowToCookMarkdownParser parser = new HowToCookMarkdownParser();
    private ParsedRecipe fixture(String name,String source) throws Exception {
        try(var input=getClass().getResourceAsStream("/fixtures/d1-r2/"+name+".md")) {
            assertThat(input).isNotNull();return parser.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8),source);
        }
    }
    private ParsedRecipe minimal(String lines) {return parser.parse("# 测试菜\n## 计算\n"+lines+"\n## 操作\n1. 加热\n","test/菜.md");}
    private ParsedIngredient named(ParsedRecipe recipe,String name) {return recipe.ingredients().stream().filter(i->name.equals(i.rawName())).findFirst().orElseThrow();}
    @Test void 公开蒸花卷尺寸器具排除而真实食材保留() throws Exception {
        var result=fixture("蒸花卷","breakfast/蒸花卷.md");
        assertThat(result.ingredients()).extracting(ParsedIngredient::rawName).containsExactly("冷冻花卷","水");
        assertThat(named(result,"水").amountMin()).isEqualByComparingTo("400");
    }
    @Test void 公开太阳蛋用量语句名称分离而每个鸡蛋的依赖量未知() throws Exception {
        var result=fixture("太阳蛋","breakfast/太阳蛋.md");
        assertThat(result.ingredients()).extracting(ParsedIngredient::rawName).containsExactly("鸡蛋","盐","油");
        assertThat(named(result,"鸡蛋").amountMin()).isEqualByComparingTo("1");
        assertThat(named(result,"油").amountMin()).isNull();
        assertThat(named(result,"油").rawLine()).isEqualTo("油的用量为 5 mL 每个鸡蛋。");
    }
    @Test void 公开整批产出说明不作食材且恢复可靠必备材料() throws Exception {
        var result=fixture("牛油火锅底料","semi-finished/牛油火锅底料.md");
        assertThat(result.ingredients()).extracting(ParsedIngredient::rawName).doesNotContain("每份原料可制作");
        assertThat(named(result,"牛油").amountMin()).isEqualByComparingTo("4500");
        assertThat(result.servings()).isNull();
        assertThat(result.warnings()).anyMatch(w->w.contains("整批产出"));
    }
    @Test void 公开米饭水位说明不扫描指节中的一作为计数() throws Exception {
        var result=fixture("电饭煲蒸米饭","staple/米饭/电饭煲蒸米饭.md");
        assertThat(named(result,"江南米").amountMin()).isNull();
        assertThat(named(result,"北方大米").amountMin()).isNull();
        assertThat(result.ingredients()).extracting(ParsedIngredient::rawName).noneMatch(n->n.contains("食指")||n.startsWith("一般"));
        assertThat(result.warnings()).anyMatch(w->w.contains("水位说明"));
    }
    @Test void 公开冷吃兔公式名称分离但不求值或拆分组合() throws Exception {
        var result=fixture("冷吃兔","meat_dish/冷吃兔.md");
        for(String name:new String[]{"盐","味精","蚝油","料酒","油","蒜","姜","干辣椒"}) {
            assertThat(named(result,name).amountMin()).isNull();assertThat(named(result,name).unitCode()).isNull();
        }
        assertThat(named(result,"盐").rawLine()).isEqualTo("盐量 = 兔肉斤数 * 2 克");
        assertThat(named(result,"小葱/大葱/洋葱").amountMin()).isNull();
        assertThat(result.ingredients()).extracting(ParsedIngredient::rawName).doesNotContain("小葱","大葱","洋葱");
        assertThat(result.warnings()).anyMatch(w->w.contains("公式依赖"));
    }
    @Test void 最小器具修饰不误删蒸锅用水和普通食材() {
        var result=minimal("- 圆碟子，直径 28cm\n- 蒸架直径20cm\n- 蒸锅用水 1000ml\n- 米 100g\n- 锅巴 20g");
        assertThat(result.ingredients()).extracting(ParsedIngredient::rawName).containsExactly("蒸锅用水","米","锅巴");
        assertThat(named(result,"米").amountMin()).isEqualByComparingTo("100");
    }
    @Test void 最小公式与直接等号数量区分且组合整体保留() {
        var result=minimal("- 盐量 = 肉重 * 2 克\n- 生抽/老抽总量 = 肉重 * 5 ml\n- 小龙虾 = 1 kg");
        assertThat(named(result,"盐").amountMin()).isNull();assertThat(named(result,"生抽/老抽").amountMin()).isNull();
        assertThat(named(result,"小龙虾").amountMin()).isEqualByComparingTo("1000");
        assertThat(named(result,"小龙虾").unitCode()).isEqualTo("G");
    }
}
