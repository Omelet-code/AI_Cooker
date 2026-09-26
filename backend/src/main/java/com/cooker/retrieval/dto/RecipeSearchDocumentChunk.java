package com.cooker.retrieval.dto;

/**
 * 单个可独立嵌入与重建的检索文本块。
 *
 * <p>块是重建与跳过的最小单位：内容哈希、模板版本、模型与维度共同决定它是否需要重新调用模型。
 *
 * @param recipeId         对应结构化菜谱主键，检索命中后据此回查真实菜谱
 * @param sourceDocumentId 构建该块时使用的原始 Markdown 版本
 * @param sourceType       来源类型，用于区分系统快照与用户自定义菜谱
 * @param ownerUserId      私有菜谱所有者；系统菜谱为空
 * @param visibility       PUBLIC 或 PRIVATE，检索前必须先做权限过滤
 * @param chunkType        overview / ingredients / steps / tools
 * @param chunkNo          同类型块的序号，步骤多块从 0 开始
 * @param content          按确定性模板拼接并规范化后的文本
 * @param contentHash      content 的 SHA-256，用于判断是否需要重新向量化
 */
public record RecipeSearchDocumentChunk(
        Long recipeId,
        Long sourceDocumentId,
        String sourceType,
        Long ownerUserId,
        String visibility,
        String chunkType,
        int chunkNo,
        String content,
        String contentHash) {

    /** 稳定块标识，格式为 {@code chunkType:chunkNo}，与投影表唯一约束一一对应。 */
    public String key() {
        return chunkType + ':' + chunkNo;
    }
}
