package com.cooker.agent.service;

import com.cooker.agent.dto.ModelChatRequest;
import com.cooker.agent.dto.ModelChatResponse;

/** 面向 Agent 模块的单次模型调用能力，不暴露供应商客户端。 */
public interface ModelGateway {
    ModelChatResponse complete(ModelChatRequest request);
}
