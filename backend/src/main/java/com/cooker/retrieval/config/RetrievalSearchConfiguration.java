package com.cooker.retrieval.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Web 检索始终读取模型版本配置；是否具备 Key 只影响查询向量通道。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalIndexProperties.class)
public class RetrievalSearchConfiguration {
}
