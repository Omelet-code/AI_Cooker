package com.cooker.retrieval.service.impl;

import com.cooker.recipe.dto.RecipeIndexProjection;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.dto.RecipeSearchDocumentChunk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 根据结构化事实生成稳定的检索文本，不读取原始 Markdown，也不调用 LLM。
 * 所有块重复保留菜名和分类，避免单独命中步骤或原料时丢失菜谱上下文。
 */
@Component
@Profile("recipe-index")
@ConditionalOnProperty(prefix = "cooker.retrieval.index", name = "enabled", havingValue = "true")
public class RecipeSearchDocumentBuilder {
    private final RetrievalIndexProperties properties;

    public RecipeSearchDocumentBuilder(RetrievalIndexProperties properties) {
        this.properties = properties;
    }

    /** 为一道菜谱生成全部检索块；同一份事实快照每次都会得到完全相同的块与哈希。 */
    public List<RecipeSearchDocumentChunk> build(RecipeIndexProjection projection) {
        validateProperties();
        List<RecipeSearchDocumentChunk> chunks = new ArrayList<>();
        chunks.add(chunk(projection, "overview", 0, overviewContent(projection)));
        if (!projection.ingredientNames().isEmpty()) {
            chunks.add(chunk(projection, "ingredients", 0,
                    context(projection) + "原料：" + String.join("、", projection.ingredientNames())));
        }
        if (!projection.toolNames().isEmpty()) {
            chunks.add(chunk(projection, "tools", 0,
                    context(projection) + "需要厨具：" + String.join("、", projection.toolNames())));
        }
        chunks.addAll(stepChunks(projection));
        return List.copyOf(chunks);
    }

    /**
     * 把步骤拆成多个块：优先按连续步骤数上限切分，超过字符上限时提前切分。
     * 步骤顺序与原文完全一致，单个超长步骤整体保留，保证烹饪动作不被截断。
     */
    private List<RecipeSearchDocumentChunk> stepChunks(RecipeIndexProjection projection) {
        List<RecipeSearchDocumentChunk> chunks = new ArrayList<>();
        List<String> currentSteps = new ArrayList<>();
        int currentLength = context(projection).length();
        int chunkNo = 0;
        for (int index = 0; index < projection.steps().size(); index++) {
            String numberedStep = "步骤" + (index + 1) + "：" + normalize(projection.steps().get(index));
            boolean reachesStepLimit = currentSteps.size() >= properties.getMaxStepChunkSteps();
            boolean reachesLengthLimit = !currentSteps.isEmpty()
                    && currentLength + numberedStep.length() + 1 > properties.getMaxChunkCharacters();
            if (reachesStepLimit || reachesLengthLimit) {
                chunks.add(chunk(projection, "steps", chunkNo++, context(projection) + String.join("\n", currentSteps)));
                currentSteps.clear();
                currentLength = context(projection).length();
            }
            // 单个异常长步骤仍作为完整语义单元保存，禁止按字符截断导致烹饪动作失真。
            currentSteps.add(numberedStep);
            currentLength += numberedStep.length() + 1;
        }
        if (!currentSteps.isEmpty()) {
            chunks.add(chunk(projection, "steps", chunkNo, context(projection) + String.join("\n", currentSteps)));
        }
        return chunks;
    }

    /** 概览块：菜名与分类，外加按字段存在性拼装的简介、难度、用时、份数和热量。 */
    private String overviewContent(RecipeIndexProjection projection) {
        List<String> lines = new ArrayList<>();
        lines.add(context(projection).strip());
        if (projection.summary() != null && !projection.summary().isBlank()) lines.add("简介：" + normalize(projection.summary()));
        if (projection.difficulty() != null) lines.add("烹饪难度：" + projection.difficulty());
        if (projection.estimatedMinutes() != null) lines.add("预计用时：" + projection.estimatedMinutes() + "分钟");
        if (projection.servings() != null) lines.add("份数：" + projection.servings());
        if (projection.caloriesKcal() != null) lines.add("热量：" + projection.caloriesKcal() + "千卡");
        return String.join("\n", lines);
    }

    /** 每个块都重复的上下文前缀；命中孤立步骤时仍能还原菜名与分类。 */
    private String context(RecipeIndexProjection projection) {
        return "菜名：" + normalize(projection.name()) + "\n分类：" + normalize(projection.categoryCode()) + "\n";
    }

    /** 统一生成块对象：先规范化空白再计算内容哈希，哈希用于后续的跳过判断。 */
    private RecipeSearchDocumentChunk chunk(RecipeIndexProjection projection, String chunkType, int chunkNo, String content) {
        String normalizedContent = normalize(content);
        return new RecipeSearchDocumentChunk(projection.recipeId(), projection.sourceDocumentId(), projection.sourceType(),
                projection.ownerUserId(), projection.visibility(), chunkType, chunkNo, normalizedContent, sha256(normalizedContent));
    }

    /** 折叠连续空白并去除首尾空格，保证同一语义内容得到稳定哈希。 */
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /** 计算 UTF-8 内容的 SHA-256 十六进制摘要，与数据库 char(64) 字段对应。 */
    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    /** 校验分块参数：步骤块过小会丢失上下文，过大则削弱步骤级召回。 */
    private void validateProperties() {
        if (properties.getMaxStepChunkSteps() < 3 || properties.getMaxStepChunkSteps() > 5) {
            throw new IllegalArgumentException("每个步骤块必须包含连续 3 到 5 个步骤");
        }
        if (properties.getMaxChunkCharacters() < 500) {
            throw new IllegalArgumentException("步骤块最大字符数不能小于 500");
        }
    }
}
