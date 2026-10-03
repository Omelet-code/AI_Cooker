package com.cooker.policy;

import com.cooker.agent.dto.RecommendationConstraints;
import com.cooker.agent.dto.RecommendationOutcome;
import com.cooker.agent.dto.RecipeRecommendation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 所有对用户展示的解释由固定中文模板与数据库字段组合，不展示模型自由文本。 */
public class RecommendationTextPolicy {
    private static final Pattern UNSAFE_FACT = Pattern.compile(
            "(?i)(?:https?://|www\\.|[a-z0-9-]+\\.(?:com|cn|net|org|io)(?:/|\\b)|"
                    + "忽略(?:以上|之前|规则|指令)|(?:系统|开发者)指令|"
                    + "(?:治愈|治疗|诊断|处方|药物|疗效|包治|根治))");
    public List<String> notices(RecommendationConstraints constraints) {
        List<String> notices = new ArrayList<>(List.of("PREFERENCE_NOT_GUARANTEED", "GENERAL_DIET_ONLY"));
        if (constraints.budgetYuan() != null) notices.add("BUDGET_NOT_VERIFIED");
        if (!constraints.availableIngredientNames().isEmpty()) notices.add("AVAILABLE_INGREDIENTS_NOT_INVENTORY");
        return List.copyOf(notices);
    }

    /** 数据库详情是可信的菜谱事实，但名称和摘要仍是外部文本，不能直接用于用户展示和快照。 */
    public RecipeRecommendation safeRecommendation(RecipeRecommendation recipe) {
        return new RecipeRecommendation(recipe.recipeId(), safeFact(recipe.name(), "菜谱"),
                safeFact(recipe.summary(), null), recipe.estimatedMinutes(), recipe.reasonCodes());
    }

    public String answer(RecommendationOutcome outcome, List<RecipeRecommendation> recipes,
            RecommendationConstraints constraints, List<String> clarificationCodes) {
        return switch (outcome) {
            case RECOMMENDED -> recommended(recipes, constraints);
            case NO_MATCH -> "没有找到同时符合当前条件的菜谱。你可以主动调整时间、厨具或排除食材后再试；我不会自动放宽条件。";
            case CLARIFICATION -> clarification(clarificationCodes);
            case UNSUPPORTED -> "我目前只能根据菜谱信息提供一般烹饪建议。请描述你想做的家常菜或烹饪条件。";
        };
    }

    private String recommended(List<RecipeRecommendation> recipes, RecommendationConstraints constraints) {
        StringBuilder answer = new StringBuilder("根据你的描述，可以先看看这些菜谱：");
        int number = 1;
        for (RecipeRecommendation recipe : recipes) {
            answer.append('\n').append(number++).append(". ").append(safeFact(recipe.name(), "菜谱"));
            if (recipe.estimatedMinutes() != null) answer.append("（约").append(recipe.estimatedMinutes()).append("分钟）");
            String summary = safeFact(recipe.summary(), null);
            if (summary != null) answer.append("：").append(summary);
        }
        if (constraints.budgetYuan() != null) answer.append("\n菜谱检索不核实预算或价格。 ");
        if (!constraints.availableIngredientNames().isEmpty())
            answer.append("你提到的已有食材只作为本次意图记录，系统没有读取或抵扣家庭库存。 ");
        answer.append("偏好不保证完全满足；这里只提供一般饮食参考，不构成诊断或治疗建议。");
        return answer.toString();
    }

    private String safeFact(String value, String fallback) {
        if (value == null || value.isBlank() || UNSAFE_FACT.matcher(value).find()) return fallback;
        String clean = value.replaceAll("[\\p{Cntrl}<>`*_#\\[\\]{}]", " ").replaceAll("\\s+", " ").strip();
        return clean.isBlank() ? fallback : clean;
    }

    private String clarification(List<String> codes) {
        if (codes == null || codes.isEmpty()) return "请再补充一下你想做什么菜或有哪些烹饪条件。";
        List<String> questions = new ArrayList<>();
        for (String code : codes) questions.add(switch (code) {
            case "TIME_AMBIGUOUS" -> "你希望烹饪时间控制在多少分钟内？";
            case "TOOLS_AMBIGUOUS" -> "你现在可以使用哪些厨具？";
            case "EXCLUSION_AMBIGUOUS" -> "有哪些食材需要排除？";
            default -> "你想找哪类菜谱？";
        });
        return String.join(" ", questions);
    }
}
