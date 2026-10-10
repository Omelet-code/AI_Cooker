package com.cooker.agent.service;

import org.springframework.stereotype.Component;

/** 推荐软预算使用单调时间；测试可注入可控实现，不修改系统时钟。 */
@Component
public class RecommendationClock {
    public long nanoTime() { return System.nanoTime(); }
}
