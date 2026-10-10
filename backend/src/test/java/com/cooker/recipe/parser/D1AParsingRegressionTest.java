package com.cooker.recipe.parser;

import static org.assertj.core.api.Assertions.*;
import com.cooker.recipe.dto.ParsedIngredient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** D1-A：锁定公开原文真实格式和未知语义边界，修复前先执行。 */
class D1AParsingRegressionTest {
    private final HowToCookMarkdownParser parser = new HowToCookMarkdownParser();
    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/fixtures/d1-a/" + name + ".md")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private ParsedIngredient line(String raw) {
        return parser.parse("# 测试菜\n## 计算\n两人食用。\n- " + raw + "\n## 操作\n1. 加热", "home/test.md").ingredients().getFirst();
    }
    @Test void 小龙虾分隔符与明确中文量() throws Exception {
        var parsed = parser.parse(fixture("lobster"), "aquatic/lobster.md");
        assertThat(parsed.ingredients().getFirst().rawName()).isEqualTo("小龙虾");
        assertThat(parsed.ingredients().getFirst().amountMin()).isEqualByComparingTo("2");
        assertThat(parsed.ingredients().getFirst().unitLabel()).isEqualTo("斤");
        var leaf = parsed.ingredients().get(2);
        assertThat(leaf.rawName()).isEqualTo("香叶");
        assertThat(leaf.amountMin()).isEqualByComparingTo("2");
        assertThat(leaf.rawLine()).isEqualTo("香叶 = 两片");
        assertThat(parsed.servings()).isNull();
    }
    @Test void 黑鳕鱼保留部位而多组量不选一个() throws Exception {
        var parsed = parser.parse(fixture("cod"), "aquatic/cod.md");
        var fish = parsed.ingredients().getFirst();
        assertThat(fish.rawName()).isEqualTo("黑鳕鱼，带皮");
        assertThat(fish.amountMin()).isNull();
        assertThat(parsed.ingredients().get(1).rawName()).isEqualTo("青葱，葱白");
        assertThat(parsed.ingredients().get(1).amountMin()).isEqualByComparingTo("25");
        assertThat(parsed.servings()).isNull();
    }
    @Test void 标点与大小写体积标签一致() {
        assertThat(line("姜，13g。").rawName()).isEqualTo("姜");
        assertThat(line("姜，13g。").amountMin()).isEqualByComparingTo("13");
        assertThat(line("料酒，5mL。").unitLabel()).isEqualTo("ml");
    }
    @Test void 大单位只归一化一次() {
        assertThat(line("面粉 1kg").amountMin()).isEqualByComparingTo("1000");
        assertThat(line("面粉 1kg").unitLabel()).isEqualTo("g");
        assertThat(line("水 半升").amountMin()).isEqualByComparingTo("500");
        assertThat(line("水 半升").unitLabel()).isEqualTo("ml");
    }
    @Test void 中文明确数字与小数范围() {
        assertThat(line("盐 十二点五克").amountMin()).isEqualByComparingTo("12.5");
        assertThat(line("蒜 二至三瓣").amountMin()).isEqualByComparingTo("2");
        assertThat(line("蒜 二至三瓣").amountMax()).isEqualByComparingTo("3");
        assertThat(line("蒜 二至三瓣").rawName()).isEqualTo("蒜");
    }
    @Test void 约量无量不伪造确定数值() {
        for (String raw : new String[]{"盐适量", "葱 一小把", "油 约10ml", "水 10ml左右"}) {
            assertThat(line(raw).amountMin()).as(raw).isNull();
        }
        assertThat(line("盐适量").rawName()).isEqualTo("盐");
    }
    @Test void 无计算用料时恢复必备原料() throws Exception {
        var parsed = parser.parse(fixture("pork"), "meat_dish/pork.md");
        assertThat(parsed.ingredients()).hasSize(15);
        assertThat(parsed.ingredients().getFirst().rawName()).isEqualTo("里脊肉");
        assertThat(parsed.ingredients().getFirst().amountMin()).isEqualByComparingTo("200");
        // 真实原文是1-2人，新策略取完整范围下限1；材料恢复规则不变。
        assertThat(parsed.servings()).isEqualTo(1);
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("人数依据=RANGE_MIN_AS_BASELINE"));
    }
    @Test void 章节工具与可选区分() {
        var parsed = parser.parse("# 菜\n## 必备原料和工具\n原料：\n- 鸡蛋 2个\n工具：\n- 密封袋\n- 炒锅\n可选（调味）\n- 盐 1克\n## 计算\n未标人数\n## 操作\n1. 加热", "home/test.md");
        assertThat(parsed.ingredients()).extracting(ParsedIngredient::rawName).containsExactly("鸡蛋", "盐");
        assertThat(parsed.ingredients().get(1).optional()).isTrue();
    }
    @Test void 多规格表格只待审定不擅选一列() throws Exception {
        var parsed = parser.parse(fixture("chiffon"), "dessert/chiffon.md");
        assertThat(parsed.ingredients()).isEmpty();
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("表格") && w.contains("待复核"));
    }
    @Test void 确定计数不受备注约重影响且重复单位范围保持() {
        assertThat(line("鸡蛋 2个（约100g）").amountMin()).isEqualByComparingTo("2");
        assertThat(line("白胡椒粉 5g-10g").amountMax()).isEqualByComparingTo("10");
        assertThat(line("料酒 15g 到 30g").amountMax()).isEqualByComparingTo("30");
        assertThat(line("125ml 淡奶油").rawName()).isEqualTo("淡奶油");
        assertThat(line("一个带皮玉米").rawName()).isEqualTo("带皮玉米");
        assertThat(line("肉蟹 1只 * 份数").amountMin()).isNull();
    }
    @Test void 必备章节器具与中文口语不伪装明确材料() {
        var parsed = parser.parse("# 菜\n## 必备原料和工具\n- 糯米 800g\n- 温度计（可选，推荐）\n- 干净密封玻璃或陶瓷罐 1个\n## 计算\n整批配方\n## 操作\n1. 加热", "home/test.md");
        assertThat(parsed.ingredients()).extracting(ParsedIngredient::rawName).containsExactly("糯米");
        assertThat(line("盐 二百五克").amountMin()).isNull();
        assertThat(line("盐 二百五克-三百克").amountMin()).isNull();
        assertThat(line("盐 一二克").amountMin()).isNull();
        assertThat(line("甜酒曲一袋（8g）").rawName()).isEqualTo("甜酒曲");
        assertThat(line("酱油 1 茶匙").unitLabel()).isEqualTo("茶匙");
    }
    @Test void 多配方组合不能展平成全部必买材料() throws Exception {
        var parsed = parser.parse(fixture("variants"), "meat_dish/variants.md");
        assertThat(parsed.ingredients()).isEmpty();
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("多配方组合") && w.contains("待复核"));
    }
    @Test void 可选章节结束后必备标志恢复且既有厨具契约不扩大() {
        var parsed = parser.parse("# 菜\n## 必备原料和工具\n可选配料\n- 盐 1g\n必须配料\n- 鸡蛋 2个\n工具：\n- 温度计\n## 操作\n1. 加热", "home/test.md");
        assertThat(parsed.ingredients().getFirst().optional()).isTrue();
        assertThat(parsed.ingredients().get(1).optional()).isFalse();
        assertThat(parsed.tools()).doesNotContain("温度计");
    }
    @Test void 人数可明确而份产量尺寸不冒充人数() {
        var people = parser.parse("# 菜\n## 计算\n两人食用。\n- 盐 1g\n## 操作\n1. 加热", "home/test.md");
        assertThat(people.servings()).isEqualTo(2);
        for (String text : new String[]{"每2份", "6寸蛋糕", "成品10个"}) {
            assertThat(parser.parse("# 菜\n## 计算\n" + text + "\n- 盐 1g\n## 操作\n1. 加热", "home/test.md").servings()).as(text).isNull();
        }
    }
    private com.cooker.recipe.dto.ParsedRecipe v2People(String text) {
        return parser.parse("# 测试菜\n## 计算\n" + text
                + "\n- 盐 1g\n## 操作\n1. 加热", "home/test.md");
    }

    /** 人数约量按给定值作为参考基准，警告保留策略依据，材料约量规则不变。 */
    @org.junit.jupiter.params.ParameterizedTest(name = "V2约人数：{0}→{1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "每份（约 2 人份）：,2", "约 3人食用,3", "大概 3 人食用,3", "大约  3人食用,3",
            "3人左右食用,3", "3 人份 左右,3", "3人上下,3", "约为 3人,3", "大概可供 3人,3",
            "大致 3人,3", "约（3人）,3", "约2人,2", "2人左右,2", "2人食用；约2人,2"
    })
    void v2约人数按值且保留依据(String text, int expected) {
        var result = v2People(text);
        assertThat(result.servings()).as(text).isEqualTo(expected);
        assertThat(result.warnings()).anyMatch(w -> w.startsWith(HowToCookMarkdownParser.RULE_VERSION + "：人数依据=APPROXIMATE_PEOPLE_AS_BASELINE；"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("不代表精确食量或自动采购量"));
    }

    /** 必须解析两端并验证有效性，倒置/小数端点另有反例，不能只截取末端。 */
    @org.junit.jupiter.params.ParameterizedTest(name = "V2人数范围：{0}→{1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "2到3人食用,2", "二至三人食用,2", "2 至 3 人,2", "2-3人,2", "2～3人,2",
            "两到三人,2", "2 人到 3 人,2", "两人至三人,2", "2 — 3 人,2", "2或3人,2",
            "约2到3人,2", "2到3人左右,2", "2-2人,2", "十二至十五人,12", "2人食用；2到3人,2"
    })
    void v2人数完整范围取下限且保留依据(String text, int expected) {
        var result = v2People(text);
        assertThat(result.servings()).as(text).isEqualTo(expected);
        assertThat(result.warnings()).anyMatch(w -> w.startsWith(HowToCookMarkdownParser.RULE_VERSION + "：人数依据=RANGE_MIN_AS_BASELINE；"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("不代表精确食量或自动采购量"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "V2明确人数：{0}→{1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "2人食用,2", "两人食用,2", "十二人食用,12", "每份可供2人食用,2", "2 人份,2",
            "原料约50克，供2人食用,2", "2人食用，约20分钟完成,2", "2人食用；每2人一份,2", "- 2 人 1 顿 520g,2", "1. 2 人份,2"
    })
    void v2明确人数不被无关约量污染(String text, int expected) {
        var result = v2People(text);
        assertThat(result.servings()).as(text).isEqualTo(expected);
        assertThat(result.warnings()).noneMatch(w -> w.contains("人数依据="));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "V2未知或非法基准：{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "每2份", "2份", "6寸蛋糕", "成品10个", "整批7张饼", "未提供人数",
            "2人食用；另一配方3人食用", "2人；约3人", "约2人；3人左右", "2-3人；4人", "2-3人；3-4人",
            "0人", "零人", "-2人", "负两人", "半人", "2.5人", "二点五人", "2147483648人",
            "3到2人", "三至两人", "2-0人", "0到2人", "2到-3人", "-2到3人", "2.5到3人", "2到3.5人",
            "二点五至三人", "两至三点五人", "两三人", "二百五人", "2/3人", "2 / 3人", "1.5只/三人；2根/三人"
    })
    void v2非法冲突份产量尺寸仍未知(String text) {
        var result = v2People(text);
        assertThat(result.servings()).as(text).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("待复核") || w.contains("未明确人数"));
    }

}
