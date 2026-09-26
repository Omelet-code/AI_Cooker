package com.cooker.retrieval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 检索投影实体；embedding 只通过显式 Mapper SQL 写入，避免 ORM 隐式处理 pgvector 类型。 */
@TableName("recipe_search_document")
public class RecipeSearchDocumentEntity {
    /** 投影块主键，由数据库自增。 */
    @TableId(type = IdType.AUTO) public Long id;
    /** 对应的结构化菜谱；删除菜谱时级联删除投影。 */
    public Long recipeId;
    /** 构建该块时使用的原始 Markdown 版本。 */
    public Long sourceDocumentId;
    /** 来源类型，用于区分系统快照与用户自定义菜谱。 */
    public String sourceType;
    /** 私有菜谱所有者；系统菜谱为空。 */
    public Long ownerUserId;
    /** 可见性：PUBLIC 或 PRIVATE，检索前必须先按此字段过滤。 */
    public String visibility;
    /** 块类型：overview、ingredients、steps 或 tools。 */
    public String chunkType;
    /** 同类型块序号；步骤块拆成多块时从 0 递增。 */
    public Integer chunkNo;
    /** 拼接后的检索文本，中文全文索引 tsv 由数据库自动生成。 */
    public String content;
    /** content 的 SHA-256；未变化时跳过重复的付费调用。 */
    public String contentHash;
    /** chunk 拼接模板版本，用于模板变更后的定向重建。 */
    public Integer documentTemplateVersion;
    /** 生成该向量的模型标识。 */
    public String embeddingModel;
    /** 向量维度，当前固定为 1024。 */
    public Integer embeddingDimension;
    /** 向量化状态：PENDING、READY 或 FAILED；检索只读取 READY。 */
    public String embeddingStatus;
    /** 已脱敏的失败摘要，禁止写入 Key 或外部响应正文。 */
    public String failureMessage;
    /** 最近一次成功向量化的时间。 */
    public OffsetDateTime indexedAt;
}
