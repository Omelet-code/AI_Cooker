package com.cooker.agent.dto;

import java.util.List;

/** 一次同步模型请求；toString 不输出对话内容。 */
public record ModelChatRequest(List<ModelMessage> messages, ModelOutputMode outputMode) {
    @Override
    public String toString() {
        return "ModelChatRequest[messages=" + (messages == null ? "null" : messages.size())
                + ", outputMode=" + outputMode + ", content=[已隐藏]]";
    }
}
