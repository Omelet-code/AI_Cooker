package com.cooker.agent.service;

import com.cooker.agent.dto.ModelMessage;
import java.util.List;

/** 仅完整指导使用的固定输入档位；系统消息也计入 Unicode 码点。 */
public final class GuideInputProfile {
    public static final int MAX_CODE_POINTS = 64_000;
    public static final int MAX_MESSAGES = 16;

    private GuideInputProfile() {}

    public static long codePoints(List<ModelMessage> messages) {
        return messages.stream().mapToLong(m -> m.content().codePointCount(0, m.content().length())).sum();
    }
}
