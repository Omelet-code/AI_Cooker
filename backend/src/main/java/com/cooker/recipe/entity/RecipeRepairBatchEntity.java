package com.cooker.recipe.entity;

/** 审计专属持久化实体，不作为HTTP或模型对象。 */
public class RecipeRepairBatchEntity {
    public Long id;
    public String requestKey;
    public String inputHash;
    public String manifestHash;
    public String approvalHash;
    public String ruleVersion;
    public String targetDatabase;
    public String targetIdentifier;
    public String reviewer;
    public String status;
    public String stateJson;
    public String resultJson;
}
