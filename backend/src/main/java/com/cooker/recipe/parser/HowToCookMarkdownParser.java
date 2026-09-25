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
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final Pattern DIFFICULTY = Pattern.compile("预估烹饪难度\\s*[：:]\\s*([★☆]{1,5})");
    private static final Pattern CALORIES = Pattern.compile("预估卡路里\\s*[：:]\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern SERVINGS = Pattern.compile("(\\d+)\\s*(?:[-~～至]\\s*(\\d+)\\s*)?人");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|小時|分钟|分鐘|分|h|min)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDERED_STEP = Pattern.compile("^\\s*\\d+[.、]\\s*(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+?)\\s*$");
    private static final Pattern AMOUNT = Pattern.compile(
            "^(.+?)\\s+(\\d+(?:\\.\\d+)?)(?:\\s*[-~～至]\\s*(\\d+(?:\\.\\d+)?))?\\s*(g|kg|克|公斤|ml|毫升|l|升|个|個|只|条|條|瓣|根|株|盒|片|块|塊|勺|匙|杯|斤)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> TOOL_WORDS = List.of(
            "空气炸锅", "电饭煲", "微波炉", "高压锅", "压力锅", "平底锅", "炒锅", "汤锅", "蒸锅", "砂锅",
            "烤箱", "蒸箱", "电磁炉", "燃气灶", "破壁机", "搅拌机", "料理机", "榨汁机", "面包机",
            "打蛋器", "烤盘", "模具", "菜刀", "砧板", "锅");

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
        List<ParsedIngredient> ingredients = parseIngredients(sections.get("计算"), warnings);
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
        Matcher matcher = SERVINGS.matcher(Optional.ofNullable(calculation).orElse(""));
        if (!matcher.find()) {
            warnings.add("未解析到份数");
            return null;
        }
        if (matcher.group(2) != null) {
            warnings.add("份数为范围，当前只保存下限");
        }
        return Integer.valueOf(matcher.group(1));
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

    private List<ParsedIngredient> parseIngredients(String calculation, List<String> warnings) {
        List<ParsedIngredient> result = new ArrayList<>();
        if (calculation == null) {
            warnings.add("缺少计算章节，未解析用料");
            return result;
        }
        int order = 1;
        for (String line : calculation.lines().toList()) {
            Matcher bullet = BULLET.matcher(line);
            if (!bullet.matches()) continue;
            String raw = bullet.group(1).trim();
            if (raw.isBlank()) continue;
            boolean optional = raw.contains("可选") || raw.contains("選用");
            String withoutOptional = raw.replace("（可选）", "").replace("(可选)", "").replace("（選用）", "").trim();
            Matcher amount = AMOUNT.matcher(withoutOptional);
            if (amount.matches()) {
                String name = amount.group(1).trim();
                String unitLabel = amount.group(4);
                result.add(new ParsedIngredient(name, normalizeAmount(new BigDecimal(amount.group(2)), unitLabel),
                        normalizeAmount(amount.group(3) == null ? new BigDecimal(amount.group(2)) : new BigDecimal(amount.group(3)), unitLabel),
                        unitCode(unitLabel), unitLabel, optional, raw, order++));
            } else {
                // 这里不抛弃无法识别的原料行，后续仍可人工治理单位或名称。
                String name = withoutOptional.replaceAll("（.*?）|\\(.*?\\)", "").trim();
                result.add(new ParsedIngredient(name.isBlank() ? raw : name, null, null, null, null, optional, raw, order++));
                warnings.add("原料数量或单位未标准化：" + raw);
            }
        }
        if (result.isEmpty()) warnings.add("计算章节未找到项目符号原料");
        return result;
    }

    private String unitCode(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "g", "克" -> "G";
            case "kg", "公斤" -> "G";
            case "ml", "毫升" -> "ML";
            case "l", "升" -> "ML";
            case "个", "個", "只", "条", "條", "瓣", "根", "株" -> "COUNT";
            default -> "CUSTOM";
        };
    }

    private BigDecimal normalizeAmount(BigDecimal amount, String unit) {
        // G/ML 作为质量、体积的结构化基准单位；显示时仍使用原始 unitLabel，避免改变原文语义。
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "kg", "公斤", "l", "升" -> amount.multiply(BigDecimal.valueOf(1000));
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
