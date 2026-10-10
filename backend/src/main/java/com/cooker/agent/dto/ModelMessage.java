package com.cooker.agent.dto;

/** 网关允许传递的纯文本消息；不承载供应商对象或工具执行指令。 */
public record ModelMessage(Role role, String content) {
    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    @Override
    public String toString() {
        return "ModelMessage[role=" + role + ", content=[已隐藏]]";
    }
}
