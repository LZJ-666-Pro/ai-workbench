package com.ai.workbench.core.api;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 推送小工具：客户端已断开时 send 会抛异常，统一静默处理。
 */
public final class SseSender {

    private SseSender() {
    }

    public static void send(SseEmitter emitter, Object payload) {
        try {
            emitter.send(payload);
        } catch (Exception e) {
            // 客户端断开等场景，静默结束本次推送
        }
    }
}
