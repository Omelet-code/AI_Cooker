package com.cooker.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import com.cooker.agent.dto.ModelOutputMode;

/** 只在本次同步调用中创建绑定单个用户Key的模型客户端。 */
public interface DeepSeekChatClientFactory {
    ChatModel create(String apiKey, ModelOutputMode outputMode);
}
