package com.cooker.recipe.parser;

import com.cooker.recipe.dto.ParsedIngredient;
import com.cooker.recipe.dto.ParsedRecipe;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * HowToCook 兼容 Markdown 的纯内存解析器。
 *
 * <p>此类刻意不依赖数据库和导入批次，避免把后续用户私有菜谱也绑定到系统快照入口。
 * 模板历史存在差异，因此只把菜名和操作步骤作为硬失败条件，其余字段的失败均以 warning 表达。</p>
 */
@Component
public class HowToCookMarkdownParser {
    public static final String RULE_VERSION = "d1-r2-parser-v3";
    private static final String NUMBER = "(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百千万半]+(?:点[零〇一二两三四五六七八九]+)?)";
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final Pattern DIFFICULTY = Pattern.compile("预估烹饪难度\\s*[：:]\\s*([★☆]{1,5})");
    private static final Pattern CALORIES = Pattern.compile("预估卡路里\\s*[：:]\\s*(\\d+(?:\\.\\d+)?)");
    // 完整匹配两端并保留符号供人数校验，不能跳过非法端点只取末端人数。
    private static final Pattern SERVINGS = Pattern.compile(
            "(?<![\\d./+−负零〇一二两三四五六七八九十百千万半点到至或~～—–-])((?:[-−负])?" + NUMBER
                    + ")(?:\\s*(?:人(?:份)?\\s*)?[-~～至到或—–]\\s*((?:[-−负])?" + NUMBER + "))?\\s*人");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|小時|分钟|分鐘|分|h|min)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDERED_STEP = Pattern.compile("^\\s*\\d+[.、]\\s*(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+?)\\s*$");
    // 查找数量片段而非把整句作为名称；多组量只诊断，不挑其中一个。
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![\\d./])(" + NUMBER + ")(?:\\s*[-~～至—–]\\s*(" + NUMBER + "))?\\s*(kg|公斤|千克|毫升|ml|克|g|升|l|个|個|只|条|條|瓣|根|株|盒|片|块|塊|勺|匙|杯|斤|袋|包|颗|茶匙|汤匙|茶勺|汤勺)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VAGUE = Pattern.compile("约|大约|左右|上下|大概|适量|少许|若干|一小把|一把|一些|按.*口味");
    private static final Pattern GROUP_TITLE = Pattern.compile("^(必须配料|必需配料|可选配料|原料|调料|调味料|工具|主料|辅料|配料|必备原料和工具)[：: ]*$");
    private static final List<String> TOOL_WORDS = List.of(
            "空气炸锅", "电饭煲", "微波炉", "高压锅", "压力锅", "平底锅", "炒锅", "汤锅", "蒸锅", "砂锅",
            "烤箱", "蒸箱", "电磁炉", "燃气灶", "破壁机", "搅拌机", "料理机", "榨汁机", "面包机",
            "打蛋器", "烤盘", "模具", "菜刀", "砧板", "锅");
    // 仅用于从材料中排除器具，不改变既有厨具投影词典和T5筛选契约。
    private static final List<String> MATERIAL_TOOL_WORDS = java.util.stream.Stream.concat(TOOL_WORDS.stream(),
            java.util.stream.Stream.of("密封袋", "油纸", "保鲜膜", "擀面杖", "小锅", "搅拌工具", "温度计", "模具或碗", "筷子", "刮刀", "圆碟子", "蒸架")).toList();

    public ParsedRecipe parse(String rawMarkdown, String relativeSourcePath) {
        List<String> warnings = new ArrayList<>();
        String markdown = normalize(rawMarkdown);
        String category = categoryFromPath(relativeSourcePath, warnings);
        Matcher titleMatcher = H1.matcher(markdown);
        if (!titleMatcher.find()) {
            return ParsedRecipe.failed(category, "缺少一级标题，无法确定菜名", warnings);
        }
        String title = cleanTitle(titleMatcher.group(1));
        if (title.isBlank()) {
            return ParsedRecipe.failed(category, "菜名为空", warnings);
        }

        Map<String, String> sections = sections(markdown);
        List<String> steps = parseSteps(sections.get("操作"));
        if (steps.isEmpty()) {
            return ParsedRecipe.failed(category, "缺少可识别的操作步骤", warnings);
        }

        String summary = parseSummary(markdown, titleMatcher.end());
        Integer difficulty = parseDifficulty(markdown, warnings);
        Integer calories = parseCalories(markdown, warnings);
        Integer servings = parseServings(sections.get("计算"), warnings);
        Integer minutes = parseDuration(summary + "\n" + sections.getOrDefault("计算", ""), warnings);
        List<ParsedIngredient> ingredients = parseIngredients(sections.get("计算"), sections.get("必备原料和工具"), warnings);
        Set<String> tools = parseTools(sections.get("必备原料和工具"));

        return new ParsedRecipe(true, null, title, category, emptyToNull(summary), difficulty, minutes,
                servings, calories, emptyToNull(sections.get("附加内容")), ingredients, List.copyOf(tools),
                steps, List.copyOf(warnings));
    }

    private String normalize(String raw) {
        String noComments = HTML_COMMENT.matcher(raw == null ? "" : raw).replaceAll("");
        // 只移除图片本身，保留其他 Markdown 文本；链接显示文字可作为普通文本保留。
        return noComments.replaceAll("(?m)^!\\[[^]]*]\\([^)]*\\)\\s*$", "").replace("\r\n", "\n");
    }

    private String categoryFromPath(String relativePath, List<String> warnings) {
        String normalized = relativePath == null ? "" : relativePath.replace((char) 92, '/');
        String[] pieces = normalized.split("/");
        if (pieces.length < 2 || pieces[0].isBlank()) {
            warnings.add("无法从相对路径识别分类");
            return "unknown";
        }
        return pieces[0];
    }

    private Map<String, String> sections(String markdown) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        Matcher matcher = H2.matcher(markdown);
        List<int[]> positions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
            positions.add(new int[]{matcher.start(), matcher.end()});
        }
        for (int i = 0; i < positions.size(); i++) {
            int end = i + 1 < positions.size() ? positions.get(i + 1)[0] : markdown.length();
            result.put(names.get(i), markdown.substring(positions.get(i)[1], end).trim());
        }
        return result;
    }

    private String parseSummary(String markdown, int titleEnd) {
        int nextSection = markdown.indexOf("\n## ", titleEnd);
        String candidate = markdown.substring(titleEnd, nextSection < 0 ? markdown.length() : nextSection);
        candidate = candidate.replaceAll("(?m)^预估(?:烹饪难度|卡路里).*$", "");
        return candidate.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
    }

    private Integer parseDifficulty(String markdown, List<String> warnings) {
        Matcher matcher = DIFFICULTY.matcher(markdown);
        if (!matcher.find()) {
            warnings.add("未解析到预估烹饪难度");
            return null;
        }
        int stars = (int) matcher.group(1).chars().filter(c -> c == '★').count();
        if (stars == 0) {
            warnings.add("预估烹饪难度不包含实心星");
            return null;
        }
        return stars;
    }

    private Integer parseCalories(String markdown, List<String> warnings) {
        Matcher matcher = CALORIES.matcher(markdown);
        if (!matcher.find()) {
            warnings.add("未解析到预估卡路里");
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1)).intValueExact();
        } catch (ArithmeticException ex) {
            warnings.add("预估卡路里不是整数，未写入结构化字段");
            return null;
        }
    }

    private Integer parseServings(String calculation, List<String> warnings) {
        String text = Optional.ofNullable(calculation).orElse("");
        Matcher matcher = SERVINGS.matcher(text);
        Integer found = null;
        List<String> basisWarnings = new ArrayList<>();
        while (matcher.find()) {
            // 只检查紧邻人数的语法，材料约量和耗时不改变人数基准。
            String prefix = text.substring(0, matcher.start());
            String suffix = text.substring(matcher.end());
            // 行首项目符号或序号不是人数负号；“-2人”仍由带符号的完整匹配拒绝。
            boolean listBullet = prefix.matches("(?s)(?:.*\\n)?[ \\t]*(?:[-*+]|\\d+[.、])[ \\t]+$");
            if (!listBullet && prefix.matches("(?s).*[./到至或~～—–负−-]\\s*$")) {
                warnings.add(RULE_VERSION + "：人数格式不明确，待复核");
                return null;
            }
            boolean approximate = prefix.matches("(?s).*(?:大约|大概|大致|约莫|约)(?:为|可供)?\\s*[（(]?\\s*$")
                    || suffix.matches("(?s)^\\s*(?:份\\s*)?[）)]?\\s*(?:左右|上下|大约|大概|大致|约).*");
            try {
                int value = number(matcher.group(1)).intValueExact();
                boolean range = matcher.group(2) != null;
                // 两端都必须是正整数且顺序合法，不能忽略非法上限或交换倒置端点。
                Integer upper = range ? number(matcher.group(2)).intValueExact() : null;
                if (value <= 0 || (range && (upper <= 0 || upper < value))) {
                    warnings.add(RULE_VERSION + "：人数非正或范围倒置，待复核");
                    return null;
                }
                if (found != null && found != value) {
                    warnings.add(RULE_VERSION + "：人数基准冲突，待复核");
                    return null;
                }
                found = value;
                if (range) {
                    basisWarnings.add(RULE_VERSION + "：人数依据=RANGE_MIN_AS_BASELINE；原文范围“"
                            + matcher.group() + "”完整解析为" + value + "到" + upper
                            + "人，采用下限" + value + "作为参考折算基准；不代表精确食量或自动采购量，仍需人工确认");
                } else if (approximate) {
                    basisWarnings.add(RULE_VERSION + "：人数依据=APPROXIMATE_PEOPLE_AS_BASELINE；约人数采用给定值"
                            + value + "作为参考折算基准；原文保留约量；不代表精确食量或自动采购量，仍需人工确认");
                }
            } catch (RuntimeException ex) {
                warnings.add(RULE_VERSION + "：人数无法可靠转换为正整数，待复核");
                return null;
            }
        }
        if (found == null) warnings.add(RULE_VERSION + "：未明确人数；份、尺寸和整批产量不转换为人数");
        // 全部基准校验后才发布依据，避免冲突人数残留可采用的约量/范围提示。
        else warnings.addAll(basisWarnings);
        return found;
    }

    private Integer parseDuration(String text, List<String> warnings) {
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.find()) {
            warnings.add("未解析到估算时间");
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            return (unit.equals("小时") || unit.equals("小時") || unit.equals("h"))
                    ? value.multiply(BigDecimal.valueOf(60)).intValueExact() : value.intValueExact();
        } catch (ArithmeticException ex) {
            warnings.add("估算时间无法转换为整分钟");
            return null;
        }
    }

    private List<ParsedIngredient> parseIngredients(String calculation, String required, List<String> warnings) {
        // 表格配方可能包含多个尺寸/规格，禁止回退到必备列表制造一个虚假的单配方。
        if (calculation != null && calculation.lines().anyMatch(line -> line.strip().startsWith("|"))) {
            warnings.add(RULE_VERSION + "：计算章节含表格配方，待复核规格；未选择任何列");
            return List.of();
        }
        if (calculation != null && calculation.contains("种组合")) {
            warnings.add(RULE_VERSION + "：计算章节描述多配方组合，待复核；未把候选清单选成单一配方");
            return List.of();
        }
        List<ParsedIngredient> result = ingredientLines(calculation, warnings);
        if (result.isEmpty()) {
            result = ingredientLines(required, warnings);
            if (!result.isEmpty()) warnings.add(RULE_VERSION + "：计算章节无可靠列表，恢复必备原料章节；未读取操作步骤");
        }
        if (result.isEmpty()) warnings.add(RULE_VERSION + "：未找到可靠原料列表，待复核");
        return result;
    }

    private List<ParsedIngredient> ingredientLines(String section, List<String> warnings) {
        List<ParsedIngredient> result = new ArrayList<>();
        if (section == null) return result;
        boolean toolSection = false;
        boolean optionalSection = false;
        for (String line : section.lines().toList()) {
            String heading = line.strip().replaceFirst("^#{1,6}\\s*", "").replace("**", "");
            Matcher bullet = BULLET.matcher(line);
            String candidate = bullet.matches() ? bullet.group(1).trim() : heading;
            if (GROUP_TITLE.matcher(candidate).matches() || !bullet.matches()) {
                if (candidate.matches(".*工具[：: ]*")) { toolSection = true; optionalSection = false; }
                else if (candidate.startsWith("可选") || candidate.startsWith("选用")) { toolSection = false; optionalSection = true; }
                else if (candidate.matches("(必须配料|必需配料|原料|配料|调料|调味料|主料|辅料|.*本体)[：: ]*")) { toolSection = false; optionalSection = false; }
                continue;
            }
            String raw = candidate;
            if (toolSection || raw.isBlank() || isTool(raw) || raw.startsWith("原则：") || raw.startsWith("原则:")) continue;
            boolean optional = optionalSection || raw.contains("可选") || raw.contains("選用") || raw.contains("选用");
            String main = raw.replace("（可选）", "").replace("(可选)", "").replace("（選用）", "").replace("**", "").trim();
            // 整批产出及用水操作说明不是材料；过滤后可回退可靠的必备原料列表。
            if (main.matches("^每份原料可制作.*")) {
                warnings.add(RULE_VERSION + "：整批产出说明不作食材，保留原文：" + raw); continue;
            }
            if (main.matches("^一般.*(?:人|食用).*")) {
                warnings.add(RULE_VERSION + "：一般食用比例说明不作单一材料，保留原文：" + raw); continue;
            }
            if (main.matches("^当.+(?:放入|没过).*(?:水|水量).*")) {
                warnings.add(RULE_VERSION + "：条件用水说明未确定量，保留原文：" + raw); continue;
            }
            // 先截取明确的逗号前名称；水位/指节中的数字不可参与数量扫描。
            if (main.matches("^[^，,]+[，,].*(?:食指|指节|没过|水位).*")) {
                String name = cleanName(main.split("[，,]",2)[0]);
                warnings.add(RULE_VERSION + "：水位说明不能转换确定数量，待复核：" + raw);
                result.add(new ParsedIngredient(name,null,null,null,null,optional,raw,result.size()+1)); continue;
            }
            main = main.replaceFirst("^(.+?)的用量为\\s*", "$1 ");
            int equals = Math.max(main.indexOf('='),main.indexOf('＝'));
            if (equals > 0) {
                String right = main.substring(equals+1).replaceAll("[（(].*?[）)]", "").replaceAll("[。.;；]+$", "").strip();
                // 只有纯数字+单位才走既有确定量规则；任何依赖公式都不求值。
                if (!AMOUNT.matcher(right).matches()) {
                    String name = cleanName(main.substring(0,equals)).replaceFirst("(?:总量|用量|量)$", "").strip();
                    if (!name.isBlank()) {
                        warnings.add(RULE_VERSION + "：公式依赖未求值" + (name.matches(".*[、/或].*") ? "；组合材料未拆分" : "") + "，待复核：" + raw);
                        result.add(new ParsedIngredient(name,null,null,null,null,optional,raw,result.size()+1)); continue;
                    }
                }
            }
            // 备注保留在rawLine；名称中的木耳（干）等属性不能全局删除。
            Matcher matcher = AMOUNT.matcher(main);
            List<int[]> spans = new ArrayList<>();
            List<String[]> amounts = new ArrayList<>();
            while (matcher.find()) {
                if (insideNote(main, matcher.start())) continue;
                spans.add(new int[]{matcher.start(), matcher.end()});
                amounts.add(new String[]{matcher.group(1), matcher.group(2), matcher.group(3)});
            }
            String quantityText = main.replaceAll("[（(].*?[）)]", "");
            boolean dependent = quantityText.matches(".*(?:份数|每(?:人|份|个|只|条|根)|/人|/份|\\*|×).*");
            boolean approximate = VAGUE.matcher(quantityText).find() || dependent;
            String name = cleanName(spans.isEmpty() ? main.replaceFirst("[=＝：:]?\\s*(适量|少许|若干|一小把|一把|一些|按.*口味).*$", "") : main.substring(0, spans.getFirst()[0]));
            // 量在前、材料在后的确定格式（125ml 淡奶油/两个鸡蛋）也能保留可靠名称。
            if (name.isBlank() && amounts.size() == 1) name = cleanName(main.substring(spans.getFirst()[1]).replaceFirst("[（(].*$", "").strip());
            if (name.isBlank() || raw.matches("^[0-9]+\\s*(汤匙|茶匙|杯)\\s*[=＝].*")) {
                warnings.add(RULE_VERSION + "：原料名称不明确或单位说明，待复核：" + raw); continue;
            }
            BigDecimal min = null, max = null;
            String code = null, label = null;
            if (amounts.size() == 1 && !approximate) {
                String[] value = amounts.getFirst();
                try {
                    min = normalizeAmount(number(value[0]), value[2]);
                    max = normalizeAmount(number(value[1] == null ? value[0] : value[1]), value[2]);
                    if (min.signum() < 0 || min.compareTo(max) > 0 || main.substring(0, spans.getFirst()[0]).stripTrailing().endsWith("-")) {
                        min = null; max = null;
                    } else {
                        code = unitCode(value[2]);
                        label = standardLabel(value[2]);
                    }
                } catch (RuntimeException ex) { min = null; max = null; }
            }
            // 5g-10g、15g到30g是明确同维度范围；2片，450g是多组事实，不能选一个。
            if (amounts.size() == 2 && !approximate
                    && main.substring(spans.get(0)[1], spans.get(1)[0]).strip().matches("[-~～至到—–]")
                    && amounts.get(0)[1] == null && amounts.get(1)[1] == null
                    && unitCode(amounts.get(0)[2]).equals(unitCode(amounts.get(1)[2]))
                    && (List.of("G", "ML").contains(unitCode(amounts.get(0)[2]))
                        || standardLabel(amounts.get(0)[2]).equals(standardLabel(amounts.get(1)[2])))) {
                try {
                min = normalizeAmount(number(amounts.get(0)[0]), amounts.get(0)[2]);
                max = normalizeAmount(number(amounts.get(1)[0]), amounts.get(1)[2]);
                if (min.compareTo(max) > 0) { min = null; max = null; }
                else { code = unitCode(amounts.get(0)[2]); label = standardLabel(amounts.get(0)[2]); }
                } catch (RuntimeException ex) { min = null; max = null; }
            }
            if (min == null) warnings.add(RULE_VERSION + "：" + (dependent ? "比例依赖用量未确定" : amounts.size() > 1 ? "多组量冲突" : "数量或单位未知") + "，待复核：" + raw);
            if ("斤".equals(label)) warnings.add(RULE_VERSION + "：斤的地域未明确，保留原单位：" + raw);
            result.add(new ParsedIngredient(name, min, max, code, label, optional, raw, result.size() + 1));
        }
        return result;
    }

    private boolean isTool(String raw) {
        String name = raw.replaceAll("[（(].*?[）)]", "").replaceAll("[：:].*$", "").trim();
        if (name.matches(".*(?:玻璃|陶瓷).*(?:罐|容器).*")) return true;
        return MATERIAL_TOOL_WORDS.stream().anyMatch(tool -> name.equals(tool)
                || name.matches("[一二两三0-9]+[个只]?\\s*" + Pattern.quote(tool))
                || name.matches(Pattern.quote(tool) + "(?:[，,；;].*|\\s*(?:直径|尺寸|长|宽|高).*)"));
    }

    private boolean insideNote(String text, int position) {
        int depth = 0;
        for (int i = 0; i < position; i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '（') depth++;
            if (c == ')' || c == '）') depth = Math.max(0, depth - 1);
        }
        return depth > 0;
    }

    private String cleanName(String text) {
        return text.replaceAll("[\\s=＝：:,，。;；]+$", "").replaceFirst("[，,：:=＝ ]*(?:约|大约|大概)$", "").trim();
    }

    private BigDecimal number(String text) {
        if (text.matches("\\d+(?:\\.\\d+)?")) return new BigDecimal(text);
        if (text.equals("半")) return new BigDecimal("0.5");
        String[] parts = text.split("点", -1);
        // 口语省略位值（二百五）或连续非零汉字（一二）不作为明确数量。
        if (parts[0].matches(".*[一二两三四五六七八九]{2}.*") || parts[0].matches(".*[百千万][一二两三四五六七八九]$"))
            throw new IllegalArgumentException("中文数量位值不明确");
        long total = 0, section = 0, digit = 0;
        String digits = "零一二三四五六七八九";
        for (char c : parts[0].replace('两', '二').replace('〇', '零').toCharArray()) {
            int d = digits.indexOf(c);
            if (d >= 0) { digit = d; continue; }
            int power = switch (c) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; default -> throw new IllegalArgumentException("中文数量格式未知"); };
            if (power == 10000) { total += (section + digit) * power; section = 0; }
            else section += (digit == 0 ? 1 : digit) * power;
            digit = 0;
        }
        String fraction = "";
        if (parts.length == 2) {
            StringBuilder decimal = new StringBuilder(".");
            for (char c : parts[1].replace('两', '二').replace('〇', '零').toCharArray()) {
                int d = digits.indexOf(c);
                if (d < 0) throw new IllegalArgumentException("中文小数格式未知");
                decimal.append(d);
            }
            fraction = decimal.toString();
        }
        return new BigDecimal(Long.toString(total + section + digit) + fraction);
    }

    private String standardLabel(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "kg", "公斤", "千克" -> "g";
            case "g" -> "g";
            case "l", "升", "ml" -> "ml";
            default -> unit;
        };
    }

    private String unitCode(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "g", "克" -> "G";
            case "kg", "公斤", "千克" -> "G";
            case "ml", "毫升" -> "ML";
            case "l", "升" -> "ML";
            case "个", "個", "只", "条", "條", "瓣", "根", "株" -> "COUNT";
            default -> "CUSTOM";
        };
    }

    private BigDecimal normalizeAmount(BigDecimal amount, String unit) {
        // 数量、编码与标签一起归一化；原单位和原数量由rawLine保留，不能再次乘1000。
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "kg", "公斤", "千克", "l", "升" -> amount.multiply(BigDecimal.valueOf(1000));
            default -> amount;
        };
    }

    private Set<String> parseTools(String required) {
        Set<String> tools = new LinkedHashSet<>();
        if (required == null) return tools;
        for (String line : required.lines().toList()) {
            Matcher bullet = BULLET.matcher(line);
            if (!bullet.matches()) continue;
            String content = bullet.group(1);
            for (String tool : TOOL_WORDS) {
                // 词典按长名称在前排列，避免“炒锅”同时被拆成“炒锅”和泛化的“锅”。
                if (content.contains(tool) && tools.stream().noneMatch(existing -> existing.contains(tool))) {
                    tools.add(tool);
                }
            }
        }
        return tools;
    }

    private List<String> parseSteps(String operation) {
        List<String> steps = new ArrayList<>();
        if (operation == null) return steps;
        StringBuilder current = null;
        for (String line : operation.lines().toList()) {
            Matcher matcher = ORDERED_STEP.matcher(line);
            if (matcher.matches()) {
                if (current != null) steps.add(current.toString().trim());
                current = new StringBuilder(matcher.group(1));
            } else if (current != null && !line.isBlank() && !BULLET.matcher(line).matches()) {
                current.append(' ').append(line.trim());
            }
        }
        if (current != null) steps.add(current.toString().trim());
        return steps;
    }

    private String cleanTitle(String title) {
        return title.replaceFirst("\\s*(的做法|做法)\\s*$", "").trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
