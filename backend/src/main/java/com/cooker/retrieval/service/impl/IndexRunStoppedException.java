package com.cooker.retrieval.service.impl;

/** 必须穿透逐菜catch的停止信号；是否已发送决定当前批是否可记FAILED。 */
public final class IndexRunStoppedException extends RuntimeException {
    private final boolean sent;
    public IndexRunStoppedException(String reason, boolean sent) {
        super(reason);
        this.sent = sent;
    }
    public boolean sent() { return sent; }
}
