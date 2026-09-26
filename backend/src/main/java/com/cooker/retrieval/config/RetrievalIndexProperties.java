package com.cooker.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 显式索引任务的配置；密钥仅由环境变量注入，不能进入版本库。
 *
 * <p>默认值就是阶段 1 冻结的基线：百炼 text-embedding-v4、1024 维、每批 10 条文本、
 * 每个步骤块 4 个连续步骤、模板版本 1。改动任何一项都会让已有投影失效，
 * 需要按 ADR-004 重建向量。
 */
@ConfigurationProperties(prefix = "cooker.retrieval.index")
public class RetrievalIndexProperties {
    /** 总开关；必须同时激活 recipe-index profile，才会产生对外部模型的调用。 */
    private boolean enabled;
    /** 单次百炼请求携带的文本条数；text-embedding-v4 上限为 10。 */
    private int batchSize = 10;
    /** 每个 steps 块包含的连续步骤数，取值 3～5，避免步骤切得过碎或单块过长。 */
    private int maxStepChunkSteps = 4;
    /** 单个块的字符数上限，用于按长度提前切分步骤块；单个超长步骤仍整体保留。 */
    private int maxChunkCharacters = 4000;
    /** chunk 拼接模板版本；模板规则变化时递增即可触发全量重建。 */
    private int documentTemplateVersion = 1;
    /** 向量模型标识；写入投影表并参与“是否已过期”的判断。 */
    private String embeddingModel = "text-embedding-v4";
    /** 向量维度；必须与 recipe_search_document.embedding 的 vector(1024) 保持一致。 */
    private int embeddingDimension = 1024;
    /** 百炼 Embedding 的 HTTP 地址，可按地域或业务空间覆盖。 */
    private String endpoint;
    /** 百炼 API Key，仅由 DASHSCOPE_API_KEY 环境变量注入，禁止写入配置文件或日志。 */
    private String apiKey;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxStepChunkSteps() { return maxStepChunkSteps; }
    public void setMaxStepChunkSteps(int maxStepChunkSteps) { this.maxStepChunkSteps = maxStepChunkSteps; }
    public int getMaxChunkCharacters() { return maxChunkCharacters; }
    public void setMaxChunkCharacters(int maxChunkCharacters) { this.maxChunkCharacters = maxChunkCharacters; }
    public int getDocumentTemplateVersion() { return documentTemplateVersion; }
    public void setDocumentTemplateVersion(int documentTemplateVersion) { this.documentTemplateVersion = documentTemplateVersion; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
