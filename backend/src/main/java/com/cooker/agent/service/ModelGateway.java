package com.cooker.agent.service;

import com.cooker.agent.dto.ModelChatRequest;
import com.cooker.agent.dto.ModelChatResponse;

/** 面向 Agent 模块的单次模型调用能力，不暴露供应商客户端。 */
public interface ModelGateway {
    ModelChatResponse complete(ModelChatRequest request);
    // 旧测试替身/实现无需被迫支持新收费能力，绝不退回旧调用偷偷生成。
    default ModelChatResponse completeGuide(ModelChatRequest request) {
        throw new ModelGatewayException(ModelGatewayException.Reason.INVALID_INPUT,"指导模型能力尚未提供");
    }
}
